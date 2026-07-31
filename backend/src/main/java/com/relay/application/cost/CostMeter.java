package com.relay.application.cost;

import com.relay.domain.Run;
import com.relay.domain.Step;

/**
 * Token and dollar accounting, and nothing else.
 *
 * <p>Every LLM call lands here twice: once on the step, once on the run total.
 * When the run total passes {@code budgetUsd} the coordinator pauses and asks.
 */
public class CostMeter {

    /** Rounded to cents-of-a-cent so the UI does not show float noise. */
    private static double round(double usd) {
        return Math.round(usd * 1_000_000d) / 1_000_000d;
    }

    public void record(Run run, Step step, long tokens, double costUsd) {
        double usd = round(costUsd);
        if (step != null) {
            step.addCost(tokens, usd);
            step.costUsd(round(step.costUsd()));
        }
        run.addCost(tokens, usd);
        run.costUsd(round(run.costUsd()));
    }

    /** LLM usage that is not attached to a step (planning, verification of the whole run). */
    public void recordRunLevel(Run run, long tokens, double costUsd) {
        record(run, null, tokens, costUsd);
    }

    /** True when the run has spent more than its budget and the user has not overridden it. */
    public boolean budgetExceeded(Run run) {
        return run.overBudget() && !run.budgetOverridden();
    }

    public double remaining(Run run) {
        if (run.budgetUsd() == null) {
            return Double.POSITIVE_INFINITY;
        }
        return round(run.budgetUsd() - run.costUsd());
    }
}
