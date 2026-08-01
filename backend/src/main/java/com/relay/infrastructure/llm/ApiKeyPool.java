package com.relay.infrastructure.llm;

import com.relay.application.port.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Round-robin over the configured Groq keys. A key that answers 429 / quota-exceeded is
 * parked — for as long as the provider asked, or {@code cooldown} (60s) when it said nothing
 * — and skipped meanwhile.
 *
 * <p>What rotation buys is worth being exact about, because it was assumed wrong once: Groq
 * counts tokens per <em>organisation</em>, not per key. Five keys from one account share one
 * daily budget, so rotating between them buys nothing when that budget is spent — it only
 * spreads the per-minute burst. Keys from different organisations have separate budgets, and
 * that is the case this class is actually useful for.
 *
 * <p>Deliberately tiny and synchronous: the whole class is the rotation logic under test.
 */
public class ApiKeyPool {

    private final List<String> keys;
    private final Map<String, Instant> coolingUntil = new HashMap<>();
    private final Set<String> retired = new HashSet<>();
    private final Duration cooldown;
    private final Clock clock;
    private int cursor;

    public ApiKeyPool(List<String> keys, Duration cooldown, Clock clock) {
        this.keys = List.copyOf(keys);
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** Next usable key, or empty when every key is cooling down. */
    public synchronized Optional<String> next() {
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.now();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(cursor % keys.size());
            cursor = (cursor + 1) % keys.size();
            if (!retired.contains(key)) {
                Instant until = coolingUntil.get(key);
                if (until == null || !until.isAfter(now)) {
                    return Optional.of(key);
                }
            }
        }
        return Optional.empty();
    }

    /** Park a key that just got rate limited. It comes back when the cooldown ends. */
    public synchronized void penalize(String key) {
        penalize(key, null);
    }

    /**
     * The longest a provider may sideline a key. Beyond this its answer is not believed.
     *
     * <p>An hour is chosen against what a limit actually looks like: Groq's daily budget is a
     * rolling window and asks for tens of minutes, so an hour covers it, while a value that
     * would take a key out for a working day is refused whatever the reason given.
     */
    static final Duration MAX_PARK = Duration.ofHours(1);

    /**
     * Park a key for as long as the provider asked, falling back to the configured cooldown.
     *
     * <p>This used to clamp to the 60s cooldown, which quietly turned "come back in 38
     * minutes" into thirty-eight pointless attempts — and, worse, into thirty-eight rounds of
     * rotation that put every other key through the same refusal. Once keys from more than
     * one organisation are in the pool that is the difference between working and not: an
     * organisation that has spent its daily budget has to stay parked for as long as it says,
     * so the traffic goes to one that has budget left, instead of round-robining back into
     * the same 429 every minute.
     *
     * <p>Still bounded, because the reason the clamp existed has not gone away: a wrong or
     * hostile {@code Retry-After} must not be able to sideline a key indefinitely.
     */
    public synchronized void penalize(String key, Duration requested) {
        Duration wait = requested == null || requested.isNegative() || requested.isZero()
                ? cooldown
                : requested.compareTo(MAX_PARK) > 0 ? MAX_PARK : requested;
        coolingUntil.put(key, clock.now().plus(wait));
    }

    /**
     * Retire a key the provider refused outright (401/403/402). Waiting will not fix a
     * revoked key, and pretending otherwise makes {@code /api/health} flip back to green
     * every cooldown while every call keeps failing.
     */
    public synchronized void retire(String key) {
        retired.add(key);
        coolingUntil.remove(key);
    }

    public synchronized void clearCooldown(String key) {
        coolingUntil.remove(key);
        retired.remove(key);
    }

    public synchronized int total() {
        return keys.size();
    }

    public synchronized int available() {
        Instant now = clock.now();
        int count = 0;
        for (String key : keys) {
            if (retired.contains(key)) {
                continue;
            }
            Instant until = coolingUntil.get(key);
            if (until == null || !until.isAfter(now)) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean empty() {
        return keys.isEmpty();
    }

    /** Masked key list, safe to log or return from the API. */
    public synchronized List<String> masked() {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            out.add(mask(key));
        }
        return out;
    }

    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String trimmed = secret.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
