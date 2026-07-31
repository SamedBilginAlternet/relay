package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * "Yarın müdürün 'kim, neyi, neden değiştirdi' derse cevap bir tık uzakta" is the closing
 * line of the demo, and the trail could not answer the first third of it: every decision
 * was recorded as a generic user. On a shared workspace that is the whole question.
 */
class AuditActorTest {

    private record Rig(RunService service, Run run) {
    }

    private Rig parkedWrite() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.UpdateIssue("replay", fixtures)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
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

        Run run = Run.create("KAN-4 kaydını kapat", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Durumu güncelle", "jira-uzmani",
                "jira.updateIssue", Map.of("issueKey", "KAN-4", "status", "Done"))));
        runs.save(run);
        service.driveNow(run.id());
        return new Rig(service, run);
    }

    @Test
    void the_trail_names_who_approved() {
        Rig rig = parkedWrite();

        rig.service().approve(rig.run().id(), rig.run().steps().get(0).id(), "ayse@sirket.com");

        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content()).contains("Onaylandı (ayse@sirket.com)"));
    }

    @Test
    void the_trail_names_who_rejected_and_why() {
        Rig rig = parkedWrite();

        rig.service().reject(rig.run().id(), rig.run().steps().get(0).id(),
                "KAN-4 bilerek açık kalacak", "mert@sirket.com");

        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content())
                        .contains("Reddedildi (mert@sirket.com)", "bilerek açık kalacak"));
    }

    /** Without a session — replay demos, scripts — the line stays clean rather than lying. */
    @Test
    void an_unattributed_decision_says_nothing_extra() {
        Rig rig = parkedWrite();

        rig.service().approve(rig.run().id(), rig.run().steps().get(0).id());

        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content()).isEqualTo("Onaylandı — devam et."));
    }
}
