package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The approval gate is the product's central claim, and it was asking people to sign a
 * blank page: parameters were derived when the step ran, i.e. after the human said yes.
 * Live, a Slack step sat waiting with no channel and no message text on screen.
 */
class ApprovalShowsParamsTest {

    private static class Poster implements com.relay.application.port.Tool {
        @Override
        public String name() {
            return "slack.postMessage";
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("channel").add("text");
            ObjectNode props = schema.putObject("properties");
            props.putObject("channel").put("type", "string");
            props.putObject("text").put("type", "string");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            return ToolResult.ok(Json.object().put("ok", true), 2, "live");
        }
    }

    @Test
    void the_human_sees_the_message_before_approving_it() {
        ToolRegistry tools = new ToolRegistryImpl(List.of(new Poster()));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.TOOL_PARAMS,
                "{\"channel\":\"#all-samed\",\"text\":\"KAN-4 bende, bugün bitiyor.\"}"));
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);

        Run run = Run.create("Ekibe bildir", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        // The planner left the parameters to the specialist — the common case.
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Slack'e yaz", "slack-agent",
                "slack.postMessage", Map.of())));
        runs.save(run);
        service.driveNow(run.id());

        Step parked = run.steps().get(0);
        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(parked.params())
                .as("the gate shows what will actually be sent")
                .containsEntry("channel", "#all-samed")
                .containsEntry("text", "KAN-4 bende, bugün bitiyor.");
    }

    /** And the work is not paid for twice: the approved call reuses the prepared draft. */
    @Test
    void preparing_early_does_not_add_a_second_model_call() {
        ToolRegistry tools = new ToolRegistryImpl(List.of(new Poster()));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.TOOL_PARAMS, "{\"channel\":\"#all-samed\",\"text\":\"Hazır.\"}",
                LlmPurpose.VERIFY, "{\"pass\":true,\"reason\":\"tamam\"}"));
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);

        Run run = Run.create("Ekibe bildir", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Slack'e yaz", "slack-agent",
                "slack.postMessage", Map.of())));
        runs.save(run);
        service.driveNow(run.id());
        service.approve(run.id(), run.steps().get(0).id());

        assertThat(llm.of(LlmPurpose.TOOL_PARAMS))
                .as("prepared once at the gate, reused when it runs")
                .hasSize(1);
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }
}
