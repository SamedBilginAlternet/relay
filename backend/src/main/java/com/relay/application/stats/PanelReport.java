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
        List<PanelStatsRepository.Rejection> cancellations,
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
     * @param gatedRatio       share of steps that stopped for a human, 0..1
     * @param approved         every yes, edited or not. Kept alongside the two halves so
     *                         the reader can check that they add up to it
     * @param approvedAsIs     approved exactly as the agent proposed it
     * @param approvedWithEdit approved after a human rewrote a parameter — the journal
     *                         holds the field, the old value and the new one
     * @param cancelled        steps written off when somebody stopped the whole run. Kept
     *                         out of {@code rejected} and out of {@code approvalRate}:
     *                         nobody answered them, so counting them as refusals made the
     *                         gate look more discriminating than it is
     * @param approvalRate     approved / (approved + rejected), 0..1; 0 when nothing was
     *                         decided. Dropping the cancellations out of the denominator
     *                         moves this number <em>up</em> — that is the honest direction
     *                         and the uncomfortable one, which is why it is still shown
     * @param editRate         approvedWithEdit / (approved + rejected): how often a human
     *                         changed what was about to be sent. The single approval rate
     *                         said nothing about this, and it is the more interesting half
     *                         of the story — a gate that only ever says yes or no is a
     *                         speed bump; one that corrects the payload is doing work
     */
    public record Approvals(long steps, long gated, double gatedRatio,
                            long approved, long approvedAsIs, long approvedWithEdit,
                            long rejected, long cancelled, long pending,
                            double approvalRate, double editRate) {
    }

    public record Totals(long tokens, double usd) {
    }
}
