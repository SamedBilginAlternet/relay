package com.relay.infrastructure.llm;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p>WHY THE TIERS ARE A LIST NOW. Two named slots was one slot too few, and it was not a
 * hypothetical: on 2026-08-01 all seven Groq keys hit their daily wall and the paid
 * provider answered {@code HTTP 599} in the same hour. Both slots down at once leaves the
 * stub, which writes no digest and no summary. Three providers on three different
 * companies' outages is a different bet from two, and the change is a list rather than a
 * third field so a fourth costs nothing.
 *
 * <p>The order is the preference order and nothing else infers it: tier 0 is the one the
 * product wants to use, and each next one is tried only when everything before it cannot
 * answer. Health keeps calling tier 1 {@code fallback} because that is the name every
 * screen and every operator already reads.
 */
public class RoutingLlmClient implements LlmClient {

    private static final Logger LOG = System.getLogger(RoutingLlmClient.class.getName());

    /** In preference order, nulls dropped. Never empty of meaning: it may be empty of tiers. */
    private final List<GroqLlmClient> tiers;
    private final StubLlmClient fallback;
    /**
     * Set only by failures another key cannot fix (a rejected request, a dead key).
     * Rate limiting is <em>not</em> recorded here: the pool already parks the key and
     * frees it when the cooldown ends, so that outage has to expire on its own.
     */
    private final AtomicBoolean hardFailure = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public RoutingLlmClient(GroqLlmClient primary, StubLlmClient fallback) {
        this(Arrays.asList(primary), fallback);
    }

    public RoutingLlmClient(GroqLlmClient primary, GroqLlmClient secondary, StubLlmClient fallback) {
        this(Arrays.asList(primary, secondary), fallback);
    }

    /** @param tiers in preference order; nulls are the "not configured" case and are dropped */
    public RoutingLlmClient(List<GroqLlmClient> tiers, StubLlmClient fallback) {
        List<GroqLlmClient> kept = new ArrayList<>();
        for (GroqLlmClient tier : tiers) {
            if (tier != null) {
                kept.add(tier);
            }
        }
        this.tiers = List.copyOf(kept);
        this.fallback = fallback;
    }

    private GroqLlmClient tier(int index) {
        return index < tiers.size() ? tiers.get(index) : null;
    }

    private GroqLlmClient primary() {
        return tier(0);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        for (int i = 0; i < tiers.size(); i++) {
            GroqLlmClient tier = tiers.get(i);
            if (!usable(tier)) {
                continue;
            }
            try {
                LlmResponse response = tier.complete(request);
                if (i == 0) {
                    if (hardFailure.compareAndSet(true, false)) {
                        LOG.log(Level.INFO, "{0} recovered — leaving fallback mode", tier.provider());
                    }
                    lastError.set(null);
                } else {
                    LOG.log(Level.INFO, "answered on {0} — the tiers before it are out",
                            tier.provider());
                }
                return response;
            } catch (RuntimeException e) {
                if (i == 0) {
                    hardFailure.set(!exhausted(e));
                    lastError.set(e.getMessage());
                } else {
                    /*
                      Every tier that failed gets on the record. The first message alone
                      names the wrong console: an operator reading "all groq keys
                      exhausted" would go top up Groq while the outage that actually
                      reached the stub was somewhere else entirely.
                    */
                    lastError.set(lastError.get() == null ? e.getMessage()
                            : lastError.get() + "; " + e.getMessage());
                }
                LOG.log(Level.WARNING, "{0} unavailable ({1})", tier.provider(), e.getMessage());
            }
        }
        return fallback.complete(request);
    }

    private static boolean usable(GroqLlmClient client) {
        return client != null && !client.keys().empty();
    }

    @Override
    public String name() {
        if (!configured()) {
            return fallback.name();
        }
        if (!degraded()) {
            return answering().name();
        }
        return fallback.name() + " ("
                + (usable(primary()) ? primary().provider() : "llm") + " degraded)";
    }

    private boolean configured() {
        return tiers.stream().anyMatch(RoutingLlmClient::usable);
    }

    /** The tier the next call would reach — what {@code provider} and {@code model} describe. */
    private GroqLlmClient answering() {
        for (int i = 0; i < tiers.size(); i++) {
            GroqLlmClient tier = tiers.get(i);
            if (!usable(tier) || tier.keys().available() == 0) {
                continue;
            }
            if (i == 0 && hardFailure.get()) {
                continue;
            }
            return tier;
        }
        for (GroqLlmClient tier : tiers) {
            if (usable(tier)) {
                return tier;
            }
        }
        return null;
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
        for (int i = 1; i < tiers.size(); i++) {
            GroqLlmClient tier = tiers.get(i);
            if (usable(tier) && tier.keys().available() > 0) {
                // A tier behind the first can answer, so the product is not degraded — it is
                // just not on its first choice this minute. Reporting red here would send
                // someone looking for an outage another provider has already absorbed.
                return false;
            }
        }
        if (!usable(primary())) {
            return true;
        }
        return hardFailure.get() || primary().keys().available() == 0;
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
        boolean configured = configured();
        GroqLlmClient answering = configured ? answering() : null;
        map.put("provider", configured && !degraded() && answering != null
                ? answering.provider() : "stub");
        map.put("model", configured && answering != null ? answering.model() : "stub");
        map.put("degraded", degraded());
        // These stay about the primary, which is what they have always meant and what the
        // screens read. The second tier gets its own block rather than being averaged in.
        map.put("keysTotal", usable(primary()) ? primary().keys().total() : 0);
        map.put("keysAvailable", usable(primary()) ? primary().keys().available() : 0);
        map.put("lastError", lastError.get());
        // Groq counts tokens per organisation. Fewer organisations than keys means the
        // extra keys are spending one budget, which is the one thing rotation cannot fix.
        if (usable(primary()) && !primary().organisations().isEmpty()) {
            map.put("organizations", primary().organisations());
        }
        // Which jobs are being answered cheaply. The split is a property, so the only way to
        // know what a running deployment is actually doing is to ask the running deployment.
        if (usable(primary()) && primary().smallModel() != null
                && !primary().smallPurposes().isEmpty()) {
            Map<String, Object> routing = new LinkedHashMap<>();
            routing.put("strongModel", primary().model());
            routing.put("smallModel", primary().smallModel());
            routing.put("smallPurposes", primary().smallPurposes());
            map.put("routing", routing);
        }
        // Kept under its old name because every screen and every operator already reads it
        // there. `tiers` below is the whole chain, in the order it is tried.
        if (usable(tier(1))) {
            map.put("fallback", describe(tier(1)));
        }
        if (tiers.size() > 1) {
            List<Map<String, Object>> chain = new ArrayList<>();
            for (GroqLlmClient tier : tiers) {
                if (usable(tier)) {
                    chain.add(describe(tier));
                }
            }
            map.put("tiers", chain);
        }
        return map;
    }

    private static Map<String, Object> describe(GroqLlmClient tier) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("provider", tier.provider());
        one.put("model", tier.model());
        one.put("keysTotal", tier.keys().total());
        one.put("keysAvailable", tier.keys().available());
        return one;
    }
}
