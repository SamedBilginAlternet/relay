package com.relay.application.orchestrator;

/** What a tool agent got out of one step. */
public record StepOutcome(boolean ok, Object result, String error, long tokens, double costUsd) {

    public static StepOutcome ok(Object result, long tokens, double costUsd) {
        return new StepOutcome(true, result, null, tokens, costUsd);
    }

    public static StepOutcome failed(String error, long tokens, double costUsd) {
        return new StepOutcome(false, null, error, tokens, costUsd);
    }
}
