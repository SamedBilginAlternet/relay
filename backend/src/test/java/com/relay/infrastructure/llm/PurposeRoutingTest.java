package com.relay.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Why these exist: the small model used to be reachable only through a rate limit. That is a
 * failure path, not a cost decision — a verifier's yes/no and a planner's plan went to the
 * same 70B model at the same price, and the cheap tier was only ever reached by first burning
 * the expensive one. Every call already declares what it is for, so the tier is now chosen by
 * the job.
 *
 * <p>Two things must not regress, and both are one edit away from doing so. The map has to be
 * right in the direction that costs money to get wrong — a purpose nobody has classified must
 * land on the strong model, never the cheap one. And the fallback has to survive the change:
 * routing a job to the small tier must not mean that job dies with the small tier.
 */
class PurposeRoutingTest {

    /** 100 prompt + 50 completion tokens, so both price lists produce a number by hand. */
    private static final String OK_BODY = """
            {"choices":[{"message":{"content":"{\\"pass\\":true}"}}],
             "usage":{"prompt_tokens":100,"completion_tokens":50}}
            """;

    private static final String RATE_LIMITED = """
            {"error":{"message":"Rate limit reached for model","type":"rate_limit_exceeded"}}
            """;

    private static final String STRONG = "llama-3.3-70b-versatile";
    private static final String SMALL = "llama-3.1-8b-instant";

    private static final double STRONG_INPUT = 0.59;
    private static final double STRONG_OUTPUT = 0.79;
    private static final double SMALL_INPUT = 0.05;
    private static final double SMALL_OUTPUT = 0.08;

    /** Records which model each request named, and answers per model. */
    private static class ModelSpy implements HttpTransport {
        final List<String> asked = new ArrayList<>();
        final List<String> answering;

        ModelSpy(String... answering) {
            this.answering = List.of(answering);
        }

        @Override
        public Reply post(String url, String apiKey, String jsonBody) {
            String model = jsonBody.contains(SMALL) ? SMALL : STRONG;
            asked.add(model);
            return answering.contains(model)
                    ? new Reply(200, OK_BODY)
                    : new Reply(429, RATE_LIMITED);
        }
    }

    private static GroqLlmClient client(HttpTransport transport, TestDoubles.FixedClock clock,
                                        List<String> strongKeys, List<String> smallKeys) {
        return new GroqLlmClient(
                new ApiKeyPool(strongKeys, Duration.ofSeconds(60), clock),
                transport, "https://groq.test/v1", STRONG, STRONG_INPUT, STRONG_OUTPUT,
                SMALL, new ApiKeyPool(smallKeys, Duration.ofSeconds(60), clock),
                "groq", SMALL_INPUT, SMALL_OUTPUT, LlmPurpose.DEFAULT_SMALL);
    }

    private static GroqLlmClient client(HttpTransport transport, TestDoubles.FixedClock clock) {
        return client(transport, clock, List.of("key-1"), List.of("key-1"));
    }

    private static LlmRequest forPurpose(String purpose) {
        return LlmRequest.of(purpose, "system", "user", null, Map.of());
    }

    @Test
    void a_verify_goes_to_the_small_model_and_a_plan_to_the_strong_one() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        ModelSpy transport = new ModelSpy(STRONG, SMALL);
        GroqLlmClient client = client(transport, clock);

        LlmResponse verify = client.complete(forPurpose(LlmPurpose.VERIFY));
        LlmResponse plan = client.complete(forPurpose(LlmPurpose.PLAN));

        assertThat(transport.asked).containsExactly(SMALL, STRONG);
        assertThat(verify.model()).isEqualTo("groq:" + SMALL);
        assertThat(plan.model()).isEqualTo("groq:" + STRONG);
    }

    /**
     * The rest of the map, in one pass. A step's parameters, a digest, a brief's
     * classification and an answer the user acts on all decide either what gets written where
     * or what the person is told is true; none of them is a cheaper mistake for having been
     * made cheaply.
     */
    @Test
    void every_job_that_writes_or_asserts_stays_on_the_strong_model() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        ModelSpy transport = new ModelSpy(STRONG, SMALL);
        GroqLlmClient client = client(transport, clock);

        for (String purpose : List.of(LlmPurpose.PLAN, LlmPurpose.TOOL_PARAMS, LlmPurpose.DIGEST,
                LlmPurpose.INSIGHT, LlmPurpose.ASK_ANSWER)) {
            client.complete(forPurpose(purpose));
        }
        for (String purpose : List.of(LlmPurpose.VERIFY, LlmPurpose.SUMMARIZE, LlmPurpose.ASK_ROUTE)) {
            client.complete(forPurpose(purpose));
        }

        assertThat(transport.asked).containsExactly(
                STRONG, STRONG, STRONG, STRONG, STRONG,
                SMALL, SMALL, SMALL);
    }

    /**
     * The safe direction. A purpose added to the orchestrator and forgotten here is not a
     * decision to answer it cheaply — nobody has decided anything about it yet.
     */
    @Test
    void a_purpose_nobody_classified_gets_the_strong_model() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        ModelSpy transport = new ModelSpy(STRONG, SMALL);
        GroqLlmClient client = client(transport, clock);

        client.complete(forPurpose("triage_incident"));
        client.complete(forPurpose(null));

        assertThat(transport.asked).containsExactly(STRONG, STRONG);
    }

    /**
     * The fallback the split must not have broken. A verify is worth a small model, but it is
     * not worth nothing: when the small pool is empty the strong one answers rather than the
     * stub, which would hand the verifier an offline guess about whether a step passed.
     */
    @Test
    void a_job_routed_small_still_answers_on_the_strong_model_when_the_small_pool_is_empty() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        ModelSpy transport = new ModelSpy(STRONG);
        GroqLlmClient client = client(transport, clock);

        LlmResponse response = client.complete(forPurpose(LlmPurpose.VERIFY));

        assertThat(transport.asked).as("small first, then up").containsExactly(SMALL, STRONG);
        assertThat(response.model()).isEqualTo("groq:" + STRONG);
        assertThat(response.fallback()).as("a real model answered, not the stub").isFalse();
    }

    /** And the direction that already worked: a strong job drops to the small tier. */
    @Test
    void a_job_routed_strong_still_answers_on_the_small_model_when_the_strong_pool_is_empty() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        ModelSpy transport = new ModelSpy(SMALL);
        GroqLlmClient client = client(transport, clock);

        LlmResponse response = client.complete(forPurpose(LlmPurpose.PLAN));

        assertThat(transport.asked).containsExactly(STRONG, SMALL);
        assertThat(response.model()).isEqualTo("groq:" + SMALL);
    }

    /** Both tiers gone still names both, so the health line says which budgets are spent. */
    @Test
    void both_tiers_exhausted_names_the_tier_the_job_belonged_on_first() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        GroqLlmClient client = client(new ModelSpy(), clock);

        assertThatThrownBy(() -> client.complete(forPurpose(LlmPurpose.VERIFY)))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining(SMALL)
                .hasMessageContaining(STRONG);
    }

    /**
     * The number the product's "all-premium it would have cost Y" line is built on. It is the
     * measured token counts against the strong model's price list and nothing else — no
     * projection of what a bigger model might have generated instead.
     */
    @Test
    void the_premium_figure_is_the_same_tokens_at_the_strong_models_price() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        GroqLlmClient client = client(new ModelSpy(STRONG, SMALL), clock);

        LlmResponse small = client.complete(forPurpose(LlmPurpose.VERIFY));

        double expectedActual = 100 / 1_000_000d * SMALL_INPUT + 50 / 1_000_000d * SMALL_OUTPUT;
        double expectedPremium = 100 / 1_000_000d * STRONG_INPUT + 50 / 1_000_000d * STRONG_OUTPUT;

        assertThat(small.promptTokens()).isEqualTo(100);
        assertThat(small.completionTokens()).isEqualTo(50);
        assertThat(small.costUsd()).isCloseTo(expectedActual, within(1e-12));
        assertThat(small.premiumCostUsd()).isCloseTo(expectedPremium, within(1e-12));
        assertThat(small.premiumCostUsd()).as("the cheap tier is cheaper, or the split buys nothing")
                .isGreaterThan(small.costUsd());
    }

    /** On the strong model the two figures are one number counted twice — nothing was saved. */
    @Test
    void a_call_on_the_strong_model_has_no_premium_to_compare_against() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        GroqLlmClient client = client(new ModelSpy(STRONG, SMALL), clock);

        LlmResponse plan = client.complete(forPurpose(LlmPurpose.PLAN));

        assertThat(plan.premiumCostUsd()).isEqualTo(plan.costUsd());
    }

    /**
     * A small-tier answer used to be billed at the big model's rate, because the price list
     * did not depend on which model replied. Roughly twelve times the real number, on the one
     * screen the product asks a person to trust.
     */
    @Test
    void the_small_model_is_billed_at_its_own_price_not_the_big_ones() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        GroqLlmClient client = client(new ModelSpy(STRONG, SMALL), clock);

        double small = client.complete(forPurpose(LlmPurpose.VERIFY)).costUsd();
        double strong = client.complete(forPurpose(LlmPurpose.PLAN)).costUsd();

        assertThat(small).isLessThan(strong / 5);
    }

    /** The split is a property, so a running deployment has to be able to say what it is doing. */
    @Test
    void the_routing_table_is_visible_on_the_health_endpoint() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        GroqLlmClient groq = client(new ModelSpy(STRONG, SMALL), clock);
        RoutingLlmClient router = new RoutingLlmClient(groq, new StubLlmClient(null));

        assertThat(router.health()).extracting("routing").isNotNull();
        assertThat(groq.smallPurposes())
                .containsExactly(LlmPurpose.ASK_ROUTE, LlmPurpose.SUMMARIZE, LlmPurpose.VERIFY);
    }

    /**
     * The escape hatch, and the reason the map is configuration rather than a constant: a
     * deployment that wants everything on the strong model gets it without a new build.
     */
    @Test
    void an_empty_map_sends_every_job_to_the_strong_model() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        ModelSpy transport = new ModelSpy(STRONG, SMALL);
        GroqLlmClient client = new GroqLlmClient(
                new ApiKeyPool(List.of("key-1"), Duration.ofSeconds(60), clock),
                transport, "https://groq.test/v1", STRONG, STRONG_INPUT, STRONG_OUTPUT,
                SMALL, new ApiKeyPool(List.of("key-1"), Duration.ofSeconds(60), clock),
                "groq", SMALL_INPUT, SMALL_OUTPUT, List.of());

        client.complete(forPurpose(LlmPurpose.VERIFY));

        assertThat(transport.asked).containsExactly(STRONG);
    }
}
