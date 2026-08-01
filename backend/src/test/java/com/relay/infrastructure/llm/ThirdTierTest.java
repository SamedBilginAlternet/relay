package com.relay.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Two provider slots was one too few, and not hypothetically. On 2026-08-01 all seven
 * Groq keys hit their daily token wall and the paid provider behind them answered
 * {@code HTTP 599} within the same hour. Both configured tiers down at once leaves the
 * stub, which writes no digest and no summary — a demo running on counted numbers alone.
 *
 * <p>Three tiers is a bet on three companies not having a bad hour together, which is a
 * materially different bet from two. These tests hold the three things that make it worth
 * having: the chain is walked in order, a tier nobody configured is not in the chain at
 * all, and every tier that failed reaches the operator rather than only the first one —
 * because the first message names the wrong console.
 */
class ThirdTierTest {

    /** Fails or answers on command, and counts how often it was asked. */
    private static class Tier extends GroqLlmClient {
        private final String label;
        private final boolean broken;
        private int calls;

        Tier(String label, boolean broken, TestDoubles.FixedClock clock) {
            super(new ApiKeyPool(List.of("k-" + label), Duration.ofSeconds(60), clock),
                    (url, apiKey, jsonBody) -> {
                        throw new IllegalStateException("this tier never reaches the network");
                    },
                    "https://" + label + ".test", label + "-model", 1.0, 1.0, null, null, label);
            this.label = label;
            this.broken = broken;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            return complete(request, java.time.Instant.MAX);
        }

        // RoutingLlmClient calls the deadline-aware overload now, so that is the seam this
        // double has to intercept — overriding only the one above would fall through to the
        // real GroqLlmClient logic and hit the transport that throws to prove it never should.
        @Override
        public LlmResponse complete(LlmRequest request, java.time.Instant deadline) {
            calls++;
            if (broken) {
                throw new LlmUnavailableException("all " + label + " keys exhausted (test)");
            }
            return new LlmResponse("{\"ok\":true}", 10, 5, 0.000_01, label + ":" + label + "-model",
                    false);
        }
    }

    private static LlmRequest ask() {
        return LlmRequest.of(LlmPurpose.PLAN, "sys", "user", null, Map.of());
    }

    @Test
    void the_third_tier_answers_when_the_two_in_front_of_it_cannot() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        Tier first = new Tier("groq", true, clock);
        Tier second = new Tier("deepseek", true, clock);
        Tier third = new Tier("gemini", false, clock);
        RoutingLlmClient router =
                new RoutingLlmClient(List.of(first, second, third), new StubLlmClient(null));

        LlmResponse response = router.complete(ask());

        assertThat(response.model()).startsWith("gemini");
        // In order, and each one only once: a chain that retried would multiply an outage
        // by the number of tiers behind it.
        assertThat(first.calls).isEqualTo(1);
        assertThat(second.calls).isEqualTo(1);
        assertThat(third.calls).isEqualTo(1);
    }

    @Test
    void a_tier_behind_a_working_one_is_never_asked() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        Tier first = new Tier("groq", false, clock);
        Tier third = new Tier("gemini", false, clock);
        RoutingLlmClient router =
                new RoutingLlmClient(List.of(first, third), new StubLlmClient(null));

        router.complete(ask());

        assertThat(first.calls).isEqualTo(1);
        assertThat(third.calls).isZero();
    }

    /**
     * An unconfigured tier arrives as null, and `List.of` would throw on it — turning
     * "no third provider" into a failure to start.
     */
    @Test
    void an_unconfigured_tier_is_dropped_rather_than_added_as_a_client_that_cannot_answer() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        Tier first = new Tier("groq", false, clock);
        RoutingLlmClient router =
                new RoutingLlmClient(Arrays.asList(first, null, null), new StubLlmClient(null));

        assertThat(router.complete(ask()).model()).startsWith("groq");
        assertThat(router.health()).doesNotContainKey("fallback");
    }

    /**
     * The operator reads one field. If it only ever carried the first tier's message they
     * would go and top up Groq while the outage that actually reached the stub was
     * somewhere else entirely.
     */
    @Test
    void every_tier_that_failed_is_on_the_record_not_just_the_first() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient router = new RoutingLlmClient(
                List.of(new Tier("groq", true, clock), new Tier("deepseek", true, clock),
                        new Tier("gemini", true, clock)),
                new StubLlmClient(null));

        router.complete(ask());

        String reported = String.valueOf(router.health().get("lastError"));
        assertThat(reported).contains("groq").contains("deepseek").contains("gemini");
    }

    @Test
    void health_lists_the_whole_chain_in_the_order_it_is_tried() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient router = new RoutingLlmClient(
                List.of(new Tier("groq", false, clock), new Tier("deepseek", false, clock),
                        new Tier("gemini", false, clock)),
                new StubLlmClient(null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chain = (List<Map<String, Object>>) router.health().get("tiers");

        assertThat(chain).hasSize(3);
        assertThat(chain.stream().map(t -> t.get("provider")))
                .containsExactly("groq", "deepseek", "gemini");
        // And the old name still points at tier 1, because every screen already reads it.
        assertThat(router.health()).containsKey("fallback");
    }
}
