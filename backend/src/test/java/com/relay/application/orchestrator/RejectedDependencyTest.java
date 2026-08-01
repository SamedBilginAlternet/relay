package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a refusal is worth is decided by what happens after it.
 *
 * <p>Live (run {@code 3ed985ff}): the person rejected "Jira kaydı aç" with a reason — this is
 * a fault report, not a work request — and the next card asked them to approve posting
 * <em>"Yeni iş talebi var"</em> to the team channel. One more press and the team would have
 * been told about a record nobody created. The gate says "read what is about to be sent";
 * that card was unreadable in the only sense that matters, because the sentence on it had
 * stopped being true one step earlier.
 */
class RejectedDependencyTest {

    /** A Jira write that records whether it ever ran. */
    private static class CreateIssueTool implements Tool {
        int calls;

        @Override
        public String name() {
            return "jira.createIssue";
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
            schema.putArray("required").add("projectKey").add("summary");
            ObjectNode props = schema.putObject("properties");
            props.putObject("projectKey").put("type", "string");
            props.putObject("summary").put("type", "string");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            return ToolResult.ok(Json.object().put("issueKey", "KAN-42"), 5, "live");
        }
    }

    /** The announcement. The point of the test is that this never runs. */
    private static class PostMessageTool implements Tool {
        int calls;

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
            schema.putArray("required").add("text");
            schema.putObject("properties").putObject("text").put("type", "string");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            return ToolResult.ok(Json.object().put("ok", true), 3, "live");
        }
    }

    /** A read, to show that a refusal does not switch the whole run off. */
    private static class ListChannelsTool implements Tool {
        int calls;

        @Override
        public String name() {
            return "slack.listChannels";
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            ObjectNode data = Json.object();
            data.putArray("channels").addObject().put("name", "#all-samed");
            return ToolResult.ok(data, 2, "replay");
        }
    }

    private static class ScriptedLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            String content;
            if (LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                content = request.user().contains("TOOL: slack.postMessage")
                        ? "{\"text\":\"Yeni iş talebi var\"}"
                        : "{\"projectKey\":\"KAN\",\"summary\":\"Ödeme adımında hata\"}";
            } else {
                content = "{\"pass\":true,\"reason\":\"tamam\"}";
            }
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

    private record Rig(RunService service, Run run) {
    }

    /** Seeds the plan: what the planner would have written is not what is under test. */
    private Rig drive(List<Tool> tools, List<RunService.SeedStep> seeds) {
        ToolRegistry registry = new ToolRegistryImpl(tools);
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new ScriptedLlm();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, registry, costMeter, journal),
                new ToolAgent(registry, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), registry),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0);

        Run run = Run.create("Bu mailden kayıt aç ve ekibe haber ver",
                Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        List<Step> steps = new ArrayList<>();
        int ordinal = 0;
        for (RunService.SeedStep seed : seeds) {
            steps.add(Step.create(run.id(), ++ordinal, seed.title(),
                    AgentRole.toolAgent(seed.toolName()), seed.toolName(), seed.params()));
        }
        run.replaceSteps(steps);
        runs.save(run);
        service.driveNow(run.id());
        return new Rig(service, run);
    }

    private static Step parked(Run run) {
        return run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step is waiting on a person"));
    }

    private static final RunService.SeedStep CREATE =
            new RunService.SeedStep("Jira kaydı aç", "jira.createIssue",
                    Map.of("projectKey", "KAN", "summary", "Ödeme adımında hata"));
    private static final RunService.SeedStep ANNOUNCE =
            new RunService.SeedStep("İlgili kanaldan ekibe haber ver", "slack.postMessage",
                    Map.of("text", "Yeni iş talebi var"));

    @Test
    void a_rejected_write_does_not_announce_itself() {
        CreateIssueTool create = new CreateIssueTool();
        PostMessageTool announce = new PostMessageTool();
        Rig rig = drive(List.of(create, announce), List.of(CREATE, ANNOUNCE));

        rig.service().reject(rig.run().id(), parked(rig.run()).id(),
                "Bu bir hata bildirimi, iş talebi değil.");

        Step post = rig.run().steps().get(1);
        assertThat(create.calls).as("the record was never opened").isZero();
        assertThat(announce.calls).as("so it was never announced either").isZero();
        assertThat(post.status()).as("and nobody was asked to approve announcing it")
                .isEqualTo(StepStatus.REJECTED);
        assertThat(post.rejectReason()).contains("1. adım reddedildi");
        assertThat(rig.run().status()).isEqualTo(RunStatus.FAILED);
    }

    /** The refusal has to be visible as a refusal, not as a step that quietly never ran. */
    @Test
    void the_trail_says_which_step_the_refusal_stopped() {
        Rig rig = drive(List.of(new CreateIssueTool(), new PostMessageTool()),
                List.of(CREATE, ANNOUNCE));

        rig.service().reject(rig.run().id(), parked(rig.run()).id(), "iş talebi değil");

        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content())
                        .contains("Adım reddedildi")
                        .contains("dayandığı 1. adım reddedildi")
                        .contains("Jira kaydı aç"));
    }

    /**
     * Reading is not writing. A refused write stops the run from telling anyone anything, but
     * a read afterwards changes nothing outside Relay and leaves the closing summary better
     * informed than an empty timeline would.
     */
    @Test
    void a_read_after_the_refusal_still_runs() {
        ListChannelsTool read = new ListChannelsTool();
        PostMessageTool announce = new PostMessageTool();
        Rig rig = drive(List.of(new CreateIssueTool(), read, announce),
                List.of(CREATE,
                        new RunService.SeedStep("Kanalları listele", "slack.listChannels", Map.of()),
                        ANNOUNCE));

        rig.service().reject(rig.run().id(), parked(rig.run()).id(), "iş talebi değil");

        assertThat(read.calls).as("the read ran").isEqualTo(1);
        assertThat(announce.calls).as("the write did not").isZero();
        assertThat(rig.run().steps().get(1).status()).isEqualTo(StepStatus.DONE);
        assertThat(rig.run().steps().get(2).status()).isEqualTo(StepStatus.REJECTED);
    }
}
