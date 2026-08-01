package com.relay.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Live, on 2026-08-01, all three configured tiers failed inside one request: the primary
 * cooling from a 429, the paid tier behind it timing out at its own 30s ceiling, the third
 * rate-limited with a 38-minute wait. None of that was a bug in any single tier — each did
 * exactly what {@link GroqKeyRotationTest} and {@link ThirdTierTest} pin it to do — but three
 * sequential worst cases summed to minutes on a screen with no way to say it was still
 * trying. These tests hold the fix: a deadline shared across the whole chain, checked between
 * every key and every tier, so a bad hour resolves in one bounded wait instead of the sum of
 * all of them.
 */
class RoutingBudgetTest {

    private static LlmRequest request() {
        return LlmRequest.of(LlmPurpose.PLAN, "system", "user", null, Map.of());
    }

    /**
     * The same {@code clock} has to reach both the pool and the client, or the deadline one
     * hands out is measured against time the other has never heard of — an all-real
     * {@code Clock.system()} default would compare a fixed-in-the-past test deadline against
     * the actual wall clock and look permanently expired. Production wiring (LlmConfig) shares
     * one {@code Clock} bean the same way; this just makes that explicit here too.
     */
    private static GroqLlmClient groq(List<String> keys, HttpTransport transport, String baseUrl,
                                      String model, String provider, TestDoubles.FixedClock clock) {
        ApiKeyPool pool = new ApiKeyPool(keys, Duration.ofSeconds(60), clock);
        return new GroqLlmClient(pool, transport, baseUrl, model, 0.59, 0.79, null, null, provider,
                0.59, 0.79, Set.of(), clock);
    }

    private static final String OK_BODY = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";

    /** Fails and, as a side effect, advances the clock — standing in for a call that hangs. */
    private static class SlowFailingTransport implements HttpTransport {
        final TestDoubles.FixedClock clock;
        final Duration perCall;
        int calls;

        SlowFailingTransport(TestDoubles.FixedClock clock, Duration perCall) {
            this.clock = clock;
            this.perCall = perCall;
        }

        @Override
        public Reply post(String url, String apiKey, String jsonBody) {
            calls++;
            clock.advance(perCall);
            return new Reply(599, "transport error: timeout");
        }
    }

    @Test
    void a_key_that_eats_the_whole_budget_stops_the_pool_from_trying_the_rest() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        // Each call "takes" 25s — longer than the 20s default budget on its own.
        SlowFailingTransport transport = new SlowFailingTransport(clock, Duration.ofSeconds(25));
        GroqLlmClient groqClient = groq(List.of("key-1", "key-2", "key-3"), transport,
                "https://groq.test/v1", "llama-3.3-70b-versatile", "groq", clock);
        RoutingLlmClient router = new RoutingLlmClient(List.of(groqClient), new StubLlmClient(null), clock,
                Duration.ofSeconds(20));

        var response = router.complete(request());

        assertThat(response.fallback()).as("falls to the stub rather than hanging").isTrue();
        assertThat(transport.calls).as("the second and third keys are never tried once the "
                + "budget is already spent").isEqualTo(1);
    }

    @Test
    void a_tier_behind_an_already_spent_budget_is_never_asked() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        SlowFailingTransport firstTransport = new SlowFailingTransport(clock, Duration.ofSeconds(25));
        int[] secondCalls = {0};
        HttpTransport second = (url, key, body) -> {
            secondCalls[0]++;
            return new HttpTransport.Reply(200, OK_BODY);
        };
        GroqLlmClient primary = groq(List.of("key-1"), firstTransport, "https://groq.test/v1",
                "llama-3.3-70b-versatile", "groq", clock);
        GroqLlmClient secondary = groq(List.of("key-2"), second, "https://deepseek.test",
                "deepseek-v4-flash", "deepseek", clock);
        RoutingLlmClient router = new RoutingLlmClient(List.of(primary, secondary), new StubLlmClient(null),
                clock, Duration.ofSeconds(20));

        var response = router.complete(request());

        assertThat(response.fallback()).isTrue();
        assertThat(firstTransport.calls).isEqualTo(1);
        assertThat(secondCalls[0]).as("the whole budget was spent on the tier before it — "
                + "one more timeout would not be trying harder").isZero();
    }

    @Test
    void plenty_of_budget_left_answers_normally() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        HttpTransport transport = (url, key, body) -> new HttpTransport.Reply(200, OK_BODY);
        GroqLlmClient groqClient = groq(List.of("key-1"), transport, "https://groq.test/v1",
                "llama-3.3-70b-versatile", "groq", clock);
        RoutingLlmClient router = new RoutingLlmClient(List.of(groqClient), new StubLlmClient(null), clock,
                Duration.ofSeconds(20));

        var response = router.complete(request());

        assertThat(response.fallback()).isFalse();
    }

    @Test
    void a_deadline_already_passed_before_the_call_starts_makes_no_network_attempt_at_all() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        int[] calls = {0};
        HttpTransport transport = (url, key, body) -> {
            calls[0]++;
            return new HttpTransport.Reply(200, OK_BODY);
        };
        GroqLlmClient groqClient = groq(List.of("key-1"), transport, "https://groq.test/v1",
                "llama-3.3-70b-versatile", "groq", clock);

        Instant pastDeadline = clock.now().minusSeconds(1);
        var thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> groqClient.complete(request(), pastDeadline));

        assertThat(thrown).isInstanceOf(LlmUnavailableException.class);
        assertThat(calls[0]).as("a deadline already in the past skips the network entirely").isZero();
    }
}
