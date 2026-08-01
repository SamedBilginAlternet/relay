package com.relay.api;

import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.RoutingLlmClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two health answers, because two different readers ask.
 *
 * <p>{@code GET /api/health} is the one Docker and Caddy call, so it stays open — and
 * therefore says as little as a liveness check can: {@code {status, version}}. It used to
 * answer with the provider, the model name, how many Groq keys exist, how many are still
 * usable, the last provider error, and the prefix and last four characters of every key.
 * Unauthenticated, to anybody who asked. None of that is a leaked key, but together it is
 * a fingerprint: it confirms that a key found somewhere else belongs to this deployment,
 * and it lets a stranger watch {@code degraded} for the minute the service is brittle.
 *
 * <p>{@code GET /api/health/details} is the operator's view and needs a session like every
 * other endpoint. The key fingerprints are gone from there too: what an operator needs is
 * how many keys there are and how many still work, never which key is which.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final String version;
    private final String toolsMode;

    public HealthController(LlmClient llm, ToolRegistry tools,
                            @Value("${app.version:0.1.0}") String version,
                            @Value("${app.tools.mode:replay}") String toolsMode) {
        this.llm = llm;
        this.tools = tools;
        this.version = version;
        this.toolsMode = toolsMode;
    }

    /** Open, and deliberately dull: enough for a container to decide the process is up. */
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", version);
        return body;
    }

    /** Signed in only — {@code AuthFilter} exempts {@code /api/health} exactly, not what is under it. */
    @GetMapping("/details")
    public Map<String, Object> details() {
        Map<String, Object> body = health();
        body.put("llm", llm instanceof RoutingLlmClient routing
                ? routing.health()
                : Map.of("provider", llm.name(), "degraded", llm.degraded()));
        Map<String, Object> toolInfo = new LinkedHashMap<>();
        toolInfo.put("mode", toolsMode);
        toolInfo.put("count", tools.all().size());
        body.put("tools", toolInfo);
        return body;
    }
}
