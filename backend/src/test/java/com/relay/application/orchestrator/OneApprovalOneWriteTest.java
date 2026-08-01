package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.Clock;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.Decision;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Measured on the live box on 2026-08-01. A human approved one {@code jira.createIssue}
 * step. Three Jira records were created: KAN-24, KAN-25 and KAN-26, all with the same
 * placeholder summary, all from that single approval.
 *
 * <p>The gate is {@code policy.ask() && decision != APPROVED}. {@code Step.sendBack} clears
 * the result and raises the attempt count and left {@code decision} alone, so a step the
 * verifier rejected went round again — past a gate that still remembered a "yes" given for
 * a different attempt. The tool-error retry path already cleared it; the
 * verification-failure path did not.
 *
 * <p>This product has one promise. Everything else it does — the audit trail, the cost
 * columns, the policy table — exists to support "nothing is written without you". An
 * approval that authorises three writes is that promise failing silently, and the only
 * evidence was three rows in somebody's Jira.
 */
class OneApprovalOneWriteTest {

    /** A write tool that counts how many times it actually reached the provider. */
    private static class CountingWrite implements Tool {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "jira.createIssue";
        }

        @Override
        public String description() {
            return "kayıt açar";
        }

        @Override
        public JsonNode schema() {
            var schema = Json.object();
            schema.put("type", "object");
            schema.putObject("properties").putObject("summary").put("type", "string");
            return schema;
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            var data = Json.object();
            data.put("key", "KAN-" + calls.incrementAndGet());
            return ToolResult.ok(data, 1, "replay");
        }
    }

    /** Says no to every verdict, so the step is always sent back. */
    private static class AlwaysRejects implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            if (LlmPurpose.VERIFY.equals(request.purpose())) {
                return new LlmResponse("{\"pass\":false,\"reason\":\"yer tutucu metin\"}",
                        100, 20, 0.000_01, "test:model", false);
            }
            return new LlmResponse("{\"summary\":\"GitHub Issue Başlığı\"}", 100, 20, 0.000_01,
                    "test:model", false);
        }

        @Override
        public String name() {
            return "always-rejects";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    @Test
    void one_approval_authorises_one_write_however_many_times_the_step_is_retried() {
        CountingWrite jira = new CountingWrite();
        ToolRegistry tools = new ToolRegistryImpl(List.of(jira));
        Clock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        LlmClient llm = new AlwaysRejects();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal =
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, new TestDoubles.RecordingEventPublisher(), journal, clock);

        Run run = Run.create("KAN'da kayıt aç", clock.now(), 1.0);
        Step step = Step.create(run.id(), 1, "Kayıt aç",
                AgentRole.toolAgent("jira.createIssue"), "jira.createIssue",
                Map.of("summary", "GitHub Issue Başlığı"));
        run.replaceSteps(List.of(step));
        runs.save(run);

        // It parks, because a write asks.
        coordinator.drive(run.id());
        assertThat(runs.findById(run.id()).orElseThrow().steps().get(0).status())
                .isEqualTo(StepStatus.AWAITING_APPROVAL);

        // The human says yes. Once. Exactly what RunService.approve does to the aggregate.
        Run parked = runs.findById(run.id()).orElseThrow();
        parked.steps().get(0).approve();
        parked.status(com.relay.domain.RunStatus.RUNNING);
        runs.save(parked);
        coordinator.drive(run.id());

        // The verifier rejects it every time, so the step goes round again — and it must
        // come back to the gate rather than straight back to the provider.
        assertThat(jira.calls.get())
                .as("one approval, one write — this was 3 on the live box")
                .isEqualTo(1);

        Step after = runs.findById(run.id()).orElseThrow().steps().get(0);
        assertThat(after.decision())
                .as("the approval was spent on the attempt it was given for")
                .isNotEqualTo(Decision.APPROVED);
        assertThat(after.status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
    }
}
