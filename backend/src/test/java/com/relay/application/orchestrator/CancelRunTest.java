package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.RunEvent;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A run that has started could not be stopped: {@code RunStatus.CANCELLED} existed and
 * nothing ever set it, so a flow aimed at the wrong target ran to the end and the only
 * brake was rejecting its steps one at a time (issue #17).
 *
 * <p>The delicate half is what "stop" means while a tool call is in the air. Relay does not
 * abort it: the provider may already have done the work and only the answer would be lost,
 * and a trail that says "iptal edildi" over a write that happened is worse than one that
 * took a few more seconds to close. These tests pin both halves of that promise — nothing
 * new starts, and the call in flight is neither interrupted nor thrown away.
 */
class CancelRunTest {

    private record Rig(RunService service, Run run, TestDoubles.RecordingEventPublisher events) {
    }

    /** A run parked on an approval gate: the case where nobody is holding the run at all. */
    private Rig parkedOnApproval() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.UpdateIssue("replay", fixtures)));
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        RunService service = service(tools, runs, events);

        Run run = Run.create("KAN-4 kaydını kapat", Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(
                Step.create(run.id(), 1, "Durumu güncelle", "jira-agent", "jira.updateIssue",
                        Map.of("issueKey", "KAN-4", "status", "Done")),
                Step.create(run.id(), 2, "İkinci kaydı da güncelle", "jira-agent", "jira.updateIssue",
                        Map.of("issueKey", "KAN-5", "status", "Done"))));
        runs.save(run);
        service.driveNow(run.id());
        return new Rig(service, run, events);
    }

    private RunService service(ToolRegistry tools, TestDoubles.InMemoryRunRepository runs,
                               TestDoubles.RecordingEventPublisher events) {
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        return new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);
    }

    @Test
    void cancelling_a_run_that_waits_for_approval_closes_every_unfinished_step() {
        Rig rig = parkedOnApproval();
        assertThat(rig.run().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);

        rig.service().cancel(rig.run().id(), "ayse@sirket.com");

        assertThat(rig.run().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(rig.run().steps()).allSatisfy(step -> {
            assertThat(step.status()).isEqualTo(StepStatus.REJECTED);
            assertThat(step.rejectReason()).contains("akış iptal edildi");
            assertThat(step.finishedAt()).isNotNull();
        });
    }

    /** Without this frame the screen keeps spinning on a run that stopped minutes ago. */
    @Test
    void the_stream_says_the_run_is_over() {
        Rig rig = parkedOnApproval();

        rig.service().cancel(rig.run().id(), "ayse@sirket.com");

        assertThat(rig.events().ofType(RunEvent.RUN_FINISHED)).hasSize(1);
        assertThat(rig.events().ofType(RunEvent.RUN_FINISHED).get(0).data())
                .containsEntry("status", "cancelled");
        assertThat(rig.events().ofType(RunEvent.STEP_FINISHED)).isNotEmpty();
    }

    @Test
    void the_trail_names_who_cancelled() {
        Rig rig = parkedOnApproval();

        rig.service().cancel(rig.run().id(), "ayse@sirket.com");

        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content()).contains("Akış iptal edildi (ayse@sirket.com)"));
    }

    /**
     * Two people share a workspace: one cancels while the other still has the approval
     * screen open. The second press must not quietly resurrect the run.
     */
    @Test
    void approving_a_cancelled_run_is_a_conflict_not_a_resumption() {
        Rig rig = parkedOnApproval();
        Step gate = rig.run().steps().get(0);
        rig.service().cancel(rig.run().id(), "ayse@sirket.com");

        assertThatThrownBy(() -> rig.service().approve(rig.run().id(), gate.id(), "mert@sirket.com"))
                .isInstanceOf(RunService.Conflict.class)
                .hasMessageContaining("cancelled");
        assertThatThrownBy(() -> rig.service().reject(rig.run().id(), gate.id(), "olmaz", "mert@sirket.com"))
                .isInstanceOf(RunService.Conflict.class);

        assertThat(rig.run().status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void a_finished_run_cannot_be_cancelled_after_the_fact() {
        Rig rig = parkedOnApproval();
        rig.service().cancel(rig.run().id(), "ayse@sirket.com");

        assertThatThrownBy(() -> rig.service().cancel(rig.run().id(), "ayse@sirket.com"))
                .isInstanceOf(RunService.Conflict.class);
    }

    /**
     * The honest half of the promise: a call already sent to the provider is not aborted,
     * its result is recorded, and only the steps that had not started are written off.
     */
    @Test
    void a_tool_call_in_flight_is_finished_not_interrupted() throws Exception {
        BlockingRead blocking = new BlockingRead();
        ToolRegistry tools = new ToolRegistryImpl(List.of(blocking));
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        RunService service = service(tools, runs, events);

        Run run = Run.create("Blocker'ları bul ve ikinci kez ara", Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(
                Step.create(run.id(), 1, "İşleri ara", "jira-agent", "jira.searchIssues", Map.of()),
                Step.create(run.id(), 2, "Bir daha ara", "jira-agent", "jira.searchIssues", Map.of())));
        runs.save(run);

        Thread driver = new Thread(() -> service.driveNow(run.id()), "cancel-test-driver");
        driver.start();
        assertThat(blocking.entered.await(5, TimeUnit.SECONDS)).isTrue();

        service.cancel(run.id(), "ayse@sirket.com");

        // The press came back without waiting for the provider, and the call is still open.
        assertThat(blocking.returned).isFalse();
        assertThat(run.status()).isNotEqualTo(RunStatus.CANCELLED);

        blocking.release.countDown();
        driver.join(10_000);

        assertThat(blocking.interrupted).isFalse();
        assertThat(blocking.calls).isEqualTo(1);
        assertThat(run.status()).isEqualTo(RunStatus.CANCELLED);
        // The step that ran kept its result; only the one that never started was written off.
        assertThat(run.steps().get(0).status()).isEqualTo(StepStatus.DONE);
        assertThat(run.steps().get(1).status()).isEqualTo(StepStatus.REJECTED);
    }

    /** A read tool that parks inside {@code execute} — the "HTTP request in flight" moment. */
    private static final class BlockingRead implements Tool {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean interrupted;
        private volatile boolean returned;
        private volatile int calls;

        @Override
        public String name() {
            return "jira.searchIssues";
        }

        @Override
        public String description() {
            return "blocks until the test lets go";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the tool call");
                }
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            returned = true;
            return ToolResult.ok(Json.toNode(Map.of("issues", List.of(Map.of("key", "RELAY-14")))),
                    12, "test");
        }
    }
}
