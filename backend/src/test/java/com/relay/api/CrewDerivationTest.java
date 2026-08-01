package com.relay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.PolicyMode;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>The Ekip screen makes the product's loudest claim — "you have a crew" — and the only
 * thing that keeps it from being a slide is that no member on it was written by hand. Every
 * one is computed from the tool registry and the policy engine at the moment of the request
 * (docs/EKIP.md §5.5). That property is invisible in the code: a helpful person could add a
 * hard-coded "Muhasebe Ajanı" to {@code CrewRoster} tomorrow and every screen would still
 * render, so these tests are where the rule lives.
 *
 * <p>Three of them state it directly. A tool nobody has heard of produces a member with no
 * code change; a member whose provider has no credentials is still listed, marked idle rather
 * than hidden; and the crew holds nothing the registry does not, so a name cannot be smuggled
 * in from anywhere else. The fourth is the other half of §7.5: authority is read out of the
 * policy engine on every request, so forbidding a tool moves the member's counts without
 * anything here storing a second copy of the answer.
 */
class CrewDerivationTest {

    private TestDoubles.InMemoryPolicyRepository policies;
    private TestDoubles.InMemoryConnectionRepository connections;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        registry = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures),
                new SlackTool.ListChannels("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures),
                new GmailTool.ListToday("replay", fixtures, null)));
        policies = new TestDoubles.InMemoryPolicyRepository();
        connections = new TestDoubles.InMemoryConnectionRepository();
    }

    /** The acceptance criterion of #113, and the reason the endpoint is not a config file. */
    @Test
    void an_unknown_provider_becomes_a_crew_member_by_itself() {
        registry = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", new FixtureStore()),
                new FakeTool("notion.createPage", RiskLevel.WRITE)));

        JsonNode crew = body();
        JsonNode notion = member(crew, "notion-agent");

        assertThat(notion).as("a registered tool is the whole application form").isNotNull();
        assertThat(notion.get("provider").asText()).isEqualTo("notion");
        assertThat(notion.get("toolCount").asInt()).isEqualTo(1);
        assertThat(notion.get("ask").asInt()).isEqualTo(1);
        // And no Turkish anywhere near it: naming is the interface's job, so an id this build
        // has never seen reaches the screen as itself.
        assertThat(crew.toString()).doesNotContain("Uzman");
    }

    /**
     * Idle is a state, not an absence. Hiding the member would answer "which specialists do I
     * have" with a shorter list every time credentials expired.
     */
    @Test
    void a_member_whose_provider_has_no_connection_is_listed_and_marked_idle() {
        connections.save(Connection.of("jira", Map.of("baseUrl", "https://x.atlassian.net"), Instant.now()));

        JsonNode crew = body();

        assertThat(member(crew, "jira-agent").get("connected").asBoolean()).isTrue();
        assertThat(member(crew, "slack-agent").get("connected").asBoolean()).isFalse();
        assertThat(member(crew, "slack-agent").get("toolCount").asInt()).isEqualTo(2);
        // Gmail's credentials live under `google`, not under `gmail` — the member is named
        // after the tools it holds, the connection is looked up where it actually is.
        assertThat(member(crew, "gmail-agent").get("connectionProvider").asText()).isEqualTo("google");
        assertThat(member(crew, "gmail-agent").get("connected").asBoolean()).isFalse();

        // Connected first, so an idle member sinks rather than disappears.
        assertThat(ids(crew)).containsExactly("jira-agent", "gmail-agent", "slack-agent");
    }

    /** Everything on the screen came from the registry, and nothing else did. */
    @Test
    void the_crew_holds_no_tool_the_registry_does_not() {
        JsonNode crew = body();

        List<String> registered = registry.all().stream().map(Tool::name).toList();
        List<String> onScreen = crew.get("members").findValuesAsText("name");

        assertThat(onScreen).containsExactlyInAnyOrderElementsOf(registered);
        // The core five have no tools at all — they have a job. They are also the only names
        // in the payload that no tool produced, and there are exactly five of them.
        assertThat(crew.get("core").findValuesAsText("id"))
                .containsExactly("planner", "coordinator", "verifier", "policy", "cost");
        assertThat(crew.get("core").toString()).doesNotContain("\"tools\"");
    }

    /**
     * The same fact in two places is the failure mode this endpoint exists to avoid: the
     * counts are recomputed from the policy engine per request, so an operator's decision
     * shows up here without anything being copied.
     */
    @Test
    void forbidding_a_tool_moves_the_member_authority_without_a_second_store() {
        assertThat(member(body(), "slack-agent").get("ask").asInt()).isEqualTo(1);
        assertThat(member(body(), "slack-agent").get("forbidden").asInt()).isZero();

        new PolicyEngine(policies, registry).set("slack.postMessage", PolicyMode.FORBIDDEN);

        assertThat(member(body(), "slack-agent").get("ask").asInt()).isZero();
        assertThat(member(body(), "slack-agent").get("forbidden").asInt()).isEqualTo(1);
        assertThat(member(body(), "slack-agent").get("auto").asInt()).isEqualTo(1);
    }

    /**
     * The tier is not a label the screen chose. It is read from the same property
     * {@code GroqLlmClient} routes on, so the row cannot advertise a model the run will not use.
     */
    @Test
    void the_tier_follows_the_configured_split_rather_than_a_table_of_its_own() {
        JsonNode shipped = body();
        assertThat(core(shipped, "verifier").get("tier").asText()).isEqualTo("small");
        assertThat(core(shipped, "planner").get("tier").asText()).isEqualTo("large");
        assertThat(member(shipped, "jira-agent").get("tier").asText()).isEqualTo("large");
        // A member that never calls a model says nothing rather than "small".
        assertThat(core(shipped, "policy").get("tier").isNull()).isTrue();

        JsonNode moved = body("verify, tool_params");
        assertThat(member(moved, "jira-agent").get("tier").asText()).isEqualTo("small");
    }

    // ---- helpers -----------------------------------------------------------

    private JsonNode body() {
        return body("");
    }

    /** Built the way Spring builds it, so the property parsing is under test too. */
    private JsonNode body(String smallPurposes) {
        CrewController controller = new CrewController(registry, new PolicyEngine(policies, registry),
                connections, smallPurposes);
        return Json.mapper().valueToTree(controller.crew());
    }

    private static List<String> ids(JsonNode crew) {
        return crew.get("members").findValuesAsText("id");
    }

    private static JsonNode member(JsonNode crew, String id) {
        for (JsonNode member : crew.get("members")) {
            if (id.equals(member.path("id").asText())) {
                return member;
            }
        }
        return null;
    }

    private static JsonNode core(JsonNode crew, String id) {
        for (JsonNode member : crew.get("core")) {
            if (id.equals(member.path("id").asText())) {
                return member;
            }
        }
        throw new AssertionError("no core member " + id);
    }

    /** A provider nobody wrote a class for. Registered, therefore on the crew. */
    private record FakeTool(String name, RiskLevel risk) implements Tool {

        @Override
        public String description() {
            return "a tool from a provider this build has never heard of";
        }

        @Override
        public JsonNode schema() {
            var schema = Json.object();
            schema.put("type", "object");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            return ToolResult.ok(params, 1, "replay");
        }
    }
}
