package com.relay.application.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.relay.application.port.LlmClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Why this test exists.
 *
 * <p>Two promises are being nailed down. The first is the one the product is sold on:
 * {@code steps.reject_reason} has been written since the first migration and read back
 * by nothing, so "we can tell you what was turned down and why" was a claim with no
 * query behind it. If the panel ever stops carrying the sentence — or stops carrying the
 * run id next to it, which is what makes the sentence checkable — the claim is empty
 * again.
 *
 * <p>The second is the one that keeps the demo alive. The Groq keys ran dry on the live
 * box more than once, and every screen that depended on them fell back to a guess. A
 * dashboard is opened far more often than a run is started, so a panel that spent a
 * token per view would be the fastest way to exhaust the quota it is reporting on. The
 * reflection test below is blunt on purpose: it fails the build the day somebody hands
 * this service a model.
 *
 * <p>The third is newer and is the one most likely to be "improved" into a lie. The
 * routing comparison is a subtraction between two sums of the <em>same</em> rows, and
 * every tempting change to it — filling a missing counterfactual with zero, scaling it up
 * to the steps that carry no model, keeping the difference when only half the rows are
 * priced — makes the number bigger and makes it false. The tests below hold the shape
 * that keeps it checkable.
 */
class PanelReportTest {

    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STEP = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final FakeStats stats = new FakeStats();
    private final PanelService panel = new PanelService(stats, () -> NOW, ZoneId.of("Europe/Istanbul"));

    @Test
    void a_refusal_arrives_with_its_reason_and_the_run_it_belongs_to() {
        stats.rejections.add(new PanelStatsRepository.Rejection(RUN, STEP,
                "Sprint blocker'larını Slack'e özetle", "failed", "Slack'e mesaj gönder",
                "slack.postMessage", "Kanal yanlış, #genel yerine #dev olmalı",
                Instant.parse("2026-07-30T14:12:00Z")));

        PanelReport report = panel.report(null, null);

        assertThat(report.rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.reason()).isEqualTo("Kanal yanlış, #genel yerine #dev olmalı");
            // Without the run id the sentence is an anecdote; with it, it is a record.
            assertThat(rejection.runId()).isEqualTo(RUN);
            assertThat(rejection.toolName()).isEqualTo("slack.postMessage");
            // Cancelling a run writes its unfinished steps off as rejected as well, and the
            // schema cannot tell those apart from a refusal. The run's own status travels
            // with the line so the screen can say which one the reader is looking at.
            assertThat(rejection.runStatus()).isEqualTo("failed");
        });
    }

    @Test
    void the_approval_rate_ignores_the_steps_nobody_has_answered_yet() {
        // 20 steps, 8 of them stopped at the gate: 3 yes, 1 no, 4 still waiting.
        stats.gate = new PanelStatsRepository.Gate(20, 8, 3, 0, 1, 0, 4);

        PanelReport.Approvals approvals = panel.report(null, null).approvals();

        assertThat(approvals.gatedRatio()).isCloseTo(0.4, within(1e-9));
        // 3 / (3 + 1). Counting the four pending ones as refusals would report 75% as 37.5%.
        assertThat(approvals.approvalRate()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void a_stopped_run_does_not_lower_the_approval_rate_it_never_took_part_in() {
        // The live shape on 1 August: 50 approvals, 6 "rejections" — of which 4 were one
        // person pressing Durdur on four runs. Reported as 89.3%, which is a number about
        // cancellations, not about the gate.
        stats.gate = new PanelStatsRepository.Gate(190, 85, 50, 6, 2, 4, 29);

        PanelReport.Approvals approvals = panel.report(null, null).approvals();

        assertThat(approvals.rejected()).isEqualTo(2);
        assertThat(approvals.cancelled()).isEqualTo(4);
        // 50 / 52, not 50 / 56. The honest number is the worse one for the pitch, and it
        // is the one that gets printed: removing it was the alternative issue #54 refused.
        assertThat(approvals.approvalRate()).isCloseTo(50d / 52d, within(1e-9));
        assertThat(approvals.approvalRate()).isNotCloseTo(50d / 56d, within(1e-3));
    }

    @Test
    void the_three_decision_buckets_add_up_to_the_decisions_that_were_made() {
        // 50 approvals, 6 of them after somebody rewrote a field, 2 refusals.
        stats.gate = new PanelStatsRepository.Gate(190, 85, 50, 6, 2, 4, 29);

        PanelReport.Approvals approvals = panel.report(null, null).approvals();

        assertThat(approvals.approvedAsIs()).isEqualTo(44);
        assertThat(approvals.approvedWithEdit()).isEqualTo(6);
        // The property the screen is sold on: three numbers, no fourth place to hide in.
        assertThat(approvals.approvedAsIs() + approvals.approvedWithEdit() + approvals.rejected())
                .isEqualTo(approvals.approved() + approvals.rejected());
        // "insan kararların %X'inde gönderileni değiştirdi" — 6 of 52, not 6 of 50.
        assertThat(approvals.editRate()).isCloseTo(6d / 52d, within(1e-9));
    }

    /**
     * A repository that contradicted itself used to be able to draw a bar backwards. It
     * cannot now — but the clamp must not be reached by any honest input, so the reason it
     * exists is written down rather than discovered later.
     */
    @Test
    void an_edit_count_larger_than_the_approvals_never_produces_a_negative_bucket() {
        stats.gate = new PanelStatsRepository.Gate(10, 5, 2, 9, 1, 0, 2);

        PanelReport.Approvals approvals = panel.report(null, null).approvals();

        assertThat(approvals.approvedAsIs()).isZero();
        assertThat(approvals.approvedWithEdit()).isEqualTo(2);
    }

    /** Every row fully priced — the simple case, and the arithmetic is visible. */
    private static PanelStatsRepository.ModelUsage priced(String model, long calls, long tokens,
                                                          double usd, double premiumUsd) {
        return new PanelStatsRepository.ModelUsage(model, calls, tokens, usd,
                calls, tokens, usd, premiumUsd);
    }

    /**
     * The comparison line is the one number on this screen a buyer will repeat out loud,
     * so it may only ever be a subtraction between two sums of the same rows.
     */
    @Test
    void the_routing_comparison_is_the_priced_rows_added_up_and_nothing_else() {
        stats.models.add(priced("groq:llama-3.1-8b-instant", 180, 320_000, 0.045, 0.221));
        stats.models.add(priced("groq:llama-3.3-70b-versatile", 12, 40_000, 0.028, 0.028));

        PanelReport.Routing routing = panel.report(null, null).routing();

        assertThat(routing.calls()).isEqualTo(192);
        assertThat(routing.tokens()).isEqualTo(360_000);
        assertThat(routing.usd()).isCloseTo(0.073, within(1e-9));
        assertThat(routing.premiumUsd()).isCloseTo(0.249, within(1e-9));
        // The whole claim, and it is a subtraction: 0.249 - 0.073. Nothing is scaled up to
        // the steps that carry no model, and nothing is projected onto a month.
        assertThat(routing.differenceUsd()).isCloseTo(0.249 - 0.073, within(1e-9));
        assertThat(routing.unpricedCalls()).isZero();
    }

    /**
     * The step that answered on the strong model is priced at what it cost, on both sides.
     * A "saving" that counted it twice would grow with every expensive call.
     */
    @Test
    void a_step_the_strong_model_answered_contributes_no_difference() {
        stats.models.add(priced("groq:llama-3.3-70b-versatile", 5, 20_000, 0.019, 0.019));

        assertThat(panel.report(null, null).routing().differenceUsd()).isZero();
    }

    /**
     * Issue #119, and the reason both halves of a row exist.
     *
     * <p>A step that touched the offline stub keeps its model — whichever call produced the
     * most tokens wrote it — and loses its premium, because one unpriceable call makes the
     * step's counterfactual unknown rather than smaller. Counting that step's dollars as
     * "what we paid" with nothing opposite them made every such call look like a saving.
     */
    @Test
    void a_call_with_no_counterfactual_leaves_both_sides_of_the_subtraction() {
        // 10 calls on the model, only 6 of them priced: $0.030 of the $0.050 billed.
        stats.models.add(new PanelStatsRepository.ModelUsage(
                "groq:llama-3.1-8b-instant", 10, 50_000, 0.050, 6, 30_000, 0.030, 0.180));

        PanelReport.Routing routing = panel.report(null, null).routing();

        // 0.180 - 0.030, not 0.180 - 0.050. The four unpriced calls are on neither side.
        assertThat(routing.usd()).isCloseTo(0.030, within(1e-9));
        assertThat(routing.differenceUsd()).isCloseTo(0.150, within(1e-9));
        assertThat(routing.calls()).isEqualTo(6);
        assertThat(routing.tokens()).isEqualTo(30_000);
        // …and the reader is told how much of the window the comparison did not cover,
        // because a line that covers 6 of 10 calls must not read like one that covers 10.
        assertThat(routing.unpricedCalls()).isEqualTo(4);
    }

    /**
     * A model nothing could be priced on drops out of the comparison and stays in the
     * table. Which model carried the volume is knowable without a counterfactual, and
     * hiding the row to protect the claim would lose a fact.
     */
    @Test
    void a_model_with_no_priced_row_is_still_counted_but_makes_no_claim() {
        stats.models.add(priced("groq:llama-3.1-8b-instant", 180, 320_000, 0.045, 0.221));
        stats.models.add(new PanelStatsRepository.ModelUsage(
                "stub", 12, 40_000, 0.0, 0, 0, 0.0, null));

        PanelReport report = panel.report(null, null);

        assertThat(report.models()).hasSize(2);
        assertThat(report.routing().calls()).isEqualTo(180);
        assertThat(report.routing().unpricedCalls()).isEqualTo(12);
    }

    /** Nothing priced anywhere: no comparison, rather than zero against zero. */
    @Test
    void a_window_with_nothing_priced_makes_no_comparison_at_all() {
        stats.models.add(new PanelStatsRepository.ModelUsage(
                "stub", 12, 40_000, 0.0, 0, 0, 0.0, null));

        PanelReport report = panel.report(null, null);

        assertThat(report.routing()).isNull();
        assertThat(report.models()).hasSize(1);
    }

    @Test
    void a_cancelled_runs_write_offs_are_kept_out_of_the_reasons_list() {
        stats.rejections.add(new PanelStatsRepository.Rejection(RUN, STEP, "Özeti gönder", "done",
                "Slack'e mesaj gönder", "slack.postMessage", "Kanal #relay-qa olmalı",
                Instant.parse("2026-07-30T14:12:00Z")));
        stats.cancellations.add(new PanelStatsRepository.Rejection(RUN, STEP, "Özeti gönder", "cancelled",
                "Jira kaydını güncelle", "jira.updateIssue", "akış iptal edildi (qa@relay)",
                Instant.parse("2026-07-30T15:00:00Z")));

        PanelReport report = panel.report(null, null);

        // The list that has to prove the gate is worth its friction holds only refusals.
        assertThat(report.rejections()).singleElement()
                .satisfies(line -> assertThat(line.reason()).isEqualTo("Kanal #relay-qa olmalı"));
        assertThat(report.cancellations()).singleElement()
                .satisfies(line -> assertThat(line.stepTitle()).isEqualTo("Jira kaydını güncelle"));
    }

    @Test
    void an_empty_window_reports_zero_instead_of_an_invented_shape() {
        PanelReport report = panel.report(null, null);

        assertThat(report.runs().total()).isZero();
        assertThat(report.rejections()).isEmpty();
        assertThat(report.cancellations()).isEmpty();
        assertThat(report.tools()).isEmpty();
        assertThat(report.models()).isEmpty();
        // Not a Routing of zeros: a window with no recorded model has no counterfactual,
        // and "$0.000000 saved" is a measurement where the truth is "not measured".
        assertThat(report.routing()).isNull();
        assertThat(report.totals().tokens()).isZero();
        // An undefined ratio is zero, never NaN — NaN is not JSON and would blank the screen.
        assertThat(report.approvals().approvalRate()).isZero();
        assertThat(report.approvals().gatedRatio()).isZero();
        // Every bucket is present and honest about being empty.
        assertThat(report.runs().byStatus())
                .containsEntry("done", 0L)
                .containsEntry("failed", 0L)
                .containsEntry("cancelled", 0L)
                .containsEntry("awaiting_approval", 0L);
    }

    @Test
    void the_status_breakdown_always_adds_up_to_the_run_count() {
        stats.runs = 6;
        stats.statusCounts.add(new PanelStatsRepository.Count("done", 4));
        stats.statusCounts.add(new PanelStatsRepository.Count("cancelled", 1));
        // A status this build has never heard of is still a run that happened.
        stats.statusCounts.add(new PanelStatsRepository.Count("archived", 1));

        PanelReport.Runs runs = panel.report(null, null).runs();

        assertThat(runs.byStatus().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(runs.total());
        assertThat(runs.byStatus()).containsEntry("archived", 1L);
    }

    @Test
    void no_range_means_the_last_seven_days() {
        panel.report(null, null);

        assertThat(stats.askedTo).isEqualTo(NOW);
        assertThat(Duration.between(stats.askedFrom, stats.askedTo)).isEqualTo(PanelService.DEFAULT_WINDOW);
    }

    @Test
    void a_plain_day_as_the_upper_bound_includes_that_whole_day() {
        panel.report("2026-07-25", "2026-07-25");

        // A person who asks for 25 July means the 25th, not the empty instant at its start.
        assertThat(Duration.between(stats.askedFrom, stats.askedTo)).isEqualTo(Duration.ofDays(1));
        assertThat(stats.askedFrom).isEqualTo(Instant.parse("2026-07-24T21:00:00Z")); // Istanbul is UTC+3
    }

    @Test
    void a_backwards_range_is_refused_rather_than_answered_with_nothing() {
        assertThatThrownBy(() -> panel.report("2026-07-25T00:00:00Z", "2026-07-20T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> panel.report("dün", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_panel_cannot_reach_the_model() {
        for (Field field : PanelService.class.getDeclaredFields()) {
            assertThat(LlmClient.class.isAssignableFrom(field.getType()))
                    .as("PanelService.%s must not be a model client — the panel is SQL only", field.getName())
                    .isFalse();
        }
        for (Constructor<?> constructor : PanelService.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertThat(LlmClient.class.isAssignableFrom(parameter))
                        .as("PanelService must not accept a model client")
                        .isFalse();
            }
        }
    }

    // -----------------------------------------------------------------------

    /** Stands in for the database. It answers with rows; it cannot call anything. */
    private static final class FakeStats implements PanelStatsRepository {

        private final List<Count> statusCounts = new ArrayList<>();
        private final List<Rejection> rejections = new ArrayList<>();
        private final List<Rejection> cancellations = new ArrayList<>();
        private final List<ToolUsage> tools = new ArrayList<>();
        private final List<ModelUsage> models = new ArrayList<>();
        private Gate gate = new Gate(0, 0, 0, 0, 0, 0, 0);
        private long runs;
        private Instant askedFrom;
        private Instant askedTo;

        @Override
        public List<Count> runStatusCounts(Instant from, Instant to) {
            record(from, to);
            return statusCounts;
        }

        @Override
        public Totals runTotals(Instant from, Instant to) {
            record(from, to);
            return new Totals(runs, 0, 0);
        }

        @Override
        public Gate gateCounts(Instant from, Instant to) {
            record(from, to);
            return gate;
        }

        @Override
        public List<Rejection> rejections(Instant from, Instant to, int limit) {
            record(from, to);
            return rejections;
        }

        @Override
        public List<Rejection> cancellations(Instant from, Instant to, int limit) {
            record(from, to);
            return cancellations;
        }

        @Override
        public List<ToolUsage> toolUsage(Instant from, Instant to) {
            record(from, to);
            return tools;
        }

        @Override
        public List<ModelUsage> modelUsage(Instant from, Instant to) {
            record(from, to);
            return models;
        }

        private void record(Instant from, Instant to) {
            askedFrom = from;
            askedTo = to;
        }
    }
}
