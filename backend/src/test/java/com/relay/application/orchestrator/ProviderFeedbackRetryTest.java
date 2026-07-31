package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Providers put the fix inside the rejection — "'Blocked' geçişi yok, mümkün olanlar:
 * Yapılacaklar, Devam Ediyor, İncelemede, Tamam". Ending the run there throws away an
 * answer the specialist could act on. It gets one more go, and if it writes, the human
 * sees the new parameters first.
 */
class ProviderFeedbackRetryTest {

    /** Refuses "Blocked" the way Jira does, accepts anything else. */
    private static class PickyTool implements Tool {
        int calls;

        @Override
        public String name() {
            return "jira.updateIssue";
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
            schema.putArray("required").add("status");
            schema.putObject("properties").putObject("status").put("type", "string");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            String status = params.path("status").asText();
            if ("Blocked".equals(status)) {
                return ToolResult.error("KAN-4 için 'Blocked' geçişi yok. Bu kayıtta şu an mümkün"
                        + " olanlar: Devam Ediyor, Tamam", 5, "live");
            }
            return ToolResult.ok(Json.object().put("status", status), 5, "live");
        }
    }

    private static class FeedbackAwareLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            String content = LlmPurpose.TOOL_PARAMS.equals(request.purpose())
                    ? (request.user().contains("Devam Ediyor")
                            ? "{\"status\":\"Devam Ediyor\"}"
                            : "{\"status\":\"Blocked\"}")
                    : "{\"pass\":true,\"reason\":\"tamam\"}";
            return new LlmResponse(content, 10, 5, 0.0001, "scripted", false);
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    private record Rig(RunService service, TestDoubles.InMemoryRunRepository runs, PickyTool tool) {
    }

    private Rig rig() {
        PickyTool tool = new PickyTool();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new FeedbackAwareLlm();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        return new Rig(new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0), runs, tool);
    }

    /** Builds a run parked on one approved write step, bypassing the planner. */
    private Run parkedWrite(Rig rig) {
        Run run = Run.create("KAN-4 kaydını ilerlet", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step step = Step.create(run.id(), 1, "Durumu güncelle", "jira-uzmani",
                "jira.updateIssue", java.util.Map.of("status", "Blocked"));
        run.replaceSteps(List.of(step));
        rig.runs().save(run);
        rig.service().driveNow(run.id());
        return run;
    }

    @Test
    void a_rejected_write_comes_back_for_approval_with_new_parameters() {
        Rig rig = rig();
        Run run = parkedWrite(rig);
        Step step = run.steps().get(0);

        rig.service().approve(run.id(), step.id());

        assertThat(rig.tool().calls).as("the refused call happened once").isEqualTo(1);
        assertThat(step.status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(step.params()).containsEntry("status", "Devam Ediyor");
    }

    @Test
    void the_second_approval_runs_the_corrected_call() {
        Rig rig = rig();
        Run run = parkedWrite(rig);
        Step step = run.steps().get(0);

        rig.service().approve(run.id(), step.id());
        rig.service().approve(run.id(), step.id());

        assertThat(rig.tool().calls).isEqualTo(2);
        assertThat(step.status()).isEqualTo(StepStatus.DONE);
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }

    /** The provider's own words reach the user, not a generic failure. */
    @Test
    void the_rejection_is_quoted_in_the_journal() {
        Rig rig = rig();
        Run run = parkedWrite(rig);
        rig.service().approve(run.id(), run.steps().get(0).id());

        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).contains("mümkün olanlar"));
    }
}
