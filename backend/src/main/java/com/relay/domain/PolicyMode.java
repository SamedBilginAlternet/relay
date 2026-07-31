package com.relay.domain;

/** Governance mode for a tool. */
public enum PolicyMode {
    AUTO,
    ASK,
    FORBIDDEN;

    public String wire() {
        return name().toLowerCase();
    }

    public static PolicyMode fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("policy mode is required");
        }
        return PolicyMode.valueOf(value.trim().toUpperCase());
    }
}
