package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Relay posted this to a real Slack channel while the Groq key was rate limited:
 * "Relay özeti — … Adımlar Relay tarafından yürütüldü; ayrıntılar zaman çizelgesinde."
 * The approval gate showed it and the user approved it, because it looks like a message
 * until you read it. Filler is now stopped before the provider call.
 */
class EmptyContentGateTest {

    /** Records whatever reaches the provider. */
    private static class SpyPoster implements com.relay.application.port.Tool {
        int calls;
        String lastText;

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
            calls++;
            lastText = params.path("text").asText();
            return ToolResult.ok(Json.object().put("ok", true), 3, "live");
        }
    }

    private record Rig(ToolAgent agent, SpyPoster tool) {
    }

    private Rig rig() {
        SpyPoster tool = new SpyPoster();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
        ToolAgent agent = new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(),
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock), clock);
        return new Rig(agent, tool);
    }

    private static Run runWith(Map<String, Object> params) {
        Run run = Run.create("Ekibe durumu bildir", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Slack'e yaz", AgentRole.COORDINATOR,
                "slack.postMessage", params)));
        return run;
    }

    @Test
    void template_text_never_reaches_the_provider() {
        Rig rig = rig();
        Run run = runWith(Map.of("channel", "#all-samed",
                "text", "Relay özeti — bir şeyler oldu\nAdımlar Relay tarafından yürütüldü;"
                        + " ayrıntılar zaman çizelgesinde."));

        StepOutcome outcome = rig.agent().execute(run, run.steps().get(0));

        assertThat(rig.tool().calls).isZero();
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).contains("içerik üretilemedi", "text");
    }

    @Test
    void an_empty_body_is_refused_too() {
        Rig rig = rig();
        Run run = runWith(Map.of("channel", "#all-samed", "text", "   "));

        // Blank fails the schema, so the specialist refills it — and with nothing found,
        // the fallback can only write "nothing found", which is not worth a channel post.
        StepOutcome outcome = rig.agent().execute(run, run.steps().get(0));

        assertThat(outcome.ok()).isFalse();
        assertThat(rig.tool().calls).isZero();
    }

    @Test
    void a_message_carrying_findings_goes_out() {
        Rig rig = rig();
        Run run = runWith(Map.of("channel", "#all-samed",
                "text", "3 blocker açık: KAN-4 (Devam Ediyor), KAN-7, KAN-9. İlk ikisi bugün bitmeli."));

        StepOutcome outcome = rig.agent().execute(run, run.steps().get(0));

        assertThat(outcome.ok()).isTrue();
        assertThat(rig.tool().calls).isEqualTo(1);
        assertThat(rig.tool().lastText).contains("KAN-4");
    }

    /** Reads are exempt: a search query is not a message to anyone. */
    @Test
    void the_gate_only_applies_to_writes() {
        assertThat(com.relay.application.text.Filler.looksLikeFiller("KAN-4 · KAN-7")).isFalse();
        assertThat(com.relay.application.text.Filler.looksLikeFiller("TODO: yaz")).isTrue();
    }
}
