package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A live run asked Slack to post to the channel {@code {{steps[3].channel}}} and got
 * {@code channel_not_found} — a baffling error for a channel that exists, because the value
 * sent was never a channel name. Relay has no template engine: a marker that survives to the
 * provider call is a parameter the model declined to fill.
 */
class PlaceholderGateTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    /** Records what actually reaches the provider. */
    private static class SpyTool implements Tool {
        final String name;
        final RiskLevel risk;
        int calls;
        JsonNode lastParams;

        SpyTool(String name, RiskLevel risk) {
            this.name = name;
            this.risk = risk;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public RiskLevel risk() {
            return risk;
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
            lastParams = params;
            return ToolResult.ok(Json.object().put("ok", true), 2, "live");
        }
    }

    private ToolAgent agentFor(List<Tool> tools, TestDoubles.InMemoryConnectionRepository connections) {
        ToolRegistry registry = new ToolRegistryImpl(tools);
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(registry);
        return new ToolAgent(registry, llm, connections,
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock), clock);
    }

    private static Run runWith(String tool, Map<String, Object> params) {
        Run run = Run.create("Profil sayfası işini bildir",
                java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Adım", AgentRole.COORDINATOR, tool, params)));
        return run;
    }

    private TestDoubles.InMemoryConnectionRepository slackConnection(String defaultChannel) {
        TestDoubles.InMemoryConnectionRepository connections = new TestDoubles.InMemoryConnectionRepository();
        java.util.Map<String, String> config = new java.util.LinkedHashMap<>();
        config.put("botToken", "xoxb-test");
        if (defaultChannel != null) {
            config.put("defaultChannel", defaultChannel);
        }
        connections.save(Connection.of("slack", config, java.time.Instant.parse("2026-07-31T09:00:00Z")));
        return connections;
    }

    @Test
    void a_placeholder_channel_falls_back_to_the_configured_default() {
        var connections = slackConnection("#all-samed");
        ToolAgent agent = agentFor(List.of(new SlackTool.PostMessage("replay", FIXTURES)), connections);
        Run run = runWith("slack.postMessage", Map.of(
                "channel", "{{steps[3].channel}}",
                "text", "KAN-4 Profil sayfası yeniden tasarımı tamamlandı."));

        StepOutcome outcome = agent.execute(run, run.steps().get(0));

        assertThat(outcome.ok()).isTrue();
        assertThat(run.steps().get(0).params()).containsEntry("channel", "#all-samed");
    }

    /** Same for a channel the model left out entirely. */
    @Test
    void a_missing_channel_falls_back_too() {
        var connections = slackConnection("#all-samed");
        ToolAgent agent = agentFor(List.of(new SlackTool.PostMessage("replay", FIXTURES)), connections);
        Run run = runWith("slack.postMessage", Map.of(
                "channel", "", "text", "KAN-4 tamamlandı."));

        agent.execute(run, run.steps().get(0));

        assertThat(run.steps().get(0).params()).containsEntry("channel", "#all-samed");
    }

    @Test
    void without_a_default_the_placeholder_never_reaches_slack() {
        var connections = slackConnection(null);
        SpyTool spy = new SpyTool("slack.postMessage", RiskLevel.WRITE);
        ToolAgent agent = agentFor(List.of(spy), connections);
        Run run = runWith("slack.postMessage", Map.of("jql", "{{steps[3].channel}}"));

        StepOutcome outcome = agent.execute(run, run.steps().get(0));

        assertThat(spy.calls).isZero();
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).contains("çözülmemiş yer tutucu", "{{steps[3].channel}}");
    }

    /** Reads break the same way, and the provider's error explains it even less. */
    @Test
    void a_templated_read_parameter_is_stopped_as_well() {
        SpyTool spy = new SpyTool("jira.searchIssues", RiskLevel.READ);
        ToolAgent agent = agentFor(List.of(spy), new TestDoubles.InMemoryConnectionRepository());
        Run run = runWith("jira.searchIssues", Map.of("jql", "project = ${project} ORDER BY updated DESC"));

        assertThat(agent.execute(run, run.steps().get(0)).ok()).isFalse();
        assertThat(spy.calls).isZero();
    }

    /** Slack's own syntax uses angle brackets — flagging those would refuse real messages. */
    @Test
    void slack_link_syntax_is_not_a_placeholder() {
        assertThat(com.relay.application.text.Placeholder.unresolved(
                "<https://jira/KAN-4|KAN-4> tamamlandı, <@U123> bilgine")).isFalse();
        assertThat(com.relay.application.text.Placeholder.unresolved("{{steps[3].channel}}")).isTrue();
        assertThat(com.relay.application.text.Placeholder.unresolved("#all-samed")).isFalse();
    }

    /**
     * The model does not only leave placeholders — it invents plausible ones. A live run
     * tried #genel, then a channel id, then #general: three inventions, three
     * channel_not_found, while #all-samed sat configured on the connection.
     */
    @Test
    void an_invented_channel_is_replaced_by_the_configured_one() {
        var connections = slackConnection("#all-samed");
        ToolAgent agent = agentFor(List.of(new SlackTool.PostMessage("replay", FIXTURES)), connections);
        Run run = runWith("slack.postMessage", Map.of(
                "channel", "#genel", "text", "KAN-4 tamamlandı."));

        StepOutcome outcome = agent.execute(run, run.steps().get(0));

        assertThat(outcome.ok()).isTrue();
        assertThat(run.steps().get(0).params()).containsEntry("channel", "#all-samed");
    }

    /** A channel the user actually asked for is left alone. */
    @Test
    void a_channel_named_in_the_goal_survives() {
        var connections = slackConnection("#all-samed");
        ToolAgent agent = agentFor(List.of(new SlackTool.PostMessage("replay", FIXTURES)), connections);
        Run run = Run.create("Sonucu #dev-sprint kanalına yaz",
                java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(Step.create(run.id(), 1, "Adım", AgentRole.COORDINATOR,
                "slack.postMessage", Map.of("channel", "#dev-sprint", "text", "KAN-4 tamamlandı."))));

        agent.execute(run, run.steps().get(0));

        assertThat(run.steps().get(0).params()).containsEntry("channel", "#dev-sprint");
    }
}
