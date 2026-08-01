package com.relay.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@code /api/health} is what we look at before going on stage, so "degraded" has to mean
 * "the next call will fall back", not "some call failed once". These tests pin the
 * difference between an outage that expires (rate limiting) and one that does not.
 */
class RoutingRecoveryTest {

    private static final String OK_BODY = """
            {"choices":[{"message":{"content":"{}"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}
            """;

    private static LlmRequest request() {
        return LlmRequest.of(LlmPurpose.VERIFY, "system", "user", null, Map.of());
    }

    private static RoutingLlmClient routing(HttpTransport transport, TestDoubles.FixedClock clock) {
        ApiKeyPool pool = new ApiKeyPool(List.of("key-1"), Duration.ofSeconds(60), clock);
        GroqLlmClient groq = new GroqLlmClient(pool, transport, "https://groq.test/v1",
                "llama-3.3-70b-versatile", 0.59, 0.79);
        ToolRegistry registry = new ToolRegistryImpl(List.of());
        return new RoutingLlmClient(groq, new StubLlmClient(registry));
    }

    @Test
    void rate_limiting_stops_counting_as_degraded_once_the_cooldown_expires() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient client = routing((url, key, body) -> new HttpTransport.Reply(429, "{}"), clock);

        client.complete(request());
        assertThat(client.degraded()).as("key is parked, next call would fall back").isTrue();

        clock.advance(Duration.ofSeconds(61));
        assertThat(client.degraded()).as("cooldown expired — the key is usable again").isFalse();
        assertThat(client.health().get("provider")).isEqualTo("groq");
    }

    @Test
    void a_rejected_request_keeps_reporting_degraded_until_a_call_succeeds() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient client = routing((url, key, body) -> new HttpTransport.Reply(401, "{}"), clock);

        client.complete(request());
        clock.advance(Duration.ofMinutes(10));

        assertThat(client.degraded()).as("a dead key does not heal by waiting").isTrue();
        assertThat(client.health().get("provider")).isEqualTo("stub");
    }

    /** A refused key is retired, so recovery is tested with an outage that can end: a 5xx. */
    @Test
    void a_successful_call_clears_the_failure() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        boolean[] broken = {true};
        RoutingLlmClient client = routing(
                (url, key, body) -> broken[0]
                        ? new HttpTransport.Reply(503, "{}")
                        : new HttpTransport.Reply(200, OK_BODY),
                clock);

        client.complete(request());
        assertThat(client.degraded()).isTrue();
        assertThat(client.health().get("lastError")).isNotNull();

        broken[0] = false;
        clock.advance(Duration.ofSeconds(61));
        client.complete(request());

        assertThat(client.degraded()).isFalse();
        assertThat(client.health().get("lastError")).isNull();
    }

    /** A key the provider refused stays out: waiting must not resurrect it. */
    @Test
    void a_refused_key_is_retired_rather_than_parked() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        int[] calls = {0};
        RoutingLlmClient client = routing((url, key, body) -> {
            calls[0]++;
            return new HttpTransport.Reply(401, "{}");
        }, clock);

        client.complete(request());
        clock.advance(Duration.ofHours(1));
        client.complete(request());

        assertThat(calls[0]).as("the dead key is never tried again").isEqualTo(1);
        assertThat(client.health().get("keysAvailable")).isEqualTo(0);
    }

    /** Groq usually says how long to wait; honouring it shortens the fallback window. */
    @Test
    void a_retry_after_shorter_than_the_cooldown_is_honoured() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient client = routing(
                (url, key, body) -> new HttpTransport.Reply(429, "{}", Duration.ofSeconds(7)), clock);

        client.complete(request());
        assertThat(client.degraded()).isTrue();

        clock.advance(Duration.ofSeconds(8));
        assertThat(client.degraded()).as("parked for seven seconds, not a minute").isFalse();
    }

    /**
     * A wait the provider asked for is served, past the 60s cooldown.
     *
     * <p>This test used to assert the opposite — anything longer than the cooldown was cut
     * to a minute — and that clamp turned "come back in 38 minutes" into thirty-eight
     * refusals, each one dragging every other key through the same 429. Groq counts tokens
     * per organisation, so once keys from a second organisation are in the pool, the spent
     * one has to stay parked for what it said or the traffic never reaches the healthy one.
     */
    @Test
    void a_retry_after_longer_than_the_cooldown_is_served_not_cut_to_a_minute() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient client = routing(
                (url, key, body) -> new HttpTransport.Reply(429, "{}", Duration.ofMinutes(38)), clock);

        client.complete(request());

        clock.advance(Duration.ofSeconds(61));
        assertThat(client.degraded()).as("a minute in, the key is still spent").isTrue();
        clock.advance(Duration.ofMinutes(38));
        assertThat(client.degraded()).isFalse();
    }

    /** But an absurd one must not sideline the only key for a working day. */
    @Test
    void a_retry_after_beyond_an_hour_is_capped() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient client = routing(
                (url, key, body) -> new HttpTransport.Reply(429, "{}", Duration.ofHours(3)), clock);

        client.complete(request());
        clock.advance(ApiKeyPool.MAX_PARK.plusMinutes(1));

        assertThat(client.degraded()).isFalse();
    }
}
