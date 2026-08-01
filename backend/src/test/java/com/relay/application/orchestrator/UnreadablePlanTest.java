package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.cost.CostMeter;
import com.relay.application.port.LlmClient;
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
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Measured on the live box on 2026-08-01, with all seven Groq keys at their daily
 * token wall and the paid tier answering every call: the goal
 *
 * <blockquote>"Bugünkü maillerime bak, iş talebi ya da hata bildirimi olanlar için KAN
 * projesinde kayıt aç."</blockquote>
 *
 * <p>produced the journal line {@code 1 adımlık plan hazır: 1) Hedefi özetle}, ran that
 * one step, had it verified — "Hedef özetlenmiştir" — and closed the run as
 * <b>Tamamlandı</b>. No mailbox was read. No record was opened. Two of three goals tried
 * that afternoon did the same thing, and the only trace on screen was a step count.
 *
 * <p>That is the worst failure this product can have. Its entire claim is that you can
 * see what it did; reporting success having done nothing is that claim inverted. So an
 * answer that is not a plan stops the run and says so, and the case that legitimately has
 * nothing to run — a goal like "dur" — still gets its one step.
 */
class UnreadablePlanTest {

    /** Answers exactly what a reasoning model answered live: prose, and no array. */
    private static class Rambling implements LlmClient {
        private final String reply;

        Rambling(String reply) {
            this.reply = reply;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(reply, 900, 200, 0.000_2, "fallback:reasoner", false);
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

    private Planner planner(String reply) {
        ToolRegistry tools = new ToolRegistryImpl(
                List.of(new JiraTool.SearchIssues("replay", new FixtureStore())));
        return new Planner(new Rambling(reply), tools, new CostMeter(),
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), new TestDoubles.FixedClock()));
    }

    private Run goal(String text) {
        return Run.create(text, new TestDoubles.FixedClock().now(), 1.0);
    }

    @Test
    void a_model_that_answers_with_prose_stops_the_run_instead_of_summarising_the_goal() {
        Planner planner = planner("I'll help you with that. First, let me look at your inbox…");

        assertThatThrownBy(() -> planner.plan(goal("Maillerime bak ve KAN'da kayıt aç")))
                .isInstanceOf(Planner.PlanUnreadableException.class)
                // Turkish, and it says what to do about it — the reader is not a developer.
                .hasMessageContaining("Plan kurulamadı");
    }

    /** Its own thinking, then nothing. The exact shape a reasoning model degrades into. */
    @Test
    void a_model_that_answers_with_its_own_thinking_is_not_a_plan_either() {
        Planner planner = planner("<think>The user wants me to read email. I should probably…</think>");

        assertThatThrownBy(() -> planner.plan(goal("Maillerime bak")))
                .isInstanceOf(Planner.PlanUnreadableException.class);
    }

    /**
     * The other half, and the reason this is not simply "fail when there are no steps".
     * A model that answers under the schema with an empty list has answered: there is
     * nothing to run. "dur" is a real goal with a real answer of that shape.
     */
    @Test
    void a_plan_that_is_honestly_empty_still_gets_its_one_step() {
        Planner planner = planner("{\"steps\": []}");

        List<Step> steps = planner.plan(goal("dur"));

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).title()).isEqualTo("Hedefi özetle");
    }

    @Test
    void a_real_plan_is_untouched() {
        Planner planner = planner(
                "{\"steps\":[{\"title\":\"Kayıtları ara\",\"toolName\":\"jira.searchIssues\","
                + "\"params\":{\"jql\":\"project = KAN\"}}]}");

        List<Step> steps = planner.plan(goal("KAN'daki kayıtları listele"));

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).toolName()).isEqualTo("jira.searchIssues");
    }
}
