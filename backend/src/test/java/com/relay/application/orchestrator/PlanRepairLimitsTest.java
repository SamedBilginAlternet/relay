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
 * {@code PlanRepairTest} walks the repair down its happy path: a lookup goes in front of an
 * ungrounded write and the write then hits a record that really came back. The branches where
 * the repair <em>declines</em> had no test at all, and each of them decides what happens to a
 * run instead of ending it.
 *
 * <p>Three things are pinned here, all of them today's behaviour:
 *
 * <ul>
 *   <li>A provider with no search-style READ tool cannot be repaired — {@code
 *       ToolAgent.lookupToolFor} matches on the words "search" and "list" in a tool's name, so
 *       the day someone adds a READ tool called {@code jira.issue} the repair quietly stops
 *       happening. The run must still survive on provider feedback rather than die.</li>
 *   <li>A plan that already read from that provider is not repaired a second time. Nothing
 *       else stops the repair from looping.</li>
 *   <li>The repair, the provider retry and the verifier's send-back share one counter
 *       ({@code Step.MAX_RETRIES}). A repaired write therefore reaches the provider twice, not
 *       three times. That is a design decision, not an accident — but it was nowhere in
 *       writing, so a run could be spent on repairs and never get to fix the provider's own
 *       complaint.</li>
 * </ul>
 */
class PlanRepairLimitsTest {

    private static final String INVENTED_KEY = "RELAY-1";
    private static final String REAL_KEY = "KAN-42";

    /** A Jira write. Optionally refuses every call the way a provider does. */
    private static class UpdateIssueTool implements Tool {
        private final String error;
        int calls;

        UpdateIssueTool(String error) {
            this.error = error;
        }

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
            schema.putArray("required").add("issueKey").add("status");
            ObjectNode props = schema.putObject("properties");
            props.putObject("issueKey").put("type", "string");
            props.putObject("status").put("type", "string");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            return error == null
                    ? ToolResult.ok(Json.object().put("key", params.path("issueKey").asText()), 5, "live")
                    : ToolResult.error(error, 5, "live");
        }
    }

    /** The search-style READ the repair looks for. Returns one real record key. */
    private static class SearchIssuesTool implements Tool {
        int calls;

        @Override
        public String name() {
            return "jira.searchIssues";
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
            schema.putArray("required").add("jql");
            schema.putObject("properties").putObject("jql").put("type", "string");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            ObjectNode data = Json.object();
            data.putArray("issues").addObject().put("key", REAL_KEY).put("summary", "Ödeme servisi");
            return ToolResult.ok(data, 4, "replay");
        }
    }

    /** A second provider, so the ordering assertion has something to keep in place. */
    private static class PostMessageTool implements Tool {
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
            return ToolResult.ok(Json.object().put("ok", true), 3, "live");
        }
    }

    /**
     * The specialist: names the record it can actually see. Before the lookup ran there is
     * nothing to see, so it keeps inventing — which is the whole reason the repair exists.
     */
    private static class SpecialistLlm implements LlmClient {
        @Override
        public LlmResponse complete(LlmRequest request) {
            String content;
            if (LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                if (request.user().contains("TOOL: jira.searchIssues")) {
                    content = "{\"jql\":\"project = KAN AND status != Done\"}";
                } else if (request.user().contains("TOOL: slack.postMessage")) {
                    content = "{\"text\":\"" + REAL_KEY + " kaydı kapatıldı.\"}";
                } else {
                    content = request.user().contains(REAL_KEY)
                            ? "{\"issueKey\":\"" + REAL_KEY + "\",\"status\":\"Tamam\"}"
                            : "{\"issueKey\":\"" + INVENTED_KEY + "\",\"status\":\"Tamam\"}";
                }
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

    /**
     * Seeds the plan instead of asking the planner for one: what is under test is what the
     * coordinator does with a plan that has a hole in it, not how the hole gets planned.
     */
    private Rig drive(List<Tool> tools, List<RunService.SeedStep> seeds) {
        ToolRegistry registry = new ToolRegistryImpl(tools);
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new SpecialistLlm();
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

        // The goal names no record on purpose — an identifier in the parameters can then only
        // have come from the model or from an earlier step.
        Run run = Run.create("Bu kaydı kapat", Instant.parse("2026-07-31T09:00:00Z"), 1.0);
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

    /** Says yes at every gate, up to {@code times} — the human is not what is under test. */
    private void approve(Rig rig, int times) {
        for (int i = 0; i < times && rig.run().status() == RunStatus.AWAITING_APPROVAL; i++) {
            Step parked = List.copyOf(rig.run().steps()).stream()
                    .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                    .findFirst()
                    .orElse(null);
            if (parked == null) {
                return;
            }
            rig.service().approve(rig.run().id(), parked.id());
        }
    }

    private static RunService.SeedStep write(String key) {
        return new RunService.SeedStep("Kaydı kapat", "jira.updateIssue",
                Map.of("issueKey", key, "status", "Tamam"));
    }

    private static List<Step> steps(Rig rig) {
        return rig.run().steps();
    }

    @Test
    void a_write_whose_provider_has_no_search_tool_is_not_repaired_but_still_retried() {
        UpdateIssueTool update = new UpdateIssueTool(null);
        // The only Jira tool registered is the write itself: there is nothing to look up with.
        Rig rig = drive(List.of(update), List.of(write(INVENTED_KEY)));
        approve(rig, 1);

        assertThat(steps(rig)).as("no lookup could be inserted").hasSize(1);
        assertThat(update.calls).as("the invented key never reached Jira").isZero();
        // The run is alive and back in front of the human with freshly derived parameters,
        // rather than failed.
        assertThat(rig.run().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(steps(rig).get(0).status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
        assertThat(steps(rig).get(0).attempts()).isEqualTo(1);
        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content())
                        .contains("Araç hatayı gerekçesiyle döndürdü")
                        .contains(ToolAgent.UNGROUNDED));
    }

    @Test
    void a_plan_that_already_read_from_that_provider_is_not_repaired_twice() {
        UpdateIssueTool update = new UpdateIssueTool(null);
        SearchIssuesTool search = new SearchIssuesTool();
        Rig rig = drive(List.of(search, update), List.of(
                new RunService.SeedStep("Kayıtları ara", "jira.searchIssues", Map.of("jql", "project = KAN")),
                write(INVENTED_KEY)));
        approve(rig, 1);

        // A read of this provider already ran, so a second one would find nothing new and the
        // repair would have no end.
        assertThat(steps(rig)).hasSize(2);
        assertThat(steps(rig)).extracting(Step::toolName)
                .containsExactly("jira.searchIssues", "jira.updateIssue");
        assertThat(search.calls).as("the existing lookup ran once, and only once").isEqualTo(1);
        assertThat(steps(rig).get(1).attempts()).isEqualTo(1);
        assertThat(rig.run().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    /**
     * The number this test writes down: two. A repaired write gets two goes at the provider,
     * because the repair itself spent the first of the three the step is allowed.
     */
    @Test
    void the_repair_and_the_provider_retry_share_one_attempt_budget() {
        UpdateIssueTool update = new UpdateIssueTool("KAN-42 için 'Tamam' geçişi yok");
        Rig rig = drive(List.of(new SearchIssuesTool(), update), List.of(write(INVENTED_KEY)));
        approve(rig, 4);

        Step written = steps(rig).get(1);
        assertThat(steps(rig)).as("the repair happened").hasSize(2);
        assertThat(written.toolName()).isEqualTo("jira.updateIssue");
        assertThat(written.attempts()).isEqualTo(Step.MAX_RETRIES);
        assertThat(written.retriesExhausted()).isTrue();
        assertThat(update.calls).as("one of the three tries went to the repair").isEqualTo(2);
        assertThat(written.status()).isEqualTo(StepStatus.FAILED);
        assertThat(rig.run().status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void an_inserted_lookup_keeps_the_rest_of_the_plan_in_order() {
        Rig rig = drive(
                List.of(new SearchIssuesTool(), new UpdateIssueTool("geçiş yok"), new PostMessageTool()),
                List.of(write(INVENTED_KEY),
                        new RunService.SeedStep("Kanala haber ver", "slack.postMessage",
                                Map.of("text", REAL_KEY + " kaydı kapatıldı."))));
        approve(rig, 4);

        assertThat(steps(rig)).extracting(Step::toolName)
                .containsExactly("jira.searchIssues", "jira.updateIssue", "slack.postMessage");
        assertThat(steps(rig)).extracting(Step::ordinal).containsExactly(1, 2, 3);
    }
}
