package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.json.Json;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A live run posted to {@code #general} while {@code #all-samed} was configured on the
 * connection — Slack answered {@code channel_not_found}. The fallback for a blank channel
 * did not help, because the model was confidently wrong rather than silent. It can only
 * pick the user's channel if it is told what it is.
 */
class ConnectionSettingsPromptTest {

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

    private TestDoubles.ScriptedLlmClient run(Map<String, String> config) {
        ToolRegistry tools = new ToolRegistryImpl(List.of(new Poster()));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryConnectionRepository connections = new TestDoubles.InMemoryConnectionRepository();
        connections.save(Connection.of("slack", config, clock.now()));
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.TOOL_PARAMS, "{\"channel\":\"#all-samed\",\"text\":\"KAN-4 bende, başladım.\"}"));

        ToolAgent agent = new ToolAgent(tools, llm, connections,
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock), clock);
        Run runObj = Run.create("Ekibe bildir", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        // No channel in the draft: the specialist has to decide, which is when it guessed.
        runObj.replaceSteps(List.of(Step.create(runObj.id(), 1, "Slack'e yaz", AgentRole.COORDINATOR,
                "slack.postMessage", Map.of())));
        agent.execute(runObj, runObj.steps().get(0));
        return llm;
    }

    @Test
    void the_configured_channel_is_put_in_front_of_the_specialist() {
        String prompt = run(Map.of("botToken", "xoxb-secret-value", "defaultChannel", "#all-samed"))
                .of(LlmPurpose.TOOL_PARAMS).get(0).user();

        assertThat(prompt).contains("USER SETTINGS", "defaultChannel = #all-samed");
    }

    /** Settings are allow-listed by name, so a token cannot ride along into a prompt. */
    @Test
    void the_token_never_enters_the_prompt() {
        String prompt = run(Map.of("botToken", "xoxb-secret-value", "defaultChannel", "#all-samed"))
                .of(LlmPurpose.TOOL_PARAMS).get(0).user();

        assertThat(prompt).doesNotContain("xoxb-secret-value").doesNotContain("botToken");
    }

    @Test
    void nothing_is_added_when_the_connection_carries_only_credentials() {
        String prompt = run(Map.of("botToken", "xoxb-secret-value"))
                .of(LlmPurpose.TOOL_PARAMS).get(0).user();

        assertThat(prompt).doesNotContain("USER SETTINGS");
    }
}
