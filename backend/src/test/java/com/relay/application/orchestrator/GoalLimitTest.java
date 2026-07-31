package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The goal is pasted verbatim into every planner and specialist prompt, so an unbounded
 * one spends the run's whole budget before a tool is ever called. Live, a 20 000 character
 * goal was accepted with a 202.
 */
class GoalLimitTest {

    private RunService service() {
        ToolRegistry tools = new ToolRegistryImpl(List.of());
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
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
        return new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0);
    }

    @Test
    void an_oversized_goal_is_refused_before_any_model_call() {
        String wall = "a".repeat(20_000);

        assertThatThrownBy(() -> service().start(wall, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void a_paragraph_of_context_is_still_fine() {
        service().start("a".repeat(1_500), 1.0);
    }
}
