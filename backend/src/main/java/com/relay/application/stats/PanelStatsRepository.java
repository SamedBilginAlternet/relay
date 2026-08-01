package com.relay.application.stats;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the panel is allowed to know, and the only way it may learn it.
 *
 * <p>Five aggregates, five SQL statements, no model call anywhere behind them. That is
 * not a style preference: the Groq quota is the most fragile resource this product has,
 * and a dashboard that spends it would make the numbers cost more than the work they
 * describe. If a figure cannot be produced by the database, it does not belong here.
 *
 * <p>The window is always applied to {@code runs.created_at} — a step is counted in the
 * window its run started in, so a single run never gets split across two ranges.
 */
public interface PanelStatsRepository {

    /** One row per run status actually present in the window. */
    List<Count> runStatusCounts(Instant from, Instant to);

    /** Run-level money. Authoritative: it includes planning and summary, which no step owns. */
    Totals runTotals(Instant from, Instant to);

    /** How many steps there were, and how many of them stopped at the approval gate. */
    Gate gateCounts(Instant from, Instant to);

    /**
     * Rejected steps, newest first, each carrying the sentence a human typed and the run
     * it belongs to. {@code steps.reject_reason} has been written since day one and read
     * back by nothing — this is the query that finally reads it.
     */
    List<Rejection> rejections(Instant from, Instant to, int limit);

    /** Calls and cost per tool. A call is a step that actually reached the provider. */
    List<ToolUsage> toolUsage(Instant from, Instant to);

    /** {@code (status, how many)}. */
    record Count(String key, long count) {
    }

    record Totals(long runs, long tokens, double usd) {
    }

    /**
     * @param steps    every step in the window
     * @param gated    steps that reached the approval gate, decided or still waiting
     * @param approved a human said yes
     * @param rejected a human said no
     * @param pending  still standing at the gate, nobody has answered yet
     */
    record Gate(long steps, long gated, long approved, long rejected, long pending) {
    }

    /**
     * {@code reason} may be null: a rejection without a typed sentence is still a rejection.
     *
     * <p>{@code runStatus} is carried because cancelling a run writes every unfinished step
     * off as rejected too (Coordinator.stop). Those closures are indistinguishable from a
     * refusal in the schema — same decision, same status — and the only way to tell them
     * apart would be to pattern-match the reason text, which is a guess dressed up as a
     * fact. So the panel reports what is recorded and hands the reader the one thing that
     * makes the line readable: which run it belongs to, and what happened to that run.
     */
    record Rejection(UUID runId, UUID stepId, String runGoal, String runStatus, String stepTitle,
                     String toolName, String reason, Instant at) {
    }

    record ToolUsage(String toolName, long calls, long tokens, double usd) {
    }
}
