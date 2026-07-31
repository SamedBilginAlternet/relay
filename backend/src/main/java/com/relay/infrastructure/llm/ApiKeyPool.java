package com.relay.infrastructure.llm;

import com.relay.application.port.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Round-robin over the configured Groq keys. A key that answers 429 / quota-exceeded
 * is parked for {@code cooldown} (60s by default) and skipped meanwhile.
 *
 * <p>Deliberately tiny and synchronous: the whole class is the rotation logic under test.
 */
public class ApiKeyPool {

    private final List<String> keys;
    private final Map<String, Instant> coolingUntil = new HashMap<>();
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
            Instant until = coolingUntil.get(key);
            if (until == null || !until.isAfter(now)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /** Park a key that just got rate limited or rejected. */
    public synchronized void penalize(String key) {
        coolingUntil.put(key, clock.now().plus(cooldown));
    }

    public synchronized void clearCooldown(String key) {
        coolingUntil.remove(key);
    }

    public synchronized int total() {
        return keys.size();
    }

    public synchronized int available() {
        Instant now = clock.now();
        int count = 0;
        for (String key : keys) {
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
