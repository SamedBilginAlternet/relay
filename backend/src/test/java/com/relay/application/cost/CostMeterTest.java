package com.relay.application.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.relay.domain.Run;
import com.relay.domain.Step;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostMeterTest {

    private final CostMeter meter = new CostMeter();

    private Run run(Double budget) {
        return Run.create("blocker özeti", Instant.parse("2026-07-31T09:00:00Z"), budget);
    }

    @Test
    void accumulatesOnBothTheStepAndTheRun() {
        Run run = run(null);
        Step step = Step.create(run.id(), 1, "ara", "jira-agent", "jira.searchIssues", Map.of());
        run.addStep(step);

        meter.record(run, step, 1200, 0.0009);
        meter.record(run, step, 300, 0.0002);

        assertThat(step.tokens()).isEqualTo(1500);
        assertThat(step.costUsd()).isCloseTo(0.0011, within(1e-9));
        assertThat(run.costTokens()).isEqualTo(1500);
        assertThat(run.costUsd()).isCloseTo(0.0011, within(1e-9));
    }

    @Test
    void runLevelUsageCountsWithoutAStep() {
        Run run = run(null);
        meter.recordRunLevel(run, 800, 0.0005);

        assertThat(run.costTokens()).isEqualTo(800);
        assertThat(run.costUsd()).isCloseTo(0.0005, within(1e-9));
        assertThat(run.steps()).isEmpty();
    }

    @Test
    void multipleStepsRollUpIntoTheRunTotal() {
        Run run = run(null);
        Step first = Step.create(run.id(), 1, "ara", "jira-agent", "jira.searchIssues", Map.of());
        Step second = Step.create(run.id(), 2, "gönder", "slack-agent", "slack.postMessage", Map.of());
        run.addStep(first);
        run.addStep(second);

        meter.record(run, first, 1000, 0.001);
        meter.record(run, second, 2000, 0.002);

        assertThat(first.tokens()).isEqualTo(1000);
        assertThat(second.tokens()).isEqualTo(2000);
        assertThat(run.costTokens()).isEqualTo(3000);
        assertThat(run.costUsd()).isCloseTo(0.003, within(1e-9));
    }

    @Test
    void budgetIsExceededOnlyAboveTheLimit() {
        Run run = run(0.01);
        meter.recordRunLevel(run, 1000, 0.009);
        assertThat(meter.budgetExceeded(run)).isFalse();
        assertThat(meter.remaining(run)).isCloseTo(0.001, within(1e-9));

        meter.recordRunLevel(run, 1000, 0.002);
        assertThat(meter.budgetExceeded(run)).isTrue();
    }

    @Test
    void userOverrideClearsTheBudgetStop() {
        Run run = run(0.001);
        meter.recordRunLevel(run, 5000, 0.05);
        assertThat(meter.budgetExceeded(run)).isTrue();

        run.budgetOverridden(true);
        assertThat(meter.budgetExceeded(run)).isFalse();
        assertThat(run.overBudget()).isTrue();
    }

    @Test
    void noBudgetMeansNoLimit() {
        Run run = run(null);
        meter.recordRunLevel(run, 1_000_000, 12.5);
        assertThat(meter.budgetExceeded(run)).isFalse();
        assertThat(meter.remaining(run)).isInfinite();
    }
}
