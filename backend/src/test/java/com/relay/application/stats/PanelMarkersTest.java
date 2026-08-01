package com.relay.application.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.orchestrator.AgentJournal;
import com.relay.application.orchestrator.Coordinator;
import com.relay.application.orchestrator.Planner;
import com.relay.application.orchestrator.RunService;
import com.relay.application.orchestrator.ToolAgent;
import com.relay.application.orchestrator.Verifier;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Why this test exists.
 *
 * <p>The panel has to answer a question the schema cannot: was this step turned down by a
 * person, or was it closed because somebody stopped the whole run? Both end as
 * {@code decision = 'rejected'} on a {@code REJECTED} step, and nothing else about them
 * differs. On the live box that cost the product its best evidence — four of the six
 * lines under "Red gerekçeleri" were one person pressing Durdur, on the one list that is
 * supposed to prove the approval gate is worth its friction (#54).
 *
 * <p>So the panel's SQL keys on the sentence {@code Coordinator.stop} writes. That is a
 * literal shared by two files that have no compiler-visible relationship, which is
 * exactly the kind of coupling that rots silently: someone rewords a Turkish string,
 * every test still passes, and the panel starts calling cancellations refusals again with
 * no error anywhere. This test is the compiler that link does not have. It drives a real
 * cancellation through {@code RunService} and reads what actually landed on the step.
 *
 * <p>If it fails, the fix is not to loosen the assertion — it is to update
 * {@link PanelStatsRepository#CANCEL_REASON_PREFIX}, or better, to give the write-off a
 * decision value of its own so the panel can stop reading prose.
 */
class PanelMarkersTest {

    /** A run parked at the approval gate — the case where a person is the only thing missing. */
    private record Rig(RunService service, Run run) {
    }

    private Rig parkedOnApproval() {
        ToolRegistry tools = new ToolRegistryImpl(
                List.of(new JiraTool.UpdateIssue("replay", new FixtureStore())));
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);

        Run run = Run.create("KAN-4 kaydını kapat", Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(
                Step.create(run.id(), 1, "Durumu güncelle", "jira-agent", "jira.updateIssue",
                        Map.of("issueKey", "KAN-4", "status", "Done")),
                Step.create(run.id(), 2, "İkinci kaydı da güncelle", "jira-agent", "jira.updateIssue",
                        Map.of("issueKey", "KAN-5", "status", "Done"))));
        runs.save(run);
        service.driveNow(run.id());
        return new Rig(service, run);
    }

    @Test
    void a_stopped_run_writes_off_its_steps_with_the_sentence_the_panel_looks_for() {
        Rig rig = parkedOnApproval();

        rig.service().cancel(rig.run().id(), "qa+relay@samedbilgin.com");

        assertThat(rig.run().steps()).isNotEmpty();
        assertThat(rig.run().steps()).allSatisfy(step -> assertThat(step.rejectReason())
                .as("the panel separates cancellations from refusals by this prefix")
                .startsWith(PanelStatsRepository.CANCEL_REASON_PREFIX));
    }

    /**
     * The other half of the same promise. Matching a prefix is only safe while the actor
     * comes after it — the panel's {@code like 'akış iptal edildi%'} would miss every
     * write-off the moment the name moved to the front of the sentence.
     */
    @Test
    void the_name_of_whoever_stopped_the_run_comes_after_the_prefix_not_before_it() {
        Rig rig = parkedOnApproval();

        rig.service().cancel(rig.run().id(), "qa+relay@samedbilgin.com");

        assertThat(rig.run().steps().get(0).rejectReason())
                .isEqualTo(PanelStatsRepository.CANCEL_REASON_PREFIX + " (qa+relay@samedbilgin.com)");
    }

    /**
     * And the guard on the other side: a sentence a human typed must not start with the
     * prefix by accident, or a refusal would be filed as a cancellation. Nothing enforces
     * that about free text — but the panel also requires the run to be {@code cancelled},
     * and a refusal leaves the run running. This pins that second condition.
     */
    /**
     * The other literal the panel reads. "Düzeltilip onaylandı" is counted from the
     * journal, not from {@code steps.params_locked}: the column is cleared when a write
     * bounces back to the gate after the provider refused it, which would drop exactly
     * the steps a person had to correct twice.
     */
    @Test
    void an_edited_approval_leaves_the_journal_line_the_panel_counts() {
        Rig rig = parkedOnApproval();
        Step gate = rig.run().steps().get(0);

        rig.service().approve(rig.run().id(), gate.id(), Map.of("status", "Blocked"),
                "qa+relay@samedbilgin.com");

        assertThat(rig.run().messages())
                .as("the panel finds an edited approval by this prefix and by from=user")
                .anySatisfy(message -> {
                    assertThat(message.content()).startsWith(PanelStatsRepository.PARAM_EDIT_PREFIX);
                    assertThat(message.fromAgent()).isEqualTo(AgentRole.USER);
                    assertThat(message.stepId()).isEqualTo(gate.id());
                });
    }

    /**
     * And the boundary the count depends on: approving what is on screen unchanged is not
     * an intervention, and must not be filed as one. Otherwise "insan kararların %X'inde
     * gönderileni değiştirdi" would just be the approval rate wearing a different name.
     */
    @Test
    void approving_without_changing_anything_writes_no_edit_line() {
        Rig rig = parkedOnApproval();
        Step gate = rig.run().steps().get(0);

        rig.service().approve(rig.run().id(), gate.id(), "qa+relay@samedbilgin.com");

        assertThat(rig.run().messages()).noneSatisfy(message ->
                assertThat(message.content()).startsWith(PanelStatsRepository.PARAM_EDIT_PREFIX));
    }

    @Test
    void a_refusal_keeps_the_run_open_so_it_can_never_be_read_as_a_cancellation() {
        Rig rig = parkedOnApproval();
        Step gate = rig.run().steps().get(0);

        rig.service().reject(rig.run().id(), gate.id(), "Kanal yanlış — #relay-qa olmalı",
                "qa+relay@samedbilgin.com");

        assertThat(gate.rejectReason()).isEqualTo("Kanal yanlış — #relay-qa olmalı");
        assertThat(rig.run().status().wire()).isNotEqualTo("cancelled");
    }
}
