package com.relay.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Credentials for one provider. {@code config} is stored encrypted (AES-GCM) and
 * is never logged; the API masks it.
 */
public class Connection {

    private final UUID id;
    private final String provider;
    private Map<String, String> config;
    private final Instant createdAt;

    public Connection(UUID id, String provider, Map<String, String> config, Instant createdAt) {
        this.id = id;
        this.provider = provider;
        this.config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
        this.createdAt = createdAt;
    }

    public static Connection of(String provider, Map<String, String> config, Instant now) {
        return new Connection(UUID.randomUUID(), provider, config, now);
    }

    public UUID id() {
        return id;
    }

    public String provider() {
        return provider;
    }

    public Map<String, String> config() {
        return config;
    }

    public void config(Map<String, String> config) {
        this.config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
    }

    public String get(String key) {
        return config.get(key);
    }

    public String getOrDefault(String key, String fallback) {
        String value = config.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Never dump credentials — not even by accident. */
    @Override
    public String toString() {
        return "Connection[provider=" + provider + ", keys=" + config.keySet() + "]";
    }
}
