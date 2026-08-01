package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>A plan that cannot be read now stops the run (#143), which is right — but the first
 * live failure closed with {@code Akış bitti: failed · 0 token · $0.000000} after a
 * planning call that really had spent its tokens. The recovery path re-read the run from
 * the repository, so everything the failed attempt had recorded on the in-memory
 * aggregate was thrown away.
 *
 * <p>On a product whose whole pitch is that it counts what it spends, a failure that
 * reports spending nothing is the one kind of zero that must never appear. It is the same
 * rule as {@code premiumCostUsd}: unknown is null, and zero is a measurement.
 */
class FailedPlanCostTest {

    /** Answers prose, expensively — the shape a reasoning model degrades into. */
    private static class Rambling implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse("Let me think about your inbox first…", 4_000, 800, 0.003_1,
                    "fallback:reasoner", false);
        }

        @Override
        public String name() {
            return "rambling";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    @Test
    void a_run_that_failed_while_planning_still_reports_what_the_planning_cost() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        ToolRegistry tools = new ToolRegistryImpl(
                List.of(new JiraTool.SearchIssues("replay", new FixtureStore())));
        LlmClient llm = new Rambling();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal =
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, new TestDoubles.RecordingEventPublisher(), journal, clock);

        Run run = Run.create("Maillerime bak ve KAN'da kayıt aç", clock.now(), 1.0);
        runs.save(run);
        coordinator.drive(run.id());

        Run closed = runs.findById(run.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(closed.costTokens()).as("the planning call really happened").isPositive();
        assertThat(closed.costUsd()).isPositive();
    }
}
