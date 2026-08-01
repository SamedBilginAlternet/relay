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
 * The LlmClient the orchestrator actually holds: the fast provider first, a paid one behind
 * it, the stub when neither can answer. Liskov in practice — the caller cannot tell which
 * one replied, but {@code /api/health} can.
 *
 * <p>The second tier exists because the first one's budget is free and therefore finite. A
 * day of testing spent five Groq organisations' daily tokens in an afternoon and the product
 * fell to the stub, which writes no digest and no summary — the demo would have run on
 * counted numbers alone. A provider that bills per token has no daily wall to hit, so it
 * takes over exactly when the free one runs out and costs nothing while the free one works.
 */
public class RoutingLlmClient implements LlmClient {

    private static final Logger LOG = System.getLogger(RoutingLlmClient.class.getName());

    private final GroqLlmClient primary;
    /** Paid, unmetered, and idle until the free tier runs out. Null when not configured. */
    private final GroqLlmClient secondary;
    private final StubLlmClient fallback;
    /**
     * Set only by failures another key cannot fix (a rejected request, a dead key).
     * Rate limiting is <em>not</em> recorded here: the pool already parks the key and
     * frees it when the cooldown ends, so that outage has to expire on its own.
     */
    private final AtomicBoolean hardFailure = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public RoutingLlmClient(GroqLlmClient primary, StubLlmClient fallback) {
        this(primary, null, fallback);
    }

    public RoutingLlmClient(GroqLlmClient primary, GroqLlmClient secondary, StubLlmClient fallback) {
        this.primary = primary;
        this.secondary = secondary;
        this.fallback = fallback;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (usable(primary)) {
            try {
                LlmResponse response = primary.complete(request);
                if (hardFailure.compareAndSet(true, false)) {
                    LOG.log(Level.INFO, "{0} recovered — leaving fallback mode", primary.provider());
                }
                lastError.set(null);
                return response;
            } catch (RuntimeException e) {
                hardFailure.set(!exhausted(e));
                lastError.set(e.getMessage());
                LOG.log(Level.WARNING, "{0} unavailable ({1})", primary.provider(), e.getMessage());
            }
        }
        if (usable(secondary)) {
            try {
                LlmResponse response = secondary.complete(request);
                LOG.log(Level.INFO, "answered on {0} — {1} is out",
                        secondary.provider(), primary == null ? "the primary" : primary.provider());
                return response;
            } catch (RuntimeException e) {
                // Both providers down is worth its own line: the first message would
                // otherwise be the only one on the record and it names the wrong console.
                lastError.set(lastError.get() == null ? e.getMessage()
                        : lastError.get() + "; " + e.getMessage());
                LOG.log(Level.WARNING, "{0} unavailable too ({1}) — falling back to stub",
                        secondary.provider(), e.getMessage());
            }
        }
        return fallback.complete(request);
    }

    private static boolean usable(GroqLlmClient client) {
        return client != null && !client.keys().empty();
    }

    @Override
    public String name() {
        if (!usable(primary) && !usable(secondary)) {
            return fallback.name();
        }
        if (!degraded()) {
            return answering().name();
        }
        return fallback.name() + " (" + (usable(primary) ? primary.provider() : "llm") + " degraded)";
    }

    /** The tier the next call would reach — what {@code provider} and {@code model} describe. */
    private GroqLlmClient answering() {
        if (usable(primary) && !hardFailure.get() && primary.keys().available() > 0) {
            return primary;
        }
        return usable(secondary) ? secondary : primary;
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
        if (usable(secondary) && secondary.keys().available() > 0) {
            // The paid tier can answer, so the product is not degraded — it is just not free
            // this minute. Reporting red here would send someone looking for an outage that
            // the second provider has already absorbed.
            return false;
        }
        if (!usable(primary)) {
            return true;
        }
        return hardFailure.get() || primary.keys().available() == 0;
    }

    /** Was the failure "every key is cooling down" rather than something a retry cannot fix? */
    private static boolean exhausted(RuntimeException e) {
        String message = e.getMessage();
        // Not `startsWith("all groq")`: the provider names itself now, and a DeepSeek
        // exhaustion read as a hard failure would latch the router into stub mode.
        return message != null && message.matches("all \\S+ keys exhausted.*");
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
        boolean configured = usable(primary) || usable(secondary);
        GroqLlmClient answering = configured ? answering() : null;
        map.put("provider", configured && !degraded() ? answering.provider() : "stub");
        map.put("model", configured ? answering.model() : "stub");
        map.put("degraded", degraded());
        // These stay about the primary, which is what they have always meant and what the
        // screens read. The second tier gets its own block rather than being averaged in.
        map.put("keysTotal", usable(primary) ? primary.keys().total() : 0);
        map.put("keysAvailable", usable(primary) ? primary.keys().available() : 0);
        map.put("lastError", lastError.get());
        // Groq counts tokens per organisation. Fewer organisations than keys means the
        // extra keys are spending one budget, which is the one thing rotation cannot fix.
        if (usable(primary) && !primary.organisations().isEmpty()) {
            map.put("organizations", primary.organisations());
        }
        if (usable(secondary)) {
            Map<String, Object> paid = new LinkedHashMap<>();
            paid.put("provider", secondary.provider());
            paid.put("model", secondary.model());
            paid.put("keysTotal", secondary.keys().total());
            paid.put("keysAvailable", secondary.keys().available());
            map.put("fallback", paid);
        }
        return map;
    }
}
