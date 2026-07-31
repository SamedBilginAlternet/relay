package com.relay.domain;

/** Lifecycle of a whole run. Wire value is the lowercase snake_case name. */
public enum RunStatus {
    PLANNING,
    AWAITING_APPROVAL,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED;

    public String wire() {
        return name().toLowerCase();
    }

    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
