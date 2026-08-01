package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.OrchestratorHarness;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One press, one decision in the record.
 *
 * <p>Two approvals of the same step arriving together both answered 200 and both wrote
 * "Onaylandı" into the trail, while the tool ran exactly once — the coordinator's lock saw to
 * that. So the audit trail carried a second signature against an action that had already
 * happened; had the two requests come from two people, the record would show both of them
 * approving, when only one of them decided anything. "Kim, neyi, neden" is the whole claim the
 * trail makes, and a decision that decided nothing answers none of the three.
 */
class DoubleDecisionTest {

    private TestDoubles.InMemoryRunRepository runs;
    private RunService service;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures),
                new SlackTool.ListChannels("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));
        OrchestratorHarness harness = OrchestratorHarness.of(tools);
        runs = harness.runs;
        service = harness.service;
    }

    private static final String GOAL =
            "Sprint'teki blocker'ları Jira'da bul, durumlarını güncelle ve ekibe Slack'ten özet at";

    @Test
    void a_second_approval_of_the_same_step_is_rejected_and_not_journalled() throws Exception {
        Run run = service.start(GOAL, null);
        Step gate = parked(run);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            Thread presser = new Thread(() -> {
                try {
                    go.await();
                    service.approve(run.id(), gate.id(), "qa@relay.test");
                    accepted.incrementAndGet();
                } catch (RunService.Conflict e) {
                    refused.incrementAndGet();
                } catch (Throwable e) {
                    unexpected.set(e);
                } finally {
                    done.countDown();
                }
            });
            presser.setDaemon(true);
            presser.start();
        }

        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(unexpected.get()).isNull();

        assertThat(accepted.get()).as("exactly one press decides").isEqualTo(1);
        assertThat(refused.get()).as("the other is a conflict, not a second decision").isEqualTo(1);
        assertThat(approvalsOf(run, gate)).as("one decision, one line in the trail").isEqualTo(1);
    }

    /** The sequential case, for the person who clicks twice because the screen looked stuck. */
    @Test
    void approving_a_step_that_is_no_longer_at_the_gate_is_a_conflict() {
        Run run = service.start(GOAL, null);
        Step gate = parked(run);

        service.approve(run.id(), gate.id(), "qa@relay.test");

        assertThatThrownBy(() -> service.approve(run.id(), gate.id(), "qa@relay.test"))
                .isInstanceOf(RunService.Conflict.class);
        assertThat(approvalsOf(run, gate)).isEqualTo(1);
    }

    @Test
    void rejecting_a_step_twice_records_one_rejection() {
        Run run = service.start(GOAL, null);
        Step gate = parked(run);

        service.reject(run.id(), gate.id(), "yanlış kayıt", "qa@relay.test");

        assertThatThrownBy(() -> service.reject(run.id(), gate.id(), "yine yanlış", "qa@relay.test"))
                .isInstanceOf(RunService.Conflict.class);
        assertThat(run.messages().stream()
                .filter(m -> gate.id().equals(m.stepId()) && m.content().startsWith("Reddedildi"))
                .count()).isEqualTo(1);
    }

    private long approvalsOf(Run run, Step step) {
        return runs.findById(run.id()).orElseThrow().messages().stream()
                .filter(m -> step.id().equals(m.stepId()))
                .filter(m -> m.content().startsWith("Onaylandı"))
                .count();
    }

    private static Step parked(Run run) {
        return run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step is waiting on a human"));
    }
}
