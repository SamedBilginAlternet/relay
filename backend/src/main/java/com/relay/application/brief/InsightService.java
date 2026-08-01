package com.relay.application.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.RiskLevel;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The AI layer of the daily brief (BRIEF §3): classify what is waiting, and propose
 * what to do about it.
 *
 * <p>All items go into <em>one</em> schema-constrained call — a brief with 12 items costs
 * one round trip, not twelve. The answer is treated as untrusted: every
 * {@code suggestedActions[].tool} is checked against the {@link ToolRegistry} and dropped
 * when it names a tool that does not exist, because models invent plausible tool names.
 *
 * <p>When the model is unavailable, degraded or answers with garbage, a deterministic
 * keyword classifier takes over so the screen is never empty.
 *
 * <p>Copy ({@code summary}, {@code label}) is Turkish — the UI is Turkish.
 */
public class InsightService {

    private static final Logger LOG = System.getLogger(InsightService.class.getName());

    public static final List<String> KINDS =
            List.of("bug_report", "request", "fyi", "needs_reply", "scheduling");
    public static final List<String> URGENCIES = List.of("high", "normal", "low");

    private static final int MAX_ITEMS = 14;
    private static final int MAX_ACTIONS = 3;

    private static final String[] BUG_WORDS = {
        "hata", "error", "patl", "502", "500", "503", "crash", "çöküyor", "exception", "fail",
        "bug", "bozuk", "kırıl", "broken", "timeout", "düşüyor", "kesiliyor"};
    private static final String[] REPLY_WORDS = {
        "onay", "rica", "bekliyor", "cevap", "yanıt", "dönebilir", "bakabilir", "?", "lütfen", "review"};
    private static final String[] MEETING_WORDS = {
        "toplantı", "planlama", "1:1", "sync", "görüşme", "meeting", "takvim", "davet"};

    private final LlmClient llm;
    private final ToolRegistry tools;

    public InsightService(LlmClient llm, ToolRegistry tools) {
        this.llm = llm;
        this.tools = tools;
    }

    // ---- wire types -------------------------------------------------------

    /** A one-click proposal. Never executed here — the user has to press it. */
    public record Action(String tool, String label, Map<String, Object> params) {

        public Map<String, Object> view() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tool", tool);
            map.put("label", label);
            map.put("params", params == null ? Map.of() : params);
            return map;
        }
    }

    public record Insight(String itemId, String kind, String urgency, String summary, List<Action> actions) {
    }

    public record Result(List<Insight> insights, long tokens, double costUsd, String source) {
    }

    // ---- entry point ------------------------------------------------------

    /**
     * @param items     everything the brief collected, in display order
     * @param projectKey Jira project a {@code jira.createIssue} suggestion should target
     */
    public Result analyze(List<BriefItem> items, String projectKey) {
        List<BriefItem> subject = items == null ? List.of()
                : items.subList(0, Math.min(items.size(), MAX_ITEMS));
        if (subject.isEmpty()) {
            return new Result(List.of(), 0, 0, "empty");
        }

        long tokens = 0;
        double cost = 0;
        Map<String, Insight> byItem = new LinkedHashMap<>();
        String source = "heuristic";

        try {
            LlmResponse response = llm.complete(request(subject, projectKey));
            tokens = response.totalTokens();
            cost = response.costUsd();
            byItem.putAll(parse(response.content(), subject));
            if (!byItem.isEmpty()) {
                source = llm.degraded() ? "llm:degraded" : "llm";
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "insight call failed, falling back to heuristics: {0}", e.getMessage());
        }

        // Anything the model skipped (or hallucinated an id for) still gets a card.
        List<Insight> out = new ArrayList<>();
        for (BriefItem item : subject) {
            Insight insight = byItem.get(item.id());
            out.add(demoteBulk(item, insight != null ? insight : heuristic(item, projectKey)));
        }
        return new Result(out, tokens, cost, source);
    }

    /**
     * Bulk mail never becomes work, whatever the model decided.
     *
     * <p>Live, a DEV Community newsletter titled "Good eats and rockstar bugs for your
     * weekend" came back as a high-urgency bug report offering to open a Jira ticket. The
     * model was pattern-matching on the word "bugs"; the mail carried a
     * {@code List-Unsubscribe} header, which says plainly that it went to a mailing list.
     * The header wins over the prose.
     */
    private static Insight demoteBulk(BriefItem item, Insight insight) {
        Object bulk = item.ref().get("bulk");
        if (!Boolean.TRUE.equals(bulk)) {
            return insight;
        }
        return new Insight(insight.itemId(), "fyi", "low", insight.summary(), List.of());
    }

    /** {@code channel, text} — enough to fill the call without shipping the schema. */
    private static String requiredFields(Tool tool) {
        JsonNode required = tool.schema().path("required");
        if (!required.isArray() || required.isEmpty()) {
            return "(yok)";
        }
        List<String> names = new ArrayList<>();
        required.forEach(node -> names.add(node.asText()));
        return String.join(", ", names);
    }

    /** Fields a suggested action can actually use as parameters. */
    private static final java.util.Set<String> REF_FIELDS = java.util.Set.of(
            "issueKey", "repo", "number", "messageId", "threadId", "channel", "from", "bulk");

    private static Map<String, Object> actionRef(Map<String, Object> ref) {
        Map<String, Object> kept = new LinkedHashMap<>();
        ref.forEach((key, value) -> {
            if (REF_FIELDS.contains(key)) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    // ---- llm --------------------------------------------------------------

    private LlmRequest request(List<BriefItem> items, String projectKey) {
        StringBuilder user = new StringBuilder();
        user.append("PROJECT KEY for new Jira issues: ").append(projectKey).append("\n\n");
        user.append("ITEMS:\n");
        for (BriefItem item : items) {
            user.append("- id=").append(item.id())
                    .append(" | source=").append(item.source())
                    .append(" | kind=").append(item.kind())
                    .append(" | title=").append(item.title())
                    .append(" | detail=").append(item.subtitle())
                    // Only the handles an action needs. The full ref carries mail snippets
                    // and provider payloads; sending fifteen of those blew through the
                    // per-minute token budget and dropped the whole layer to heuristics.
                    .append(" | ref=").append(Json.write(actionRef(item.ref())))
                    .append('\n');
        }
        user.append("\nTOOLS YOU MAY SUGGEST (use the exact name, nothing else exists):\n");
        for (Tool tool : actionableTools()) {
            // Name, purpose and the required field names — not the whole JSON Schema.
            // Fifteen full schemas rode along on every brief and, with the free-tier
            // per-minute token budget, that alone was enough to push the call into 429
            // and drop the screen to heuristics.
            user.append("- ").append(tool.name())
                    .append(" (risk=").append(tool.risk().wire()).append("): ")
                    .append(tool.description())
                    .append(" | zorunlu: ").append(requiredFields(tool))
                    .append('\n');
        }
        user.append("\nAnswer JSON only: {\"insights\":[{\"id\":\"…\",\"kind\":\"…\",\"urgency\":\"…\","
                + "\"summary\":\"…\",\"suggestedActions\":[{\"tool\":\"…\",\"label\":\"…\",\"params\":{…}}]}]}");

        return LlmRequest.of(LlmPurpose.INSIGHT, systemPrompt(), user.toString(), schema(),
                Map.of("items", itemContext(items), "projectKey", projectKey));
    }

    private String systemPrompt() {
        return """
                You are the Insight agent of Relay. For every inbox / pull request / issue / event you
                get, decide what it is and what the user could do about it in one click.
                Rules:
                - kind: bug_report | request | fyi | needs_reply | scheduling
                - urgency: high | normal | low. Only production breakage or a same-day deadline is high.
                - Bulk mail is not work. Newsletters, digests, product announcements, marketing,
                  receipts, "verify your e-mail" and automated notifications are ALWAYS
                  kind=fyi, urgency=low, with NO suggested actions — no matter which words they
                  contain. A newsletter titled "rockstar bugs for your weekend" is not a bug
                  report; a bug report is a person describing something that broke.
                - A real request comes from a human who expects something back from THIS user.
                - summary: ONE short sentence, in TURKISH, saying what is being asked of the user.
                - label: TURKISH, imperative, max 4 words, e.g. "Jira ticket aç".
                - suggestedActions: at most 3, ONLY tools from the given list, with params that fit
                  that tool's schema. Reuse the item's ref fields (issueKey, repo, number…) verbatim.
                - Do not invent tool names. If nothing sensible applies, return an empty action list.
                - Answer with JSON only, matching the schema. No prose.
                """;
    }

    private List<Map<String, Object>> itemContext(List<BriefItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        items.forEach(item -> out.add(item.view()));
        return out;
    }

    /** Tools worth proposing: everything registered except the destructive ones. */
    private List<Tool> actionableTools() {
        List<Tool> out = new ArrayList<>();
        for (Tool tool : tools.all()) {
            if (tool.risk() != RiskLevel.DESTRUCTIVE) {
                out.add(tool);
            }
        }
        return out;
    }

    private Map<String, Insight> parse(String content, List<BriefItem> items) {
        Map<String, Insight> out = new LinkedHashMap<>();
        JsonNode root = Json.extract(content);
        if (root == null) {
            return out;
        }
        JsonNode array = root.isArray() ? root : root.path("insights");
        if (!array.isArray()) {
            return out;
        }
        Set<String> knownIds = new LinkedHashSet<>();
        items.forEach(item -> knownIds.add(item.id()));

        for (JsonNode node : array) {
            String id = node.path("id").asText(node.path("itemId").asText(""));
            if (!knownIds.contains(id)) {
                continue; // an id we never sent — drop it rather than guess
            }
            out.put(id, new Insight(
                    id,
                    oneOf(node.path("kind").asText(""), KINDS, "fyi"),
                    oneOf(node.path("urgency").asText(""), URGENCIES, "normal"),
                    node.path("summary").asText("").isBlank() ? "Özet üretilemedi." : node.path("summary").asText(),
                    actions(node.path("suggestedActions"))));
        }
        return out;
    }

    /**
     * The trust boundary. A suggestion naming a tool that is not in the registry is
     * dropped — it is not turned into a run, not shown, not logged as usable.
     */
    private List<Action> actions(JsonNode node) {
        List<Action> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode candidate : node) {
            String tool = candidate.path("tool").asText("");
            if (tool.isBlank() || tools.find(tool).isEmpty()) {
                if (!tool.isBlank()) {
                    LOG.log(Level.INFO, "dropping suggestion for unknown tool {0}", tool);
                }
                continue;
            }
            String label = candidate.path("label").asText("");
            if (label.isBlank()) {
                label = tool;
            }
            out.add(new Action(tool, label, Json.toMap(candidate.get("params"))));
            if (out.size() >= MAX_ACTIONS) {
                break;
            }
        }
        return out;
    }

    private static String oneOf(String value, List<String> allowed, String fallback) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(lower) ? lower : fallback;
    }

    // ---- deterministic fallback -------------------------------------------

    /** No model, no problem: keyword rules that always produce a usable card. */
    Insight heuristic(BriefItem item, String projectKey) {
        String text = item.text().toLowerCase(Locale.ROOT);
        List<Action> actions = new ArrayList<>();

        if ("event".equals(item.kind())) {
            return new Insight(item.id(), "scheduling", "normal",
                    "Bugünkü takvim kaydı: " + item.title() + ".", actions);
        }

        boolean bug = mentions(text, BUG_WORDS);
        boolean reply = mentions(text, REPLY_WORDS);
        boolean meeting = mentions(text, MEETING_WORDS);

        String kind = bug ? "bug_report" : reply ? "needs_reply" : meeting ? "scheduling" : "fyi";
        String urgency = bug ? "high" : reply ? "normal" : "low";
        String summary = switch (kind) {
            case "bug_report" -> "Bir hata bildirimi gibi görünüyor: " + item.title() + ".";
            case "needs_reply" -> "Senden bir dönüş bekleniyor: " + item.title() + ".";
            case "scheduling" -> "Takvimle ilgili bir konu: " + item.title() + ".";
            default -> "Bilgilendirme: " + item.title() + ".";
        };

        // "Open a ticket" makes no sense for something that is already a ticket.
        if (bug && !"jira".equals(item.source()) && has("jira.createIssue")) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("projectKey", projectKey);
            params.put("issueType", "Bug");
            params.put("summary", item.title());
            params.put("description", item.title() + "\n\nKaynak: " + item.source()
                    + (item.url() == null || item.url().isBlank() ? "" : " — " + item.url()));
            actions.add(new Action("jira.createIssue", "Jira ticket aç", params));
        }
        if ("jira".equals(item.source()) && has("jira.addComment")) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("issueKey", String.valueOf(item.ref().getOrDefault("issueKey", "")));
            params.put("body", "Relay: bugünkü brifingde öne çıktı.");
            actions.add(new Action("jira.addComment", "Yorum ekle", params));
        }
        if ("github".equals(item.source()) && has("github.addComment")) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("repo", String.valueOf(item.ref().getOrDefault("repo", "")));
            params.put("number", item.ref().getOrDefault("number", 0));
            params.put("body", "Relay: bugün bakıyorum.");
            actions.add(new Action("github.addComment", "GitHub'a yorum yaz", params));
        }
        if (actions.size() < MAX_ACTIONS && has("slack.postMessage") && (bug || reply)) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("channel", "#engineering");
            params.put("text", item.title() + (item.url() == null || item.url().isBlank()
                    ? "" : " — " + item.url()));
            actions.add(new Action("slack.postMessage", "Ekibe bildir", params));
        }
        return new Insight(item.id(), kind, urgency, summary, actions);
    }

    private boolean has(String toolName) {
        return tools.find(toolName).isPresent();
    }

    private static boolean mentions(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ---- schema -----------------------------------------------------------

    /** JSON schema the insight response must satisfy. */
    public static JsonNode schema() {
        ObjectNode action = Json.object();
        action.put("type", "object");
        action.putArray("required").add("tool").add("label");
        ObjectNode actionProps = action.putObject("properties");
        actionProps.putObject("tool").put("type", "string");
        actionProps.putObject("label").put("type", "string");
        actionProps.putObject("params").put("type", "object");

        ObjectNode insight = Json.object();
        insight.put("type", "object");
        insight.putArray("required").add("id").add("kind").add("urgency").add("summary");
        ObjectNode props = insight.putObject("properties");
        props.putObject("id").put("type", "string");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        KINDS.forEach(kinds::add);
        ObjectNode urgency = props.putObject("urgency");
        urgency.put("type", "string");
        ArrayNode urgencies = urgency.putArray("enum");
        URGENCIES.forEach(urgencies::add);
        props.putObject("summary").put("type", "string");
        ObjectNode suggested = props.putObject("suggestedActions");
        suggested.put("type", "array");
        suggested.set("items", action);

        ObjectNode root = Json.object();
        root.put("type", "object");
        root.putArray("required").add("insights");
        ObjectNode insights = root.putObject("properties").putObject("insights");
        insights.put("type", "array");
        insights.set("items", insight);
        return root;
    }
}
