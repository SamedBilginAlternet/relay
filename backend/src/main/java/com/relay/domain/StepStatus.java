package com.relay.domain;

/** Lifecycle of a single step. Wire value is the lowercase snake_case name. */
public enum StepStatus {
    PENDING,
    AWAITING_APPROVAL,
    RUNNING,
    DONE,
    FAILED,
    REJECTED,
    /**
     * The step's precondition came back empty, so there was nothing to do it <em>to</em>.
     *
     * <p>Live on 2026-08-01: "iş talebi olanlar için Jira kaydı aç" found zero qualifying
     * mails, and the run then FAILED trying to draft a {@code jira.createIssue} for a mail
     * that did not exist. Neither of the two honest outcomes is a failure: the condition
     * being empty is the flow working. Distinct from {@link #REJECTED} (a person or a policy
     * said no) and from {@link #DONE} (work happened) — a skipped step did no work and
     * nobody had to refuse it.
     */
    SKIPPED;

    public String wire() {
        return name().toLowerCase();
    }

    public boolean terminal() {
        return this == DONE || this == FAILED || this == REJECTED || this == SKIPPED;
    }
}
