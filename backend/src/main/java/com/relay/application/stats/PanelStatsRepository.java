package com.relay.application.stats;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the panel is allowed to know, and the only way it may learn it.
 *
 * <p>Aggregates only, one SQL statement each, no model call anywhere behind them. That
 * is not a style preference: the Groq quota is the most fragile resource this product
 * has, and a dashboard that spends it would make the numbers cost more than the work
 * they describe. If a figure cannot be produced by the database, it does not belong
 * here.
 *
 * <p>The window is always applied to {@code runs.created_at} — a step is counted in the
 * window its run started in, so a single run never gets split across two ranges.
 */
public interface PanelStatsRepository {

    /**
     * The sentence {@code Coordinator.stop} writes into {@code steps.reject_reason} when
     * somebody stops a whole run, with the optional {@code " (actor)"} left off.
     *
     * <p>It lives here because the panel keys on it, and a constant nobody can find is how
     * a coupling turns into a bug. The coupling itself is real and worth stating plainly:
     * the schema records a write-off and a refusal identically — same {@code decision},
     * same {@code status} — so telling them apart means matching a literal our own code
     * produces. {@code PanelMarkersTest} drives a real cancellation through
     * {@code RunService} and fails the build the day that literal changes. What would
     * remove the coupling is a decision value of its own ({@code steps.decision =
     * 'cancelled'}), which is a migration and a change inside the orchestrator.
     */
    String CANCEL_REASON_PREFIX = "akış iptal edildi";

    /** One row per run status actually present in the window. */
    List<Count> runStatusCounts(Instant from, Instant to);

    /** Run-level money. Authoritative: it includes planning and summary, which no step owns. */
    Totals runTotals(Instant from, Instant to);

    /** How many steps there were, and how many of them stopped at the approval gate. */
    Gate gateCounts(Instant from, Instant to);

    /**
     * Steps a human refused, newest first, each carrying the sentence they typed and the
     * run it belongs to. {@code steps.reject_reason} has been written since day one and
     * read back by nothing — this is the query that finally reads it.
     *
     * <p>Write-offs from a cancelled run are <em>not</em> in here; see
     * {@link #cancellations(Instant, Instant, int)}.
     */
    List<Rejection> rejections(Instant from, Instant to, int limit);

    /**
     * Steps that were closed because somebody stopped the whole run, newest first.
     *
     * <p>Same rows, same shape, different question. Cancelling a run marks every
     * unfinished step {@code rejected} ({@code Coordinator.stop}), so these used to sit in
     * the refusal list and outnumber it: on the live box, four of six "red gerekçesi" were
     * one person pressing Durdur. That list is the only evidence the approval gate is
     * worth its friction, and it was mostly not evidence at all.
     */
    List<Rejection> cancellations(Instant from, Instant to, int limit);

    /** Calls and cost per tool. A call is a step that actually reached the provider. */
    List<ToolUsage> toolUsage(Instant from, Instant to);

    /** {@code (status, how many)}. */
    record Count(String key, long count) {
    }

    record Totals(long runs, long tokens, double usd) {
    }

    /**
     * @param steps     every step in the window
     * @param gated     steps carrying a decision or still waiting for one. Write-offs from
     *                  a cancelled run are counted here, because the schema cannot say
     *                  which of them had actually reached the gate when Durdur was pressed
     *                  — so the bucket below keeps them visible instead of quietly dropping
     *                  them out of a total the screen prints
     * @param approved  a human said yes, edited or not
     * @param rejected  a human said no. Cancellation write-offs are excluded: pressing
     *                  Durdur is one decision about a run, not N decisions about its steps
     * @param cancelled steps closed by that Durdur — no human ever answered them
     * @param pending   still standing at the gate, nobody has answered yet
     */
    record Gate(long steps, long gated, long approved, long rejected, long cancelled, long pending) {
    }

    /**
     * {@code reason} may be null: a rejection without a typed sentence is still a rejection.
     *
     * <p>{@code runStatus} is carried because cancelling a run writes every unfinished step
     * off as rejected too (Coordinator.stop), and the reader has to be able to see which
     * kind of line they are on. The two are now split into separate lists, but a real
     * refusal on a run that was cancelled later is still a real refusal — the status says
     * so on the line instead of moving it.
     */
    record Rejection(UUID runId, UUID stepId, String runGoal, String runStatus, String stepTitle,
                     String toolName, String reason, Instant at) {
    }

    record ToolUsage(String toolName, long calls, long tokens, double usd) {
    }
}
