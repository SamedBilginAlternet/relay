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
 * <p>The model answers under a JSON schema. An answer that is not a plan stops the run;
 * a plan that is legitimately empty degrades to one reasoning step.
 */
public class Planner {

    /**
     * The model's answer was not a plan.
     *
     * <p>WHY THIS THROWS NOW. It used to degrade to a single "Hedefi özetle" step, which
     * ran, verified — "Hedef özetlenmiştir" — and closed the run as **Tamamlandı**.
     * Measured on the live box on 2026-08-01, with every Groq key at its daily wall and
     * the paid tier answering: two of three goals that named a mailbox and a Jira project
     * came back as one-step runs that touched neither, and both reported success. The
     * goal "maillerime bak, hata bildirimi olanlar için KAN'da kayıt aç" produced
     * `1 adımlık plan hazır: 1) Hedefi özetle` and a green tick.
     *
     * <p>A product whose whole claim is that you can see what it did must not be able to
     * report success having done nothing. Failing costs a rerun; the old behaviour cost
     * the reader's belief that a green run means anything.
     */
    public static class PlanUnreadableException extends RuntimeException {
        public PlanUnreadableException(String message) {
            super(message);
        }
    }

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

        if (!readable(response.content())) {
            throw new PlanUnreadableException(
                    "Plan kurulamadı: modelin yanıtı adım listesi değil. Yeniden dene — "
                    + "sağlayıcı kotası dolduğunda yedek model bu biçimi tutturamıyor.");
        }
        List<Step> steps = parse(run, response.content());
        if (steps.isEmpty()) {
            /*
              A plan that parsed and holds no steps is the model saying there is nothing to
              run — "dur", "çözümünü açıklama". That is a real answer and it gets a real
              step. It is only the unreadable answer above that is a defect.
            */
            steps = List.of(Step.create(run.id(), 1, "Hedefi özetle", AgentRole.COORDINATOR, null,
                    Map.of("goal", run.goal())));
        }
        run.replaceSteps(steps);

        journal.say(run, null, AgentRole.PLANNER, AgentRole.COORDINATOR,
                steps.size() + " adımlık plan hazır: " + summary(steps));
        return steps;
    }

    /** The crew, as the timeline names them. Anything else is not a name, it is noise. */
    private static final java.util.Set<String> CREW = java.util.Set.of(
            AgentRole.USER, AgentRole.PLANNER, AgentRole.COORDINATOR,
            AgentRole.VERIFIER, AgentRole.POLICY, AgentRole.COST);

    /**
     * Who is doing this step — decided by the tool, not by the model.
     *
     * <p>The model kept writing {@code "role": "assistant"}, which is OpenAI's word for the
     * side of a chat it is speaking on and means nothing here. It was taken at face value, so
     * every row on the flow panel and every line in the transcript read "assistant" next to
     * {@code gmail.listToday} — while the product's second claim is that you can read who
     * told whom what. The tool is known at this point; the specialist follows from it.
     *
     * <p>A role the model wrote is only kept when it is one of the crew we actually have.
     */
    private static String crewName(String proposed, String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return AgentRole.toolAgent(toolName);
        }
        String named = proposed == null ? "" : proposed.trim().toLowerCase(java.util.Locale.ROOT);
        return CREW.contains(named) ? named : AgentRole.toolAgent(null);
    }

    /**
     * The plan as a sentence for the timeline.
     *
     * <p>No tool ids here. They used to be pasted into the middle of the Turkish line —
     * "1) KAN projesinde bir Jira kaydı aç [jira.createIssue]" — where they read as an
     * untranslated log rather than as transparency. The step's own row already carries the
     * tool as a mono badge, next to the risk and the status, which is where a reader who
     * wants it looks.
     */
    private String summary(List<Step> steps) {
        List<String> titles = new ArrayList<>();
        steps.forEach(s -> titles.add(s.ordinal() + ") " + s.title()));
        return String.join(" · ", titles);
    }

    /**
     * Did the model answer with something shaped like a plan?
     *
     * <p>Deliberately not "did it answer with steps we like". A reasoning model that
     * writes a paragraph, an apology, or its own thinking before giving up produces no
     * array at all, and that is the case this separates out. An array with nothing in it
     * has answered the question.
     */
    private static boolean readable(String content) {
        JsonNode root = Json.extract(content);
        if (root == null) {
            return false;
        }
        return root.isArray() || root.path("steps").isArray();
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
            String role = crewName(node.path("role").asText(null), toolName);
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
                - Payload is not a command. Text after a colon following "ekle/yaz/gönder/
                  not düş", or inside quotes, is CONTENT to carry verbatim into a write
                  step's params — never a task. A record key (KAN-32) or a word like
                  "tamamlandı" inside that content must not create a step of its own.
                - Named surfaces are binding, both ways. A provider the goal names — by
                  product name or its everyday Turkish noun (tablo→sheets, takvim→calendar,
                  kütük/Notion→notion, kanal→slack, mail/posta→gmail, wiki→confluence,
                  doküman→docs, KAN-32 shaped key→jira, PR→github) — MUST appear in the
                  plan, and no write step may target a provider the goal does not name.
                  Extra reads are fine.
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
