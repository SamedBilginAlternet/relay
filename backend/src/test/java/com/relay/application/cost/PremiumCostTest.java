package com.relay.application.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.relay.application.port.LlmResponse;
import com.relay.domain.Run;
import com.relay.domain.Step;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Why these exist: the product's claim is "this run cost X — all-premium it would have cost
 * Y", and the only version of that sentence worth saying is one a judge can recompute. So Y
 * is the run's own measured token counts against the strong model's price list, never a
 * projection, never a rounding of a guess.
 *
 * <p>Which makes the missing case the interesting one. A step that ran on the offline stub
 * has token counts that were estimated from character lengths and were never billed by
 * anyone. Adding those to a real premium total produces a number that looks exactly like the
 * others and is not derived from anything. The field says null instead — and once one call
 * on a step is unpriceable the whole step's figure is, because a total missing one of its
 * calls is not a total.
 */
class PremiumCostTest {

    private final CostMeter meter = new CostMeter();

    private static Run run() {
        return Run.create("blocker özeti", Instant.parse("2026-07-31T09:00:00Z"), null);
    }

    private static Step step(Run run) {
        Step step = Step.create(run.id(), 1, "ara", "jira-agent", "jira.searchIssues", Map.of());
        run.addStep(step);
        return step;
    }

    /**
     * 10 000 prompt + 5 000 completion at Groq's llama-3.1-8b-instant / llama-3.3-70b prices.
     *
     * <p>Both figures land exactly on the six-decimal grid money is kept at here, so what
     * these tests check is the arithmetic rather than {@link CostMeter}'s rounding — which
     * has its own test below.
     */
    private static LlmResponse smallAnswer() {
        double cost = 10_000 / 1_000_000d * 0.05 + 5_000 / 1_000_000d * 0.08;
        double premium = 10_000 / 1_000_000d * 0.59 + 5_000 / 1_000_000d * 0.79;
        return new LlmResponse("{}", 10_000, 5_000, cost, "groq:llama-3.1-8b-instant", false, premium);
    }

    private static LlmResponse strongAnswer(long prompt, long completion) {
        double cost = prompt / 1_000_000d * 0.59 + completion / 1_000_000d * 0.79;
        return new LlmResponse("{}", prompt, completion, cost, "groq:llama-3.3-70b-versatile", false, cost);
    }

    @Test
    void the_premium_figure_equals_the_steps_tokens_at_the_strong_models_price() {
        Run run = run();
        Step step = step(run);

        meter.record(run, step, smallAnswer());

        // 10 000 × 0.59/M + 5 000 × 0.79/M, by hand: 0.0059 + 0.00395.
        assertThat(step.tokens()).isEqualTo(15_000);
        assertThat(step.costUsd()).isCloseTo(0.0009, within(1e-9));
        assertThat(step.premiumCostUsd()).isCloseTo(0.00985, within(1e-9));
    }

    /** Several calls: the premium column is the sum of the same calls, priced the other way. */
    @Test
    void a_step_with_several_calls_sums_both_columns_over_the_same_calls() {
        Run run = run();
        Step step = step(run);

        meter.record(run, step, smallAnswer());
        meter.record(run, step, smallAnswer());

        assertThat(step.tokens()).isEqualTo(30_000);
        assertThat(step.costUsd()).isCloseTo(0.0018, within(1e-9));
        assertThat(step.premiumCostUsd()).isCloseTo(0.0197, within(1e-9));
    }

    /**
     * The premium column obeys exactly the rule the cost column does: six decimals, a tenth
     * of a cent of a cent. Rounding only one of the two would put the rounding error into the
     * difference between them, and that difference is the whole claim the number makes.
     */
    @Test
    void the_premium_figure_is_kept_at_the_same_six_decimals_the_cost_is() {
        Run run = run();
        Step step = step(run);

        // 100 + 50 tokens: 0.0000985 at the strong price, below the grid money is kept at.
        double premium = 100 / 1_000_000d * 0.59 + 50 / 1_000_000d * 0.79;
        meter.record(run, step, new LlmResponse("{}", 100, 50, 0.000009,
                "groq:llama-3.1-8b-instant", false, premium));

        assertThat(step.premiumCostUsd()).isEqualTo(0.000099);
        assertThat(CostMeter.usd(step.premiumCostUsd()).toPlainString()).isEqualTo("0.000099");
    }

    /** On the strong model there is nothing to compare: the same money in both columns. */
    @Test
    void a_step_that_never_left_the_strong_model_shows_the_same_number_twice() {
        Run run = run();
        Step step = step(run);

        meter.record(run, step, strongAnswer(1000, 500));

        assertThat(step.premiumCostUsd()).isEqualTo(step.costUsd());
    }

    /**
     * The attribution rule. A step derives its parameters, has them refused, derives them
     * again and is then verified — and since the tier follows the job, those calls do not
     * share a model. One name is kept: the one that did the most tokens, because that is the
     * call that shaped the step and the one its cost is mostly made of.
     */
    @Test
    void the_step_is_named_after_the_call_that_did_the_most_tokens() {
        Run run = run();
        Step step = step(run);

        meter.record(run, step, smallAnswer());                    // 15 000 tokens, small
        meter.record(run, step, strongAnswer(40_000, 9_000));      // 49 000 tokens, strong
        meter.record(run, step, smallAnswer());                    // 15 000 tokens, small again

        assertThat(step.model()).isEqualTo("groq:llama-3.3-70b-versatile");
        assertThat(step.modelTokens()).isEqualTo(49_000);
    }

    /** Nothing has answered yet, so there is nothing to name and nothing to compare. */
    @Test
    void a_step_that_made_no_model_call_carries_neither_number() {
        Run run = run();
        Step step = step(run);

        assertThat(step.model()).isNull();
        assertThat(step.premiumCostUsd()).isNull();
    }

    /**
     * The stub estimates tokens from character counts and no provider ever billed them.
     * Pricing that at the strong model's rate would put an invented figure in the same column
     * as measured ones, which is the one thing this number cannot afford.
     */
    @Test
    void a_call_that_cannot_be_priced_leaves_the_premium_figure_null() {
        Run run = run();
        Step step = step(run);

        meter.record(run, step, new LlmResponse("özet", 80, 40, 0.0, "stub", true));

        assertThat(step.tokens()).isEqualTo(120);
        assertThat(step.premiumCostUsd()).as("null is not zero").isNull();
        assertThat(step.model()).isEqualTo("stub");
    }

    /** And one unpriceable call poisons the step's total, whichever order it arrives in. */
    @Test
    void one_unpriceable_call_makes_the_whole_steps_premium_unknown() {
        Run run = run();
        Step first = step(run);
        Step second = Step.create(run.id(), 2, "gönder", "slack-agent", "slack.postMessage", Map.of());
        run.addStep(second);

        meter.record(run, first, smallAnswer());
        meter.record(run, first, new LlmResponse("özet", 80, 40, 0.0, "stub", true));

        meter.record(run, second, new LlmResponse("özet", 80, 40, 0.0, "stub", true));
        meter.record(run, second, smallAnswer());

        assertThat(first.premiumCostUsd()).as("priced first, then not").isNull();
        assertThat(second.premiumCostUsd()).as("unpriced first, then priced").isNull();
        // The real cost is unaffected — that one is known for every call.
        assertThat(first.costUsd()).isCloseTo(0.0009, within(1e-9));
    }

    /**
     * The old two-number call site cannot say which model answered or what the strong one
     * would have charged, so it must not pretend to. Silently treating "unknown" as "the same
     * as actual" would report a saving of exactly zero for every legacy call — a number that
     * reads as measured and is not.
     */
    @Test
    void recording_bare_tokens_and_dollars_reports_the_premium_as_unknown() {
        Run run = run();
        Step step = step(run);

        meter.record(run, step, 1200, 0.0009);

        assertThat(step.costUsd()).isCloseTo(0.0009, within(1e-9));
        assertThat(step.premiumCostUsd()).isNull();
        assertThat(step.model()).isNull();
    }

    /**
     * A step is written at the approval gate and read back when the approval arrives, so the
     * high-water mark has to survive the round trip. Without it the call that happens to come
     * after the reload takes the name regardless of how small it was.
     */
    @Test
    void the_winning_model_survives_being_read_back_from_the_database() {
        Run run = run();
        Step step = step(run);
        meter.record(run, step, strongAnswer(40_000, 9_000));

        // What JpaRunRepository does on the way back in.
        Step reloaded = step(run());
        reloaded.tokens(step.tokens());
        reloaded.costUsd(step.costUsd());
        reloaded.model(step.model(), step.modelTokens());
        reloaded.premiumCostUsd(step.premiumCostUsd());

        meter.record(run, reloaded, smallAnswer());

        assertThat(reloaded.model()).as("15 000 tokens do not unseat 49 000")
                .isEqualTo("groq:llama-3.3-70b-versatile");
    }

    /** A run reloaded with no premium but with tokens on it stays unknown, not partial. */
    @Test
    void a_step_read_back_without_a_premium_does_not_start_a_fresh_total() {
        Run run = run();
        Step reloaded = step(run);
        reloaded.tokens(120);
        reloaded.costUsd(0.0);
        reloaded.model("stub", 120);
        reloaded.premiumCostUsd(null);

        meter.record(run, reloaded, smallAnswer());

        assertThat(reloaded.premiumCostUsd()).isNull();
    }
}
