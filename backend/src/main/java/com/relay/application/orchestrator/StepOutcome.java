package com.relay.application.orchestrator;

import com.relay.application.port.LlmResponse;

/**
 * What a tool agent got out of one step.
 *
 * <p>{@code premiumCostUsd} and {@code model} ride along because the coordinator is what
 * records cost, and it only ever saw two numbers. The routing that sends a cheap job to a
 * cheap model is worth nothing on screen if the answer to "which model, and what would the
 * strong one have cost" is dropped one layer below the place that writes it down.
 *
 * <p>The short factories are kept for outcomes that never involved a model — an unknown
 * tool, a provider that threw — and they say "not derivable" rather than "zero", which is
 * the difference between an unknown saving and a claim of no saving.
 */
public record StepOutcome(boolean ok, Object result, String error, long tokens, double costUsd,
                          Double premiumCostUsd, String model, String skipReason) {

    public StepOutcome(boolean ok, Object result, String error, long tokens, double costUsd) {
        this(ok, result, error, tokens, costUsd, null, null, null);
    }

    public static StepOutcome ok(Object result, long tokens, double costUsd) {
        return new StepOutcome(true, result, null, tokens, costUsd);
    }

    public static StepOutcome ok(Object result, long tokens, double costUsd,
                                 Double premiumCostUsd, String model) {
        return new StepOutcome(true, result, null, tokens, costUsd, premiumCostUsd, model, null);
    }

    /** Straight off the model's own answer — the only form that keeps everything. */
    public static StepOutcome ok(Object result, LlmResponse response) {
        return new StepOutcome(true, result, null, response.totalTokens(), response.costUsd(),
                response.premiumCostUsd(), response.model(), null);
    }

    public static StepOutcome failed(String error, long tokens, double costUsd) {
        return new StepOutcome(false, null, error, tokens, costUsd);
    }

    public static StepOutcome failed(String error, long tokens, double costUsd,
                                     Double premiumCostUsd, String model) {
        return new StepOutcome(false, null, error, tokens, costUsd, premiumCostUsd, model, null);
    }

    /**
     * The specialist affirmed there is nothing for the step to act on — neither success nor
     * failure. {@code ok} is false so no path treats a skip as a result to verify, and the
     * coordinator checks {@link #skipped()} before it checks {@code ok}.
     */
    public static StepOutcome skipped(String reason, long tokens, double costUsd,
                                      Double premiumCostUsd, String model) {
        return new StepOutcome(false, null, null, tokens, costUsd, premiumCostUsd, model, reason);
    }

    public boolean skipped() {
        return skipReason != null;
    }
}
