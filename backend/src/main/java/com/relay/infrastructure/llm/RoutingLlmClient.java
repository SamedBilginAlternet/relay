package com.relay.infrastructure.llm;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The LlmClient the orchestrator actually holds: Groq first, stub when Groq cannot
 * answer. Liskov in practice — the caller cannot tell which one replied, but
 * {@code /api/health} can.
 */
public class RoutingLlmClient implements LlmClient {

    private static final Logger LOG = System.getLogger(RoutingLlmClient.class.getName());

    private final GroqLlmClient primary;
    private final StubLlmClient fallback;
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public RoutingLlmClient(GroqLlmClient primary, StubLlmClient fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (primary != null && !primary.keys().empty()) {
            try {
                LlmResponse response = primary.complete(request);
                if (degraded.compareAndSet(true, false)) {
                    LOG.log(Level.INFO, "groq recovered — leaving stub mode");
                }
                lastError.set(null);
                return response;
            } catch (RuntimeException e) {
                degraded.set(true);
                lastError.set(e.getMessage());
                LOG.log(Level.WARNING, "groq unavailable ({0}) — falling back to stub", e.getMessage());
            }
        }
        return fallback.complete(request);
    }

    @Override
    public String name() {
        if (primary == null || primary.keys().empty()) {
            return fallback.name();
        }
        return degraded.get() ? fallback.name() + " (groq degraded)" : primary.name();
    }

    @Override
    public boolean degraded() {
        return primary == null || primary.keys().empty() || degraded.get();
    }

    /** What {@code GET /api/health} shows. Never contains a key. */
    public Map<String, Object> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean groqConfigured = primary != null && !primary.keys().empty();
        map.put("provider", groqConfigured && !degraded.get() ? "groq" : "stub");
        map.put("model", groqConfigured ? primary.model() : "stub");
        map.put("degraded", degraded());
        map.put("keysTotal", groqConfigured ? primary.keys().total() : 0);
        map.put("keysAvailable", groqConfigured ? primary.keys().available() : 0);
        map.put("keys", groqConfigured ? primary.keys().masked() : java.util.List.of());
        map.put("lastError", lastError.get());
        return map;
    }
}
