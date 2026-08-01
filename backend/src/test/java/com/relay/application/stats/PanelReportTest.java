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
        stats.gate = new PanelStatsRepository.Gate(20, 8, 3, 1, 4);

        PanelReport.Approvals approvals = panel.report(null, null).approvals();

        assertThat(approvals.gatedRatio()).isCloseTo(0.4, within(1e-9));
        // 3 / (3 + 1). Counting the four pending ones as refusals would report 75% as 37.5%.
        assertThat(approvals.approvalRate()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void an_empty_window_reports_zero_instead_of_an_invented_shape() {
        PanelReport report = panel.report(null, null);

        assertThat(report.runs().total()).isZero();
        assertThat(report.rejections()).isEmpty();
        assertThat(report.tools()).isEmpty();
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
        private final List<ToolUsage> tools = new ArrayList<>();
        private Gate gate = new Gate(0, 0, 0, 0, 0);
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
        public List<ToolUsage> toolUsage(Instant from, Instant to) {
            record(from, to);
            return tools;
        }

        private void record(Instant from, Instant to) {
            askedFrom = from;
            askedTo = to;
        }
    }
}
