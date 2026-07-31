package com.relay.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, offline stand-in for the model. No network, no keys, no randomness —
 * the same goal always produces the same plan.
 *
 * <p>This is the default when {@code GROQ_API_KEYS} is empty and the safety net when
 * every key is burned, so the app always runs and the demo always plays.
 * Cost is reported as $0.00 because nothing was actually billed.
 */
public class StubLlmClient implements LlmClient {

    private static final Pattern PROJECT_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9})-\\d+\\b");
    private static final Pattern BARE_PROJECT = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9})\\b");
    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-\\d+)\\b");
    /** Whatever the provider called the headline: mail subject, issue summary, PR title. */
    private static final Pattern SUBJECT_LIKE = Pattern.compile(
            "\"(?:subject|summary|title)\"\\s*:\\s*\"([^\"]{4,90})\"");

    private final ToolRegistry tools;

    public StubLlmClient(ToolRegistry tools) {
        this.tools = tools;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String purpose = request.purpose() == null ? LlmPurpose.SUMMARIZE : request.purpose();
        String content = switch (purpose) {
            case LlmPurpose.PLAN -> plan(request);
            case LlmPurpose.TOOL_PARAMS -> params(request);
            case LlmPurpose.VERIFY -> verify(request);
            default -> summarize(request);
        };
        long promptTokens = estimate(request.system()) + estimate(request.user());
        long completionTokens = estimate(content);
        return new LlmResponse(content, promptTokens, completionTokens, 0.0, "stub", true);
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean degraded() {
        return true;
    }

    // ---- plan -------------------------------------------------------------

    private String plan(LlmRequest request) {
        String goal = context(request, "goal", request.user());
        String g = goal.toLowerCase(Locale.ROOT);

        ArrayNode steps = Json.mapper().createArrayNode();

        boolean jiraRead = mentions(g, "jira", "issue", "ticket", "sprint", "blocker", "görev", "iş", "task", "bug");
        boolean jiraUpdate = mentions(g, "güncelle", "update", "durum", "status", "taşı", "move", "in progress",
                "done", "kapat", "close");
        boolean jiraComment = mentions(g, "yorum", "comment", "note", "not düş");
        boolean slack = mentions(g, "slack", "kanal", "channel", "ekibe", "team", "duyur", "announce", "özet at",
                "mesaj", "message", "bildir", "notify", "paylaş", "share");

        if (jiraRead && has("jira.searchIssues")) {
            steps.add(step("Jira'da ilgili işleri bul", "jira-agent", "jira.searchIssues",
                    Map.of("jql", jql(goal), "maxResults", 10)));
        }
        if (jiraUpdate && has("jira.updateIssue")) {
            steps.add(step("Bulunan işlerin durumunu güncelle", "jira-agent", "jira.updateIssue",
                    Map.of("status", targetStatus(g))));
        }
        if (jiraComment && has("jira.addComment")) {
            steps.add(step("İlgili işe yorum ekle", "jira-agent", "jira.addComment", Map.of()));
        }
        if (slack && has("slack.listChannels")) {
            steps.add(step("Slack kanallarını listele", "slack-agent", "slack.listChannels", Map.of()));
        }
        if (slack && has("slack.postMessage")) {
            steps.add(step("Ekibe Slack'ten özet gönder", "slack-agent", "slack.postMessage", Map.of()));
        }

        if (steps.isEmpty()) {
            steps.add(step("Hedefi adımlara böl ve özetle", "coordinator", null, Map.of("goal", goal)));
        }

        ObjectNode root = Json.object();
        root.set("steps", steps);
        return root.toString();
    }

    private ObjectNode step(String title, String role, String toolName, Map<String, Object> params) {
        ObjectNode node = Json.object();
        node.put("title", title);
        node.put("role", role);
        if (toolName == null) {
            node.putNull("toolName");
        } else {
            node.put("toolName", toolName);
        }
        node.set("params", Json.toNode(params));
        return node;
    }

    private String jql(String goal) {
        Matcher keyed = PROJECT_KEY.matcher(goal);
        if (keyed.find()) {
            return "project = " + keyed.group(1) + " AND status != Done ORDER BY updated DESC";
        }
        Matcher bare = BARE_PROJECT.matcher(goal);
        while (bare.find()) {
            String candidate = bare.group(1);
            if (candidate.length() >= 3 && !candidate.equals("JIRA") && !candidate.equals("SLACK")) {
                return "project = " + candidate + " AND status != Done ORDER BY updated DESC";
            }
        }
        return "sprint in openSprints() AND status != Done ORDER BY updated DESC";
    }

    private String targetStatus(String goal) {
        if (mentions(goal, "done", "bitir", "kapat", "tamamla", "close")) {
            return "Done";
        }
        if (mentions(goal, "blocked", "blocker", "engel")) {
            return "Blocked";
        }
        return "In Progress";
    }

    // ---- tool params ------------------------------------------------------

    private String params(LlmRequest request) {
        JsonNode schema = request.schema();
        ObjectNode out = Json.object();
        JsonNode draft = Json.toNode(context(request, "draft"));
        if (draft != null && draft.isObject()) {
            draft.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        }
        String goal = String.valueOf(context(request, "goal", ""));
        Object previous = context(request, "previous");
        String previousJson = Json.write(previous);

        if (schema != null && schema.has("required")) {
            for (JsonNode required : schema.get("required")) {
                String field = required.asText();
                JsonNode current = out.get(field);
                if (current != null && !current.isNull() && !(current.isTextual() && current.asText().isBlank())) {
                    continue;
                }
                out.set(field, fill(field, schema, goal, previousJson));
            }
        }
        return out.toString();
    }

    private JsonNode fill(String field, JsonNode schema, String goal, String previousJson) {
        String type = schema.path("properties").path(field).path("type").asText("string");
        String lower = field.toLowerCase(Locale.ROOT);

        if (lower.contains("issuekey") || lower.equals("key")) {
            return Json.mapper().getNodeFactory().textNode(firstIssueKey(previousJson, goal));
        }
        if (lower.contains("channel")) {
            return Json.mapper().getNodeFactory().textNode(firstChannel(previousJson));
        }
        if (lower.contains("jql")) {
            return Json.mapper().getNodeFactory().textNode(jql(goal));
        }
        if (lower.contains("status")) {
            return Json.mapper().getNodeFactory().textNode(targetStatus(goal.toLowerCase(Locale.ROOT)));
        }
        if (lower.contains("text") || lower.contains("message") || lower.contains("body")
                || lower.contains("comment") || lower.contains("summary")) {
            return Json.mapper().getNodeFactory().textNode(digest(goal, previousJson));
        }
        return switch (type) {
            case "integer", "number" -> Json.mapper().getNodeFactory().numberNode(10);
            case "boolean" -> Json.mapper().getNodeFactory().booleanNode(true);
            case "array" -> Json.mapper().createArrayNode();
            case "object" -> Json.object();
            default -> Json.mapper().getNodeFactory().textNode(goal);
        };
    }

    private String firstIssueKey(String previousJson, String goal) {
        Matcher fromPrevious = ISSUE_KEY.matcher(previousJson == null ? "" : previousJson);
        if (fromPrevious.find()) {
            return fromPrevious.group(1);
        }
        Matcher fromGoal = ISSUE_KEY.matcher(goal);
        if (fromGoal.find()) {
            return fromGoal.group(1);
        }
        return "RELAY-1";
    }

    private String firstChannel(String previousJson) {
        if (previousJson != null) {
            Matcher named = Pattern.compile("\"name\"\\s*:\\s*\"([a-z0-9_-]+)\"").matcher(previousJson);
            if (named.find()) {
                return "#" + named.group(1);
            }
        }
        return "#general";
    }

    /**
     * A findings-first digest built from the step results, with no model involved.
     *
     * <p>The old version wrote "Adımlar Relay tarafından yürütüldü; ayrıntılar zaman
     * çizelgesinde" — a sentence that reports activity and tells the reader nothing. Live,
     * that text reached a Slack channel. What matters is in the results: which records,
     * which subjects, how many. Pull those out instead.
     */
    private String digest(String goal, String previousJson) {
        // Only what the providers returned. The wrapper around each step also carries a
        // "title" — the step's own name — and reading that produced Slack messages like
        // "Konular: Jira'da ilgili işleri bul · Slack kanallarını listele": the plan read
        // back to the team instead of its findings.
        String payload = resultsOnly(previousJson);
        List<String> keys = collect(ISSUE_KEY, payload, 6);
        List<String> subjects = collect(SUBJECT_LIKE, payload, 4);

        StringBuilder sb = new StringBuilder();
        if (!keys.isEmpty()) {
            sb.append(keys.size()).append(" kayıt: ").append(String.join(", ", keys));
        }
        if (!subjects.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("Konular: ").append(String.join(" · ", subjects));
        }
        if (sb.length() == 0) {
            // Nothing concrete came back. Say that plainly rather than dressing it up —
            // the write gate treats template phrasing as a defect and stops the step.
            return "Sonuç bulunamadı: " + goal.trim();
        }
        return sb.toString();
    }

    /** The {@code result} subtrees of the previous steps, with the step wrappers dropped. */
    private static String resultsOnly(String previousJson) {
        if (previousJson == null || previousJson.isBlank()) {
            return "";
        }
        JsonNode parsed;
        try {
            parsed = Json.parse(previousJson);
        } catch (RuntimeException e) {
            // Not every caller passes a JSON array here; fall back to the raw text.
            return previousJson;
        }
        if (!parsed.isArray()) {
            return previousJson;
        }
        ArrayNode results = Json.mapper().createArrayNode();
        for (JsonNode step : parsed) {
            JsonNode result = step.path("result");
            if (!result.isMissingNode() && !result.isNull()) {
                results.add(result);
            }
        }
        return results.toString();
    }

    /** First {@code limit} distinct capture groups of {@code pattern} in {@code text}. */
    private static List<String> collect(Pattern pattern, String text, int limit) {
        List<String> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && out.size() < limit) {
            String value = matcher.group(1).trim();
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    // ---- verify / summarize ----------------------------------------------

    private String verify(LlmRequest request) {
        Object result = context(request, "result");
        String raw = Json.write(result).toLowerCase(Locale.ROOT);
        boolean empty = result == null || raw.equals("null") || raw.equals("{}") || raw.equals("[]");
        boolean failed = raw.contains("\"error\":\"") || raw.contains("\"ok\":false");
        ObjectNode node = Json.object();
        if (empty || failed) {
            node.put("pass", false);
            node.put("reason", empty ? "sonuç boş geldi" : "sonuç hata içeriyor");
        } else {
            node.put("pass", true);
            node.put("reason", "sonuç adımı karşılıyor");
        }
        return node.toString();
    }

    private String summarize(LlmRequest request) {
        String goal = String.valueOf(context(request, "goal", ""));
        return digest(goal.isBlank() ? String.valueOf(request.user()) : goal, request.user());
    }

    // ---- helpers ----------------------------------------------------------

    private boolean has(String toolName) {
        return tools == null || tools.find(toolName).isPresent();
    }

    private static boolean mentions(String haystack, String... needles) {
        String lower = haystack.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Object context(LlmRequest request, String key) {
        return context(request, key, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T context(LlmRequest request, String key, T fallback) {
        if (request.context() instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value != null) {
                return (T) value;
            }
        }
        return fallback;
    }

    private static long estimate(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
