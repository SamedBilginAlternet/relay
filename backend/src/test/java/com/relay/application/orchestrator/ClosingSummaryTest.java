package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A finished run used to end on "Akış bitti: done · 1.018 token · $0,0012" — accurate,
 * and no answer to the question the user asked. The closing line now says what happened,
 * and says nothing at all rather than blocking the run when the model cannot.
 */
class ClosingSummaryTest {

    private static class ScriptedLlm implements LlmClient {
        private final String summary;
        private final boolean explode;
        LlmRequest lastSummaryRequest;

        ScriptedLlm(String summary, boolean explode) {
            this.summary = summary;
            this.explode = explode;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (LlmPurpose.SUMMARIZE.equals(request.purpose())) {
                if (explode) {
                    throw new IllegalStateException("model down");
                }
                lastSummaryRequest = request;
                return new LlmResponse(summary, 40, 20, 0.0001, "scripted", false);
            }
            return new LlmResponse("{\"pass\":true,\"reason\":\"tamam\"}", 10, 5, 0.0001, "scripted", false);
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    private Run driveOneReadStep(ScriptedLlm llm) {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.SearchIssues("replay", fixtures)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock, new Summarizer(llm));

        Run run = Run.create("Blocker kayıtlarını getir", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Blocker'ları bul", AgentRole.COORDINATOR,
                "jira.searchIssues", Map.of("jql", "labels = blocker"))));
        runs.save(run);
        new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0).driveNow(run.id());
        return run;
    }

    @Test
    void the_last_word_to_the_user_is_what_happened() {
        Run run = driveOneReadStep(new ScriptedLlm("3 blocker kaydı bulundu: RELAY-14, RELAY-21, RELAY-33.", false));

        assertThat(run.messages()).last().satisfies(message -> {
            assertThat(message.toAgent()).isEqualTo(AgentRole.USER);
            assertThat(message.content()).contains("token");
        });
        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).isEqualTo("3 blocker kaydı bulundu: RELAY-14, RELAY-21, RELAY-33."));
    }

    @Test
    void the_summary_sees_the_step_results_not_just_the_goal() {
        ScriptedLlm llm = new ScriptedLlm("özet", false);
        driveOneReadStep(llm);

        assertThat(llm.lastSummaryRequest.user())
                .contains("Blocker kayıtlarını getir")
                .contains("jira.searchIssues")
                .contains("RELAY-14");
    }

    /** The stub writes template filler; an honest cost line beats a fake answer. */
    @Test
    void a_degraded_model_writes_no_summary_at_all() {
        ScriptedLlm degraded = new ScriptedLlm("Relay özeti — bir şeyler oldu", false) {
            @Override
            public boolean degraded() {
                return true;
            }
        };
        Run run = driveOneReadStep(degraded);

        assertThat(run.messages()).last().satisfies(message ->
                assertThat(message.content()).startsWith("Akış bitti:"));
        assertThat(run.messages()).noneSatisfy(message ->
                assertThat(message.content()).contains("Relay özeti"));
    }

    /**
     * The client can also go degraded between the check and the call. Live, a rejected run
     * closed on the stub's "Sonuç bulunamadı: <hedef>" — filler, and a wrong account of what
     * happened: the user rejected the step, nothing was searched for and not found.
     */
    @Test
    void a_summary_that_came_from_the_stub_is_not_written_either() {
        ScriptedLlm sneaky = new ScriptedLlm("Sonuç bulunamadı: Blocker kayıtlarını getir", false) {
            @Override
            public LlmResponse complete(LlmRequest request) {
                LlmResponse response = super.complete(request);
                return LlmPurpose.SUMMARIZE.equals(request.purpose())
                        ? new LlmResponse(response.content(), response.promptTokens(),
                                response.completionTokens(), response.costUsd(), "stub", true)
                        : response;
            }
        };
        Run run = driveOneReadStep(sneaky);

        assertThat(run.messages()).last().satisfies(message ->
                assertThat(message.content()).startsWith("Akış bitti:"));
        assertThat(run.messages()).noneSatisfy(message ->
                assertThat(message.content()).contains("Sonuç bulunamadı"));
    }

    /** A model outage must cost the wording, never the run. */
    @Test
    void a_failing_summary_leaves_the_run_finished_anyway() {
        Run run = driveOneReadStep(new ScriptedLlm(null, true));

        assertThat(run.status().terminal()).isTrue();
        assertThat(run.messages()).last().satisfies(message ->
                assertThat(message.content()).startsWith("Akış bitti:"));
    }

    /**
     * Live: a search returned KAN-11 down to KAN-1 and the closing line reported
     * "KAN-11, KAN-12 ve KAN-13". The last two do not exist. The grounding guard covers
     * parameters headed for a provider; this text goes straight to the person.
     */
    @Test
    void a_summary_that_names_a_record_the_run_never_saw_is_dropped() {
        Run run = driveOneReadStep(new ScriptedLlm(
                "3 kayıt bulundu: KAN-11, KAN-12 ve KAN-13.", false));

        assertThat(run.messages()).noneSatisfy(message ->
                assertThat(message.content()).contains("KAN-12"));
        assertThat(run.messages()).last().satisfies(message ->
                assertThat(message.content()).startsWith("Akış bitti:"));
    }

    @Test
    void a_summary_that_only_names_what_came_back_survives() {
        Run run = driveOneReadStep(new ScriptedLlm(
                "3 blocker kaydı bulundu: RELAY-14, RELAY-21, RELAY-33.", false));

        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).contains("RELAY-14"));
    }

    /** Prose with no record key at all is not suspicious — most summaries look like this. */
    @Test
    void a_summary_without_any_key_is_left_alone() {
        Run run = driveOneReadStep(new ScriptedLlm("Bugün bekleyen bir iş çıkmadı.", false));

        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).isEqualTo("Bugün bekleyen bir iş çıkmadı."));
    }
}
