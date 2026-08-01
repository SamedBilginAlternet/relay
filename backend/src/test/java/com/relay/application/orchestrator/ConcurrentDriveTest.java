package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The same flow, touched by two people at once.
 *
 * <p>Nothing in the product stops a colleague opening the run the moment you do, or the same
 * person leaving the demo open in two tabs — every one of those is a {@code drive} on the same
 * id. The coordinator's per-run lock is the only thing standing between that and a tool call
 * being made twice, which is a duplicate Jira record and a duplicate Slack message with the
 * audit trail claiming one of each.
 *
 * <p>Written with latches: the threads are released together on purpose, and the tool double
 * fails the test the moment two of them are inside it.
 */
class ConcurrentDriveTest {

    /** A read tool that holds the run open long enough for the other threads to pile in. */
    private static final class SlowTool implements Tool {
        private final AtomicInteger inside = new AtomicInteger();
        private final AtomicInteger everConcurrent = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "jira.searchIssues";
        }

        @Override
        public String description() {
            return "slow read";
        }

        @Override
        public JsonNode schema() {
            var schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            schema.putObject("properties").putObject("jql").put("type", "string");
            return schema;
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls.incrementAndGet();
            int now = inside.incrementAndGet();
            everConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inside.decrementAndGet();
            }
            var result = Json.object();
            result.putArray("issues");
            return ToolResult.ok(result, 1, "replay");
        }
    }

    @Test
    void two_drives_that_start_together_run_each_step_exactly_once() throws Exception {
        SlowTool tool = new SlowTool();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        LlmClient llm = new StubLlmClient(tools);
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);

        Run run = Run.create("Jira'da blocker'ları bul", clock.now(), 1.0);
        runs.save(run);

        int drivers = 6;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(drivers);
        for (int i = 0; i < drivers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    coordinator.drive(run.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("every driver returned").isTrue();

        assertThat(tool.everConcurrent.get())
                .as("a provider may never see the same run from two threads at once")
                .isEqualTo(1);

        Set<String> started = new HashSet<>();
        for (RunEvent event : events.ofType(RunEvent.STEP_STARTED)) {
            String stepId = String.valueOf(event.data().get("stepId"));
            assertThat(started.add(stepId)).as("step %s was started twice", stepId).isTrue();
        }
        assertThat(started).hasSize(run.steps().size());
        assertThat(tool.calls.get()).isEqualTo(started.size());
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }
}
