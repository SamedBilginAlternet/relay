package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
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
 * The routing is worth nothing if the answer to "which model, and what would the strong one
 * have cost" does not survive the trip to the thing that writes cost down.
 *
 * <p>It did not. Every call between the model and the coordinator — {@code StepOutcome},
 * {@code Verifier.Verdict}, {@code ToolAgent.ParamRefresh} — carried two numbers and dropped
 * the rest, so both columns stayed null on every row in production while the tier split
 * worked perfectly underneath. A feature nobody can see is a feature nobody built.
 */
class CostAttributionTest {

    /** Answers as the routed tiers do: cheap for verification, dear for parameters. */
    private static class TieredLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            if (LlmPurpose.VERIFY.equals(request.purpose())) {
                // The small tier: what it cost, and what the strong one would have.
                return new LlmResponse("{\"pass\":true,\"reason\":\"tamam\"}", 800, 200, 0.000_060,
                        "groq:llama-3.1-8b-instant", false, 0.000_750);
            }
            return new LlmResponse("{\"jql\":\"project = KAN\"}", 1_000, 200, 0.000_750,
                    "groq:llama-3.3-70b-versatile", false, 0.000_750);
        }

        @Override
        public String name() {
            return "tiered";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    private Run driven() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.SearchIssues("replay", fixtures)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new TieredLlm();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0);

        Run run = Run.create("KAN'daki açık kayıtları listele", clock.now(), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Kayıtları ara",
                com.relay.domain.AgentRole.toolAgent("jira.searchIssues"), "jira.searchIssues",
                Map.of("jql", "project = KAN"))));
        runs.save(run);
        service.driveNow(run.id());
        return run;
    }

    @Test
    void a_step_records_which_model_answered() {
        Step step = driven().steps().get(0);

        assertThat(step.model()).as("null here is what the screen showed for weeks")
                .isNotNull();
        assertThat(step.tokens()).isPositive();
    }

    /**
     * The number the whole comparison rests on: the same measured tokens, priced on the
     * strong model. It has to be at least the real cost — a step that ran cheap must not
     * claim the expensive model would have been cheaper.
     */
    @Test
    void a_step_records_what_the_strong_model_would_have_cost() {
        Step step = driven().steps().get(0);

        assertThat(step.premiumCostUsd()).isNotNull();
        assertThat(step.premiumCostUsd()).isGreaterThanOrEqualTo(step.costUsd());
    }

    /** A run with no model call at all says "unknown", never zero — see LlmResponse. */
    @Test
    void a_step_with_no_model_call_claims_no_saving() {
        StepOutcome outcome = StepOutcome.failed("unknown tool: nope.thing", 0, 0);

        assertThat(outcome.premiumCostUsd()).isNull();
        assertThat(outcome.model()).isNull();
    }
}
