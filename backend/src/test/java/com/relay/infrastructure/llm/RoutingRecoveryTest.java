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

    /** An absurd Retry-After must not sideline the only key for hours. */
    @Test
    void a_retry_after_longer_than_the_cooldown_is_clamped() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        RoutingLlmClient client = routing(
                (url, key, body) -> new HttpTransport.Reply(429, "{}", Duration.ofHours(3)), clock);

        client.complete(request());
        clock.advance(Duration.ofSeconds(61));

        assertThat(client.degraded()).isFalse();
    }
}
