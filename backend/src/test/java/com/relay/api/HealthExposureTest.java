package com.relay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.auth.AuthService;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.auth.AuthFilter;
import com.relay.infrastructure.auth.BCryptPasswordHasher;
import com.relay.infrastructure.llm.ApiKeyPool;
import com.relay.infrastructure.llm.GroqLlmClient;
import com.relay.infrastructure.llm.HttpTransport;
import com.relay.infrastructure.llm.RoutingLlmClient;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.AuthDoubles;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The health endpoint is the one door left open on purpose, so what it says out loud is a
 * security decision rather than a formatting one.
 *
 * <p>Live, with no cookie at all, {@code GET /api/health} answered with the model name, the
 * number of Groq keys, how many were still usable, the last provider error and the prefix
 * and last four characters of all five keys. None of that is a key. Together it tells a
 * stranger how the deployment is provisioned, lets them watch {@code degraded} for the
 * minute it is brittle, and lets them match a key leaked somewhere else against this
 * install.
 */
class HealthExposureTest {

    private HealthController health() {
        ToolRegistry tools = new ToolRegistryImpl(List.of());
        ApiKeyPool pool = new ApiKeyPool(List.of("gsk_abcdefghijklmnop", "gsk_qrstuvwxyz012345"),
                Duration.ofSeconds(60), new TestDoubles.FixedClock());
        HttpTransport offline = (url, apiKey, jsonBody) -> new HttpTransport.Reply(500, "{}");
        RoutingLlmClient llm = new RoutingLlmClient(
                new GroqLlmClient(pool, offline, "https://groq.test/v1", "llama-3.3-70b-versatile", 0.59, 0.79),
                new StubLlmClient(tools));
        return new HealthController(llm, tools, "0.1.0", "live");
    }

    @Test
    void an_unauthenticated_health_check_reveals_no_key_material() throws Exception {
        Map<String, Object> open = health().health();

        assertThat(open).containsOnlyKeys("status", "version");
        assertThat(open.toString())
                .doesNotContain("keys", "keysTotal", "keysAvailable", "model", "lastError");
        // A container health check reads the status and nothing else; it still can.
        assertThat(open.get("status")).isEqualTo("ok");

        // And the door to the rest of it is shut.
        assertThat(statusWithoutASession("/api/health/details")).isEqualTo(401);
        assertThat(statusWithoutASession("/api/health")).isEqualTo(200);
    }

    /**
     * The operator view answers the question an operator has — how many keys, how many
     * working — without saying which key is which.
     */
    @Test
    void even_the_signed_in_view_stops_short_of_fingerprinting_a_key() {
        Map<String, Object> details = health().details();

        assertThat(details).containsKeys("llm", "tools");
        assertThat(details.toString()).doesNotContain("gsk_", "****", "mnop", "2345");
        assertThat(asMap(details.get("llm"))).containsEntry("keysTotal", 2)
                .containsEntry("keysAvailable", 2)
                .doesNotContainKey("keys");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static int statusWithoutASession(String path) throws Exception {
        AuthService auth = new AuthService(new AuthDoubles.InMemoryUsers(),
                new AuthDoubles.InMemorySessions(), new BCryptPasswordHasher(4),
                new TestDoubles.FixedClock());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthFilter(auth, true).doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
