package com.relay.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Run;
import com.relay.domain.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Goal in, ordered steps out. Nothing else.
 *
 * <p>The model answers under a JSON schema; anything unparseable degrades to a
 * single reasoning step rather than blowing up the run.
 */
public class Planner {

    private static final int MAX_STEPS = 8;

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final CostMeter costMeter;
    private final AgentJournal journal;

    public Planner(LlmClient llm, ToolRegistry tools, CostMeter costMeter, AgentJournal journal) {
        this.llm = llm;
        this.tools = tools;
        this.costMeter = costMeter;
        this.journal = journal;
    }

    /** Builds the crew and the step list, mutating the run in place. */
    public List<Step> plan(Run run) {
        journal.say(run, null, AgentRole.USER, AgentRole.PLANNER, run.goal());

        LlmRequest request = LlmRequest.of(
                LlmPurpose.PLAN,
                systemPrompt(),
                userPrompt(run),
                schema(),
                Map.of("goal", run.goal(), "tools", toolNames()));

        LlmResponse response = llm.complete(request);
        costMeter.record(run, null, response.totalTokens(), response.costUsd());

        List<Step> steps = parse(run, response.content());
        if (steps.isEmpty()) {
            steps = List.of(Step.create(run.id(), 1, "Hedefi özetle", AgentRole.COORDINATOR, null,
                    Map.of("goal", run.goal())));
        }
        run.replaceSteps(steps);

        journal.say(run, null, AgentRole.PLANNER, AgentRole.COORDINATOR,
                steps.size() + " adımlık plan hazır: " + summary(steps));
        return steps;
    }

    private String summary(List<Step> steps) {
        List<String> titles = new ArrayList<>();
        steps.forEach(s -> titles.add(s.ordinal() + ") " + s.title()
                + (s.toolName() == null ? "" : " [" + s.toolName() + "]")));
        return String.join(" · ", titles);
    }

    private List<Step> parse(Run run, String content) {
        List<Step> steps = new ArrayList<>();
        JsonNode root = Json.extract(content);
        if (root == null) {
            return steps;
        }
        JsonNode array = root.isArray() ? root : root.path("steps");
        if (!array.isArray()) {
            return steps;
        }
        int ordinal = 0;
        for (JsonNode node : array) {
            if (++ordinal > MAX_STEPS) {
                break;
            }
            String title = node.path("title").asText("Adım " + ordinal);
            String toolName = text(node, "toolName");
            if (toolName != null && tools.find(toolName).isEmpty()) {
                // A hallucinated tool becomes a reasoning step instead of a hard failure.
                toolName = null;
            }
            String role = node.path("role").asText(null);
            if (role == null || role.isBlank()) {
                role = AgentRole.toolAgent(toolName);
            }
            Map<String, Object> params = Json.toMap(node.get("params"));
            steps.add(Step.create(run.id(), ordinal, title, role, toolName, params));
        }
        return steps;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText();
        return raw == null || raw.isBlank() || "null".equals(raw) ? null : raw;
    }

    private List<String> toolNames() {
        List<String> names = new ArrayList<>();
        tools.all().forEach(t -> names.add(t.name()));
        return names;
    }

    private String systemPrompt() {
        return """
                You are the Planner of Relay, a multi-agent workflow runner for knowledge workers.
                Turn the user's goal into an ordered, minimal list of executable steps.
                Rules:
                - Use ONLY the tools listed. If a step needs no tool, set toolName to null.
                - Every tool step must carry draft params that fit that tool's schema.
                - NEVER invent an identifier — issue keys, pull request numbers, message ids.
                  Use one only if the goal spells it out. Otherwise the plan MUST start with a
                  search/list step, and the writing step depends on what that step found.
                - Prefer reading before writing. Keep the plan under 6 steps.
                - A step that writes text for a human — a Slack message, a Jira description,
                  a PR comment — must come AFTER the step that gathers the facts, and its
                  title must say what that text is about. Never plan "özet gönder" as the
                  first step: there is nothing to summarise yet.
                - User-facing text is written in the language of the goal (Turkish goal →
                  Turkish message). Titles too.
                - Answer with JSON only, matching the given schema. No prose.
                """;
    }

    private String userPrompt(Run run) {
        StringBuilder sb = new StringBuilder();
        sb.append("GOAL:\n").append(run.goal()).append("\n\nAVAILABLE TOOLS:\n");
        for (Tool tool : tools.all()) {
            sb.append("- ").append(tool.name())
                    .append(" (risk=").append(tool.risk().wire()).append("): ")
                    .append(tool.description())
                    .append("\n  params schema: ").append(tool.schema().toString())
                    .append('\n');
        }
        sb.append("\nAnswer JSON: {\"steps\":[{\"title\":\"…\",\"role\":\"…\",\"toolName\":\"…|null\",\"params\":{…}}]}");
        return sb.toString();
    }

    /** JSON schema the plan response must satisfy. */
    public static JsonNode schema() {
        ObjectNode step = Json.object();
        step.put("type", "object");
        ArrayNode required = step.putArray("required");
        required.add("title");
        ObjectNode props = step.putObject("properties");
        props.putObject("title").put("type", "string");
        props.putObject("role").put("type", "string");
        props.putObject("toolName").put("type", "string");
        props.putObject("params").put("type", "object");

        ObjectNode root = Json.object();
        root.put("type", "object");
        root.putArray("required").add("steps");
        ObjectNode rootProps = root.putObject("properties");
        ObjectNode stepsNode = rootProps.putObject("steps");
        stepsNode.put("type", "array");
        stepsNode.set("items", step);
        return root;
    }

    /** Exposed for tests / debugging. */
    public Map<String, Object> describe() {
        return new LinkedHashMap<>(Map.of("llm", llm.name(), "tools", toolNames()));
    }
}
