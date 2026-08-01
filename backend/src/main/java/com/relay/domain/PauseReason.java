package com.relay.domain;

/**
 * Why a step is sitting in front of a human.
 *
 * <p>The two pauses look identical on the wire — both are {@code awaiting_approval} — but
 * they ask for different things, and answering one used to answer both: approving a Slack
 * message silently lifted the run's spending ceiling because the only thing the code could
 * still see at approval time was "the run is over budget". The reason the step stopped is
 * therefore written down on the step and persisted: approval arrives as a separate request,
 * with the run read back from the database, so an in-process flag would already be gone.
 */
public enum PauseReason {

    /** The tool writes somewhere, and the policy says a person signs that off. */
    POLICY,

    /** The run has spent past {@code budgetUsd}. Nothing is wrong with the step itself. */
    BUDGET;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
