package com.relay.application.connection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tokens leave the process masked or not at all. */
public final class Masking {

    private static final Set<String> SECRET_KEYS = Set.of(
            "apitoken", "token", "bottoken", "password", "secret", "apikey", "key");

    /**
     * Names that end a secret. Google's OAuth connection stores {@code refreshToken} and
     * {@code accessToken}, neither of which is in the exact-name list — so
     * {@code GET /api/connections} handed both back in full, a live refresh token in a
     * response the UI renders. Suffixes catch the next provider's spelling too.
     */
    private static final Set<String> SECRET_SUFFIXES = Set.of(
            "token", "secret", "password", "apikey", "credential", "credentials");

    private Masking() {
    }

    /**
     * Note what is deliberately <em>not</em> secret: {@code projectKey} ends in "key" and is
     * an identifier the user needs to read back. Only the bare name {@code key} is masked.
     */
    public static boolean isSecret(String key) {
        if (key == null) {
            return false;
        }
        String normalised = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (SECRET_KEYS.contains(normalised)) {
            return true;
        }
        return SECRET_SUFFIXES.stream().anyMatch(normalised::endsWith);
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
