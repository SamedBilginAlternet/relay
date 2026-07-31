package com.relay.domain;

/** Risk of a tool. Drives the default policy: READ -> auto, WRITE -> ask, DESTRUCTIVE -> forbidden. */
public enum RiskLevel {
    READ,
    WRITE,
    DESTRUCTIVE;

    public String wire() {
        return name().toLowerCase();
    }

    public PolicyMode defaultMode() {
        return switch (this) {
            case READ -> PolicyMode.AUTO;
            case WRITE -> PolicyMode.ASK;
            case DESTRUCTIVE -> PolicyMode.FORBIDDEN;
        };
    }
}
