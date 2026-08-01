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
    /**
     * Set only by failures another key cannot fix (a rejected request, a dead key).
     * Rate limiting is <em>not</em> recorded here: the pool already parks the key and
     * frees it when the cooldown ends, so that outage has to expire on its own.
     */
    private final AtomicBoolean hardFailure = new AtomicBoolean(false);
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
                if (hardFailure.compareAndSet(true, false)) {
                    LOG.log(Level.INFO, "groq recovered — leaving stub mode");
                }
                lastError.set(null);
                return response;
            } catch (RuntimeException e) {
                hardFailure.set(!exhausted(e));
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
        return degraded() ? fallback.name() + " (groq degraded)" : primary.name();
    }

    /**
     * Reflects what the next call would do, not what the last one did.
     *
     * <p>A burst of 429s used to latch this to {@code true} until some run happened to
     * succeed, so health stayed red long after the cooldown expired and the key worked
     * again. Now a freed key is enough to clear it.
     */
    @Override
    public boolean degraded() {
        if (primary == null || primary.keys().empty()) {
            return true;
        }
        return hardFailure.get() || primary.keys().available() == 0;
    }

    /** Was the failure "every key is cooling down" rather than something a retry cannot fix? */
    private static boolean exhausted(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.startsWith("all groq keys exhausted");
    }

    /**
     * What {@code GET /api/health/details} shows an operator. Never contains a key, and no
     * longer contains a fingerprint of one.
     *
     * <p>It used to list every key as {@code gsk_****tXVT}. The question an operator has is
     * "how many keys, how many still working" — {@code keysTotal} and {@code keysAvailable}
     * answer it. Which key is which only ever helped somebody matching a key found
     * elsewhere against this deployment.
     */
    public Map<String, Object> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean groqConfigured = primary != null && !primary.keys().empty();
        map.put("provider", groqConfigured && !degraded() ? "groq" : "stub");
        map.put("model", groqConfigured ? primary.model() : "stub");
        map.put("degraded", degraded());
        map.put("keysTotal", groqConfigured ? primary.keys().total() : 0);
        map.put("keysAvailable", groqConfigured ? primary.keys().available() : 0);
        map.put("lastError", lastError.get());
        // Groq counts tokens per organisation. Fewer organisations than keys means the
        // extra keys are spending one budget, which is the one thing rotation cannot fix.
        if (groqConfigured && !primary.organisations().isEmpty()) {
            map.put("organizations", primary.organisations());
        }
        return map;
    }
}
