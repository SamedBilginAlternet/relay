package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.support.OrchestratorHarness;
import org.junit.jupiter.api.Test;

/**
 * The goal is pasted verbatim into every planner and specialist prompt, so an unbounded
 * one spends the run's whole budget before a tool is ever called. Live, a 20 000 character
 * goal was accepted with a 202.
 */
class GoalLimitTest {

    private RunService service() {
        return OrchestratorHarness.withNoTools().build().service;
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
