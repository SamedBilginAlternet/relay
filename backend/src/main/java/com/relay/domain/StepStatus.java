package com.relay.domain;

/** Lifecycle of a single step. Wire value is the lowercase snake_case name. */
public enum StepStatus {
    PENDING,
    AWAITING_APPROVAL,
    RUNNING,
    DONE,
    FAILED,
    REJECTED;

    public String wire() {
        return name().toLowerCase();
    }

    public boolean terminal() {
        return this == DONE || this == FAILED || this == REJECTED;
    }
}
