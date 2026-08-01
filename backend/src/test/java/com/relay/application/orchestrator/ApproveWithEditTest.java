package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The approval gate used to be binary, and "almost right" was its most common case: the
 * wrong channel, a missing sentence, a status name the project does not have. Paying for
 * that with a rejection, a written reason and another model turn asked the human to argue
 * with the agent about a value they already knew. So the gate takes the correction.
 *
 * <p>What these tests protect is the half that is easy to lose: once a person has typed a
 * value, nothing downstream may quietly replace it — not the specialist's next model turn,
 * not the channel the connection would have preferred — and the guards that stand between
 * a parameter and a provider must still stand.
 */
class ApproveWithEditTest {

    /** Slack, near enough: a channel, a message, and a record of what it was handed. */
    private static class Poster implements Tool {
        JsonNode lastCall;
        int calls;
        /** Set to give the tool a connection default, the way the real Slack tool has one. */
        String defaultChannel;

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
            ObjectNode text = props.putObject("text");
            text.put("type", "string");
            text.put("minLength", 1);
            props.putObject("threadTs").put("type", "string");
            return schema;
        }

        @Override
        public JsonNode withDefaults(JsonNode params, Connection connection) {
            if (defaultChannel == null || !params.isObject()
                    || !params.path("channel").asText("").isBlank()) {
                return params;
            }
            return ((ObjectNode) params).deepCopy().put("channel", defaultChannel);
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            lastCall = params.deepCopy();
            return ToolResult.ok(Json.object().put("ok", true), 3, "live");
        }
    }

    private record Rig(RunService service, TestDoubles.InMemoryRunRepository runs, Poster tool,
                       TestDoubles.ScriptedLlmClient llm) {
    }

    private Rig rig() {
        Poster tool = new Poster();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.TOOL_PARAMS, "{\"channel\":\"#yanlis-kanal\",\"text\":\"KAN-4 bende.\"}",
                LlmPurpose.VERIFY, "{\"pass\":true,\"reason\":\"tamam\"}"));
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        return new Rig(new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools),
                runs, tool, llm);
    }

    /** A run parked on one Slack write, with the parameters the specialist proposed. */
    private Run parked(Rig rig) {
        Run run = Run.create("Ekibe KAN-4'ü bildir", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Slack'e yaz", "slack-uzmani",
                "slack.postMessage", Map.of())));
        rig.runs().save(run);
        rig.service().driveNow(run.id());
        return run;
    }

    @Test
    void the_value_the_user_typed_is_the_value_the_provider_gets() {
        Rig rig = rig();
        Run run = parked(rig);
        Step step = run.steps().get(0);
        assertThat(step.params()).containsEntry("channel", "#yanlis-kanal");

        rig.service().approve(run.id(), step.id(), Map.of("channel", "#dogru-kanal"), "qa@relay.dev");

        assertThat(rig.tool().lastCall.path("channel").asText()).isEqualTo("#dogru-kanal");
        assertThat(rig.tool().lastCall.path("text").asText())
                .as("a field nobody touched keeps the value that was on screen")
                .isEqualTo("KAN-4 bende.");
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }

    /** The whole point of the feature: one gate, one approval, no extra round trip. */
    @Test
    void correcting_a_parameter_costs_no_second_model_call() {
        Rig rig = rig();
        Run run = parked(rig);
        int before = rig.llm().of(LlmPurpose.TOOL_PARAMS).size();

        rig.service().approve(run.id(), run.steps().get(0).id(),
                Map.of("channel", "#dogru-kanal"), "qa@relay.dev");

        assertThat(rig.llm().of(LlmPurpose.TOOL_PARAMS)).hasSize(before);
        assertThat(rig.tool().calls).isEqualTo(1);
    }

    /** "kim, neyi, neden": the field, both values and the person, on one line. */
    @Test
    void the_trail_records_the_field_both_values_and_who_changed_it() {
        Rig rig = rig();
        Run run = parked(rig);

        rig.service().approve(run.id(), run.steps().get(0).id(),
                Map.of("channel", "#dogru-kanal"), "ayse@sirket.com");

        assertThat(run.messages()).anySatisfy(message -> assertThat(message.content())
                .contains("düzenlendi")
                .contains("ayse@sirket.com")
                .contains("channel")
                .contains("#yanlis-kanal")
                .contains("#dogru-kanal"));
    }

    @Test
    void approving_without_touching_anything_behaves_exactly_as_before() {
        Rig rig = rig();
        Run run = parked(rig);
        Step step = run.steps().get(0);

        rig.service().approve(run.id(), step.id(), Map.of("channel", "#yanlis-kanal"), "qa@relay.dev");

        assertThat(step.paramsLocked()).as("nothing changed, so nothing is locked").isFalse();
        assertThat(run.messages()).noneSatisfy(message ->
                assertThat(message.content()).contains("düzenlendi"));
        assertThat(rig.tool().lastCall.path("channel").asText()).isEqualTo("#yanlis-kanal");
    }

    // ---- refused edits ----------------------------------------------------

    @Test
    void an_edit_that_fails_the_schema_leaves_the_step_at_the_gate() {
        Rig rig = rig();
        Run run = parked(rig);
        Step step = run.steps().get(0);

        assertThatThrownBy(() -> rig.service().approve(run.id(), step.id(),
                Map.of("text", ""), "qa@relay.dev"))
                .isInstanceOf(RunService.InvalidParams.class)
                .satisfies(thrown -> assertThat(((RunService.InvalidParams) thrown).fields())
                        .containsKey("text"));

        assertThat(step.status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
        assertThat(step.params()).containsEntry("text", "KAN-4 bende.");
        assertThat(rig.tool().calls).isZero();
    }

    /** The error names the field, in the language of the person reading the screen. */
    @Test
    void a_field_that_the_tool_does_not_have_is_refused_by_name() {
        Rig rig = rig();
        Run run = parked(rig);

        assertThatThrownBy(() -> rig.service().approve(run.id(), run.steps().get(0).id(),
                Map.of("kanal", "#dogru-kanal"), "qa@relay.dev"))
                .isInstanceOf(RunService.InvalidParams.class)
                .satisfies(thrown -> assertThat(((RunService.InvalidParams) thrown).fields())
                        .containsEntry("kanal", "Bu araçta böyle bir parametre yok."));
    }

    @Test
    void a_wrong_type_is_refused_before_the_tool_is_called() {
        Rig rig = rig();
        Run run = parked(rig);

        assertThatThrownBy(() -> rig.service().approve(run.id(), run.steps().get(0).id(),
                Map.of("channel", Map.of("id", "C123")), "qa@relay.dev"))
                .isInstanceOf(RunService.InvalidParams.class)
                .satisfies(thrown -> assertThat(((RunService.InvalidParams) thrown).fields())
                        .containsEntry("channel", "Bu alan metin olmalı."));
        assertThat(rig.tool().calls).isZero();
    }

    // ---- the guards still stand -------------------------------------------

    /**
     * Relay has no template engine, so {@code {{steps[3].channel}}} is not an address —
     * whoever typed it, model or human. Slack answered {@code channel_not_found} for one
     * of these once, which reads as "your channel is gone" for a channel that is fine.
     *
     * <p>It used to be caught in front of the provider, a model round after the 200: the
     * approval was accepted, written into the trail, and undone. Now the edit is refused
     * where the schema errors are, so the answer arrives under the box that caused it.
     */
    @Test
    void a_placeholder_typed_by_a_human_still_never_reaches_the_provider() {
        Rig rig = rig();
        Run run = parked(rig);

        assertThatThrownBy(() -> rig.service().approve(run.id(), run.steps().get(0).id(),
                Map.of("channel", "{{steps[3].channel}}"), "qa@relay.dev"))
                .isInstanceOf(RunService.InvalidParams.class)
                .satisfies(e -> assertThat(((RunService.InvalidParams) e).fields())
                        .containsKey("channel"));

        assertThat(rig.tool().calls).as("the guard fired before the call").isZero();
        assertThat(run.steps().get(0).status())
                .as("a refused edit changes nothing")
                .isEqualTo(com.relay.domain.StepStatus.AWAITING_APPROVAL);
        assertThat(run.messages())
                .as("and an approval that was refused is not written down as one")
                .noneSatisfy(message -> assertThat(message.content()).startsWith("Onaylandı"));
    }

    /**
     * Nor may the configured default quietly take an edited address away again.
     *
     * <p>The address guard exists because a model kept inventing channels while a real one
     * sat configured — but it decides "this value came from nowhere" by looking for it in
     * the goal, and a channel the user has just typed is nowhere near the goal either.
     */
    @Test
    void an_edited_channel_is_not_replaced_by_the_connection_default() {
        Rig rig = rig();
        rig.tool().defaultChannel = "#varsayilan";
        Run run = parked(rig);
        assertThat(run.steps().get(0).params())
                .as("the gate itself still corrects an address nobody can vouch for")
                .containsEntry("channel", "#varsayilan");

        rig.service().approve(run.id(), run.steps().get(0).id(),
                Map.of("channel", "#hedefte-gecmeyen-kanal"), "qa@relay.dev");

        assertThat(rig.tool().lastCall.path("channel").asText()).isEqualTo("#hedefte-gecmeyen-kanal");
    }

    // ---- the model does not get the last word -----------------------------

    /** Jira, refusing a transition the way Jira does — and naming the ones it allows. */
    private static class PickyTool implements Tool {
        final List<String> seen = new java.util.ArrayList<>();

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
            String status = params.path("status").asText();
            seen.add(status);
            if ("Blocked".equals(status)) {
                return ToolResult.error("KAN-4 için 'Blocked' geçişi yok. Mümkün olanlar:"
                        + " Devam Ediyor, Tamam", 5, "live");
            }
            return ToolResult.ok(Json.object().put("status", status), 5, "live");
        }
    }

    /** Answers "Devam Ediyor" once it has seen the provider's rejection quoted back at it. */
    private static class FeedbackAwareLlm implements LlmClient {
        int paramCalls;

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (!LlmPurpose.TOOL_PARAMS.equals(request.purpose())) {
                return new LlmResponse("{\"pass\":true,\"reason\":\"tamam\"}", 10, 5, 0.0001, "scripted", false);
            }
            paramCalls++;
            String content = request.user().contains("Devam Ediyor")
                    ? "{\"status\":\"Devam Ediyor\"}"
                    : "{\"status\":\"Blocked\"}";
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

    /**
     * The trap the whole {@code paramsLocked} flag exists for.
     *
     * <p>A step the provider has already refused carries {@code lastProviderError}, and that
     * alone sends {@code finaliseParams} back to the model even when the draft is perfectly
     * valid — which is exactly the moment a human is most likely to have fixed the value by
     * hand. Without the flag the specialist's answer lands on top of theirs and the call
     * goes out with a status they never chose.
     */
    @Test
    void a_hand_written_value_survives_a_step_the_provider_already_refused() {
        PickyTool tool = new PickyTool();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        FeedbackAwareLlm llm = new FeedbackAwareLlm();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);

        Run run = Run.create("KAN-4 kaydını ilerlet", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Durumu güncelle", "jira-uzmani",
                "jira.updateIssue", Map.of("status", "Blocked"))));
        runs.save(run);
        service.driveNow(run.id());
        Step step = run.steps().get(0);

        // First approval sends "Blocked", the provider refuses, the step comes back with the
        // specialist's correction — and the human overrules it with a third value.
        service.approve(run.id(), step.id());
        assertThat(step.params()).containsEntry("status", "Devam Ediyor");
        int callsBefore = llm.paramCalls;

        service.approve(run.id(), step.id(), Map.of("status", "Tamam"), "qa@relay.dev");

        assertThat(tool.seen).as("the model never overwrote the human's value")
                .containsExactly("Blocked", "Tamam");
        assertThat(llm.paramCalls).as("and was not asked again").isEqualTo(callsBefore);
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }

    /**
     * And the lock is not a life sentence: if the provider refuses the human's value too,
     * the specialist is allowed to propose the next one, or the step would sit at the gate
     * showing a value already known to be impossible.
     */
    @Test
    void a_refused_hand_written_value_hands_the_next_try_back_to_the_specialist() {
        PickyTool tool = new PickyTool();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        FeedbackAwareLlm llm = new FeedbackAwareLlm();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);

        Run run = Run.create("KAN-4 kaydını ilerlet", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Durumu güncelle", "jira-uzmani",
                "jira.updateIssue", Map.of("status", "Devam Ediyor"))));
        runs.save(run);
        service.driveNow(run.id());
        Step step = run.steps().get(0);

        service.approve(run.id(), step.id(), Map.of("status", "Blocked"), "qa@relay.dev");

        assertThat(step.status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
        assertThat(step.paramsLocked()).isFalse();
        assertThat(step.params()).containsEntry("status", "Devam Ediyor");
    }
}
