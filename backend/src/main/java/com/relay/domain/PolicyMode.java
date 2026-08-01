package com.relay.domain;

/** Governance mode for a tool. */
public enum PolicyMode {
    AUTO,
    ASK,
    FORBIDDEN;

    public String wire() {
        return name().toLowerCase();
    }

    /**
     * Both messages reach the person editing a policy on screen, so both are written in the
     * language the screen is in — and {@code valueOf} is not allowed to answer for us, since
     * "No enum constant com.relay.domain.PolicyMode.MAYBE" is not a sentence (#81).
     */
    public static PolicyMode fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Politika modu gerekli: auto, ask ya da forbidden.");
        }
        try {
            return PolicyMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Geçersiz politika modu: " + value.trim()
                    + ". Beklenen: auto, ask ya da forbidden.");
        }
    }
}
