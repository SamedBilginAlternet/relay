package com.relay.domain;

/** How a step was cleared for execution. */
public enum Decision {
    AUTO,
    APPROVED,
    REJECTED;

    public String wire() {
        return name().toLowerCase();
    }
}
