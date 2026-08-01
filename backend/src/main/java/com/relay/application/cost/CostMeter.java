package com.relay.application.cost;

import com.relay.domain.Run;
import com.relay.domain.Step;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Token and dollar accounting, and nothing else.
 *
 * <p>Every LLM call lands here twice: once on the step, once on the run total.
 * When the run total passes {@code budgetUsd} the coordinator pauses and asks.
 */
public class CostMeter {

    /** Six decimals — a tenth of a cent of a cent. Below that nothing here is meaningful. */
    public static final int SCALE = 6;

    /** Rounded to cents-of-a-cent so the UI does not show float noise. */
    private static double round(double usd) {
        return Math.round(usd * 1_000_000d) / 1_000_000d;
    }

    /**
     * The one shape money leaves this application in.
     *
     * <p>A double that has been added to another double is not a price: two model turns
     * summed to {@code 0.0036615500000000004}, and Jackson writes small doubles in
     * scientific notation, so {@code /api/runs} answered {@code 3.82E-4}. Both reached the
     * screen. A {@code BigDecimal} fixed at six decimals prints as itself — {@code 0.000382}
     * — and every endpoint that reports a cost goes through here, so they cannot disagree
     * about the same money again.
     *
     * @param usd may be null (nothing spent yet, no budget set); null comes back
     */
    public static BigDecimal usd(Double usd) {
        if (usd == null || usd.isNaN() || usd.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(usd).setScale(SCALE, RoundingMode.HALF_UP);
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
