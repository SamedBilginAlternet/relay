package com.relay.application.stats;

import com.relay.application.port.Clock;
import com.relay.domain.RunStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the flow panel. Ratios and window arithmetic live here; counting lives in
 * SQL. The class has no LlmClient, no ToolRegistry and no network of any kind, and
 * {@code PanelReportTest} asserts that it stays that way.
 */
public class PanelService {

    /** "Son 7 gün" — what the screen opens on when nobody picked a range. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    /**
     * A reject reason is a sentence, not a metric; past the first page of them the list
     * stops being readable and starts being a download. The newest ones are the ones a
     * demo is asked about.
     *
     * <p>Applied to each list separately, on purpose. A single shared limit would let one
     * afternoon of cancelled runs push every real refusal off the page — which is exactly
     * the failure the split exists to end.
     */
    private static final int REJECTION_LIMIT = 50;

    /** A window nobody can mean. Guards against {@code from=1970-01-01} pulling the whole table. */
    private static final Duration MAX_WINDOW = Duration.ofDays(366);

    private final PanelStatsRepository stats;
    private final Clock clock;
    private final ZoneId zone;

    public PanelService(PanelStatsRepository stats, Clock clock, ZoneId zone) {
        this.stats = stats;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * Both bounds are optional and accept either a full ISO-8601 instant
     * ({@code 2026-07-25T00:00:00Z}) or a plain day ({@code 2026-07-25}), which is read
     * in the panel's timezone. The window is half-open — {@code [from, to)} — and a plain
     * {@code to} day is therefore stretched to the end of that day, because a person who
     * types "to 31 July" means the 31st included.
     */
    public PanelReport report(String from, String to) {
        Instant end = to == null || to.isBlank() ? clock.now() : parse(to, true, "to");
        Instant start = from == null || from.isBlank() ? end.minus(DEFAULT_WINDOW) : parse(from, false, "from");
        return between(start, end);
    }

    /** The same report once the bounds are already instants. Half-open: {@code [from, to)}. */
    public PanelReport between(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Başlangıç bitişten sonra olamaz.");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("Aralık en fazla 366 gün olabilir.");
        }

        PanelStatsRepository.Totals totals = stats.runTotals(from, to);
        PanelStatsRepository.Gate gate = stats.gateCounts(from, to);
        // The two halves are cut from the same column by the same query, so this can only
        // go negative if the repository contradicts itself. It is clamped anyway: a bar of
        // length -3 is a lie the reader cannot see, where a 0 next to a wrong total is one
        // they can. The invariant is asserted in PanelReportTest.
        long approvedWithEdit = Math.min(gate.approvedWithEdit(), gate.approved());
        long decisions = gate.approved() + gate.rejected();
        List<PanelStatsRepository.ModelUsage> models = stats.modelUsage(from, to);

        return new PanelReport(
                from,
                to,
                new PanelReport.Runs(totals.runs(), byStatus(stats.runStatusCounts(from, to))),
                new PanelReport.Approvals(
                        gate.steps(),
                        gate.gated(),
                        share(gate.gated(), gate.steps()),
                        gate.approved(),
                        gate.approved() - approvedWithEdit,
                        approvedWithEdit,
                        gate.rejected(),
                        gate.cancelled(),
                        gate.pending(),
                        share(gate.approved(), decisions),
                        share(approvedWithEdit, decisions)),
                stats.rejections(from, to, REJECTION_LIMIT),
                stats.cancellations(from, to, REJECTION_LIMIT),
                stats.toolUsage(from, to),
                models,
                routing(models),
                new PanelReport.Totals(totals.tokens(), totals.usd()));
    }

    // -----------------------------------------------------------------------

    /**
     * Folds the per-model rows into the one comparison line, or refuses to.
     *
     * <p>It is a sum of the same rows the table above it prints — not a second query with
     * a second predicate — so the column adds up to the line and a reader can check it by
     * eye. Nothing is scaled, extrapolated to the rows that carry no model, or projected
     * onto a month.
     *
     * <p>Returns {@code null} unless <em>every</em> row carries a premium price. A partial
     * comparison is the failure worth guarding: sum the real cost of ten steps against the
     * counterfactual of the three that happen to have one and the difference is not wrong
     * by a rounding, it is about a different window than the one on the header.
     */
    private static PanelReport.Routing routing(List<PanelStatsRepository.ModelUsage> models) {
        if (models.isEmpty()) {
            return null;
        }
        long calls = 0;
        long tokens = 0;
        double usd = 0;
        double premium = 0;
        for (PanelStatsRepository.ModelUsage model : models) {
            if (model.premiumUsd() == null) {
                return null;
            }
            calls += model.calls();
            tokens += model.tokens();
            usd += model.usd();
            premium += model.premiumUsd();
        }
        return new PanelReport.Routing(calls, tokens, usd, premium, premium - usd);
    }

    /**
     * Every status in the enum's own order, so the bars keep their places between two
     * refreshes instead of reshuffling when a bucket empties out. A status the database
     * reports but the enum has never heard of is still shown — it is a real run, and
     * hiding it would make the breakdown disagree with the total.
     */
    private static Map<String, Long> byStatus(List<PanelStatsRepository.Count> counts) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (RunStatus status : RunStatus.values()) {
            out.put(status.wire(), 0L);
        }
        for (PanelStatsRepository.Count count : counts) {
            String key = count.key() == null ? "unknown" : count.key().toLowerCase(java.util.Locale.ROOT);
            out.merge(key, count.count(), Long::sum);
        }
        return out;
    }

    /** 0 rather than NaN: an undefined ratio is drawn as an empty bar, never as a hole. */
    private static double share(long part, long whole) {
        return whole <= 0 ? 0d : (double) part / (double) whole;
    }

    private Instant parse(String raw, boolean endOfDay, String field) {
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // not an instant — try a plain day below
        }
        try {
            LocalDate day = LocalDate.parse(value);
            return (endOfDay ? day.plusDays(1) : day).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + field + "' bir tarih (2026-07-25) veya zaman damgası olmalı.");
        }
    }
}
