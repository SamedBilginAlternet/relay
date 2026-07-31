package com.relay.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GroqKeyRotationTest {

    private static final String OK_BODY = """
            {"choices":[{"message":{"content":"{\\"pass\\":true}"}}],
             "usage":{"prompt_tokens":100,"completion_tokens":50}}
            """;

    private static final String RATE_LIMITED = """
            {"error":{"message":"Rate limit reached for model","type":"rate_limit_exceeded"}}
            """;

    /** Transport that answers per key and records the order keys were tried in. */
    private static class FakeTransport implements HttpTransport {
        final List<String> calls = new ArrayList<>();
        final Function<String, Reply> behaviour;

        FakeTransport(Function<String, Reply> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Reply post(String url, String apiKey, String jsonBody) {
            calls.add(apiKey);
            return behaviour.apply(apiKey);
        }
    }

    private LlmRequest request() {
        return LlmRequest.of(LlmPurpose.VERIFY, "system", "user", null, Map.of());
    }

    private GroqLlmClient client(List<String> keys, FakeTransport transport, TestDoubles.FixedClock clock) {
        ApiKeyPool pool = new ApiKeyPool(keys, Duration.ofSeconds(60), clock);
        return new GroqLlmClient(pool, transport, "https://groq.test/v1", "llama-3.3-70b-versatile", 0.59, 0.79);
    }

    @Test
    void rotatesToTheNextKeyOn429() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        FakeTransport transport = new FakeTransport(key ->
                key.equals("key-1") ? new HttpTransport.Reply(429, RATE_LIMITED)
                        : new HttpTransport.Reply(200, OK_BODY));
        GroqLlmClient client = client(List.of("key-1", "key-2"), transport, clock);

        LlmResponse response = client.complete(request());

        assertThat(transport.calls).containsExactly("key-1", "key-2");
        assertThat(response.promptTokens()).isEqualTo(100);
        assertThat(response.completionTokens()).isEqualTo(50);
        assertThat(response.costUsd()).isGreaterThan(0);
    }

    @Test
    void aBurnedKeyStaysInCooldownForTheNextCall() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        FakeTransport transport = new FakeTransport(key ->
                key.equals("key-1") ? new HttpTransport.Reply(429, RATE_LIMITED)
                        : new HttpTransport.Reply(200, OK_BODY));
        GroqLlmClient client = client(List.of("key-1", "key-2"), transport, clock);

        client.complete(request());
        transport.calls.clear();
        client.complete(request());

        // key-1 is cooling down, so the second call goes straight to key-2.
        assertThat(transport.calls).containsExactly("key-2");
    }

    @Test
    void aCooledDownKeyComesBackAfterTheWindow() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        List<String> failing = new ArrayList<>(List.of("key-1"));
        FakeTransport transport = new FakeTransport(key ->
                failing.contains(key) ? new HttpTransport.Reply(429, RATE_LIMITED)
                        : new HttpTransport.Reply(200, OK_BODY));
        ApiKeyPool pool = new ApiKeyPool(List.of("key-1", "key-2"), Duration.ofSeconds(60), clock);
        GroqLlmClient client = new GroqLlmClient(pool, transport, "https://groq.test/v1", "m", 0.5, 0.5);

        client.complete(request());
        assertThat(pool.available()).isEqualTo(1);

        failing.clear();
        clock.advance(Duration.ofSeconds(61));
        assertThat(pool.available()).isEqualTo(2);

        transport.calls.clear();
        client.complete(request());
        assertThat(transport.calls).containsExactly("key-1");
    }

    @Test
    void everyKeyExhaustedThrows() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        FakeTransport transport = new FakeTransport(key -> new HttpTransport.Reply(429, RATE_LIMITED));
        GroqLlmClient client = client(List.of("key-1", "key-2", "key-3"), transport, clock);

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("exhausted");
        assertThat(transport.calls).containsExactly("key-1", "key-2", "key-3");
        assertThat(client.degraded()).isTrue();
    }

    @Test
    void routerFallsBackToTheStubWhenGroqIsGone() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        FakeTransport transport = new FakeTransport(key -> new HttpTransport.Reply(429, RATE_LIMITED));
        GroqLlmClient groq = client(List.of("key-1"), transport, clock);
        RoutingLlmClient router = new RoutingLlmClient(groq, new StubLlmClient(null));

        LlmResponse response = router.complete(
                LlmRequest.of(LlmPurpose.PLAN, "system", "Jira'da blocker'ları bul", null,
                        Map.of("goal", "Jira'da blocker'ları bul ve Slack'ten ekibe özet at")));

        assertThat(response.fallback()).isTrue();
        assertThat(response.content()).contains("jira.searchIssues");
        assertThat(router.degraded()).isTrue();
        assertThat(router.health().get("provider")).isEqualTo("stub");
        assertThat(router.health().toString()).doesNotContain("key-1");
    }

    @Test
    void withNoKeysConfiguredTheStubIsUsedDirectly() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        FakeTransport transport = new FakeTransport(key -> new HttpTransport.Reply(200, OK_BODY));
        GroqLlmClient groq = client(List.of(), transport, clock);
        RoutingLlmClient router = new RoutingLlmClient(groq, new StubLlmClient(null));

        router.complete(LlmRequest.of(LlmPurpose.SUMMARIZE, "s", "u", null, Map.of("goal", "özet")));

        assertThat(transport.calls).isEmpty();
        assertThat(router.name()).isEqualTo("stub");
        assertThat(router.degraded()).isTrue();
    }

    @Test
    void keysAreMaskedNotPrinted() {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        ApiKeyPool pool = new ApiKeyPool(List.of("gsk_live_abcdefghijklmnop"), Duration.ofSeconds(60), clock);
        assertThat(pool.masked()).containsExactly("gsk_****mnop");
    }
}
