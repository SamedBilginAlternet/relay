package com.relay.application.connection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tokens leave the process masked or not at all. */
public final class Masking {

    private static final Set<String> SECRET_KEYS = Set.of(
            "apitoken", "token", "bottoken", "password", "secret", "apikey", "key");

    private Masking() {
    }

    public static boolean isSecret(String key) {
        return key != null && SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT).replace("_", ""));
    }

    /** {@code xoxb-2f9a…4d21} -> {@code xoxb-****4d21} */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 6) {
            return "****";
        }
        int prefix = Math.min(5, trimmed.length() - 4);
        return trimmed.substring(0, prefix) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    public static Map<String, String> maskConfig(Map<String, String> config) {
        Map<String, String> out = new LinkedHashMap<>();
        config.forEach((key, value) -> out.put(key, isSecret(key) ? mask(value) : value));
        return out;
    }
}
