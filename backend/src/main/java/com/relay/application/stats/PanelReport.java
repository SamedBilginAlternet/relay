package com.relay.application.stats;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What {@code GET /api/panel} answers: the window, and the four questions a buyer asks
 * about it — how much work ran, how much of it a human had to clear, what was turned
 * down and why, and what the whole thing cost.
 *
 * <p>Nothing here is smoothed or estimated. Every number is a count or a sum; an empty
 * window produces zeros and the screen is expected to say so out loud rather than draw
 * a chart made of them.
 */
public record PanelReport(
        Instant from,
        Instant to,
        Runs runs,
        Approvals approvals,
        List<PanelStatsRepository.Rejection> rejections,
        List<PanelStatsRepository.ToolUsage> tools,
        Totals totals) {

    /**
     * @param byStatus every run status, including the ones with no runs — the breakdown
     *                 is a fixed set of buckets, and a missing key would read as "unknown"
     *                 where the truth is "none"
     */
    public record Runs(long total, Map<String, Long> byStatus) {
    }

    /**
     * @param gatedRatio   share of steps that stopped for a human, 0..1
     * @param approvalRate approved / (approved + rejected), 0..1; 0 when nothing was decided
     */
    public record Approvals(long steps, long gated, double gatedRatio,
                            long approved, long rejected, long pending, double approvalRate) {
    }

    public record Totals(long tokens, double usd) {
    }
}
