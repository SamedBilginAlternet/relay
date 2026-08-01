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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AI layer of the daily brief (BRIEF §3): classify what is waiting, and propose
 * what to do about it.
 *
 * <p>All items go into <em>one</em> schema-constrained call — a brief with 12 items costs
 * one round trip, not twelve. The answer is treated as untrusted: every
 * {@code suggestedActions[].tool} is checked against the {@link ToolRegistry} and dropped
 * when it names a tool that does not exist, because models invent plausible tool names.
 *
 * <p>A suggestion is the next step, not a pleasantry: what it proposes is decided by the
 * item's state, so an issue nobody started and an issue already in flight get different
 * buttons. Nothing here runs — every action still has to be pressed and approved.
 *
 * <p>When the model is unavailable, degraded or answers with garbage, a deterministic
 * classifier takes over so the screen is never empty — and it directs the same way.
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

    /** Jira status names that mean nobody has picked the issue up yet. */
    private static final String[] TODO_STATES = {
        "to do", "todo", "backlog", "open", "açık", "yapılacak", "new", "selected for development"};
    private static final String[] BLOCKED_STATES = {"blocked", "engel"};
    private static final String[] DONE_STATES = {
        "done", "tamam", "closed", "kapalı", "resolved", "çözüldü"};

    /** {@code 3g önce} — how long the row has been sitting there, read off the meta text. */
    private static final Pattern DAYS_AGO = Pattern.compile("^(\\d+)g önce$");

    private static final String TEAM_CHANNEL = "#engineering";

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
                    // The state is in the detail (Jira status, "review bekliyor"); how long the
                    // item has been in that state is here, and a review that has waited two days
                    // is a different suggestion from one opened an hour ago.
                    .append(" | bekleme=").append(item.meta())
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
                - summary: ONE short sentence, in TURKISH, saying what the user should do next and
                  why — not what the item is. "KAN-58 sana atandı, henüz başlanmadı" beats
                  "Bir Jira kaydı var".
                - suggestedActions: at most 3, ONLY tools from the given list, with params that fit
                  that tool's schema. Reuse the item's ref fields (issueKey, repo, number…) verbatim.
                - An action MOVES THE WORK ON. Order them: the step that changes the state of the
                  thing first, the step that tells the people who need to know second. A bare
                  comment is not a next step when the state is what has to change.
                - The item's own state picks the action — the same kind of item gets a different
                  suggestion in a different state:
                  · Jira issue assigned to the user, not started yet (To Do / Backlog / Open) →
                    move it to In Progress, then tell the team it has been picked up.
                  · Jira issue already in progress → write today's progress onto the issue.
                  · Jira issue blocked → take it to whoever can unblock it.
                  · Pull request waiting for the user's review → leave the review comment; when it
                    has been waiting for days, remind the team as well.
                  · Personal mail expecting an answer → prepare a reply draft, if a tool in the list
                    above writes drafts; if none does, turn the request into a record.
                  · Mail reporting something broken → open the record first, then tell the channel.
                  · Meeting starting today → send the attendees a reminder.
                - label: TURKISH, imperative, max 4 words, naming the NEXT STEP — "In Progress yap",
                  "İncele ve yorumla", "Taslak cevap yaz" — never the tool's own name.
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

    /**
     * No model, no problem — and on the deployed instance this is the usual path, because
     * the free-tier per-minute budget drops the call long before the working day is over.
     * So the fallback has to hand out work, not describe it.
     *
     * <p>The previous version keyed on the source alone: every Jira row got "Yorum ekle",
     * every GitHub row got "GitHub'a yorum yaz", and four of the five cards on the live
     * screen carried the same sentence. A comment is a courtesy — it never moves the item.
     * What decides the next step is the item's <em>state</em>: an issue nobody started needs
     * starting, one already in flight needs today's progress written down, a review that has
     * waited two days needs a reviewer rather than another polite note.
     */
    Insight heuristic(BriefItem item, String projectKey) {
        if ("event".equals(item.kind()) || "calendar".equals(item.source())) {
            return meetingNext(item);
        }
        return switch (item.source() == null ? "" : item.source()) {
            case "jira" -> jiraNext(item);
            case "github" -> githubNext(item);
            default -> mailNext(item, projectKey);
        };
    }

    /** An issue with your name on it: where it stands decides what happens to it next. */
    private Insight jiraNext(BriefItem item) {
        String key = ref(item, "issueKey");
        String handle = key.isBlank() ? item.title() : key;
        String status = ref(item, "status");
        String state = status.toLowerCase(Locale.ROOT);
        boolean important = ref(item, "priority").toLowerCase(Locale.ROOT).contains("high");
        List<Action> actions = new ArrayList<>();

        if (mentions(state, DONE_STATES)) {
            return new Insight(item.id(), "fyi", "low",
                    handle + " tamamlanmış görünüyor — bugün bir şey gerekmiyor.", List.of());
        }
        if (mentions(state, BLOCKED_STATES)) {
            jiraComment(actions, key, "Engeli kayda yaz",
                    "Bu kayıt engelli. Devam edebilmek için gereken: ");
            slack(actions, "Engeli ekibe taşı", handle + " engelli: " + item.title() + link(item));
            return new Insight(item.id(), "request", "high",
                    handle + " engelli — engeli kaldıracak kişiye bugün taşı.", capped(actions));
        }
        if (status.isBlank() || mentions(state, TODO_STATES)) {
            transition(actions, key, "In Progress", "Başla: In Progress yap");
            slack(actions, "Ekibe başladığını bildir",
                    handle + " üzerinde çalışmaya başlıyorum: " + item.title() + link(item));
            return new Insight(item.id(), "request", important ? "high" : "normal",
                    handle + " sana atandı ve henüz başlanmadı — bugün başlat.", capped(actions));
        }
        int idle = daysWaiting(item.meta());
        jiraComment(actions, key, "İlerlemeyi kayda yaz",
                "Bugünkü ilerleme: " + item.title() + " üzerinde çalışıyorum. Son durum: ");
        slack(actions, "Ekibe durum geç", handle + " durumu: " + item.title() + link(item));
        return new Insight(item.id(), "request", important ? "high" : "normal",
                handle + " " + status + " durumunda"
                        + (idle >= 2 ? " ve " + idle + " gündür güncellenmedi" : "")
                        + " — bugünkü ilerlemeyi yaz.", capped(actions));
    }

    /** A review you owe somebody is a different job from a review somebody owes you. */
    private Insight githubNext(BriefItem item) {
        String repo = ref(item, "repo");
        Object number = item.ref().getOrDefault("number", 0);
        String handle = repo.isBlank() ? item.title() : repo + "#" + number;
        String reason = ref(item, "reason");
        int waiting = daysWaiting(item.meta());
        String waited = waiting >= 1 ? " " + waiting + " gündür" : "";
        List<Action> actions = new ArrayList<>();

        if ("review_requested".equals(reason)) {
            githubComment(actions, repo, number, "İncele ve özet yaz",
                    "\"" + item.title() + "\" değişikliğini inceledim. Özet:\n- \n\nKarar: ");
            if (waiting >= 2) {
                slack(actions, "Ekibe hatırlat",
                        handle + waited + " review bekliyor: " + item.title() + link(item));
            }
            return new Insight(item.id(), "request", waiting >= 2 ? "high" : "normal",
                    handle + waited + " senin review'unu bekliyor — incele ve kararını yaz.",
                    capped(actions));
        }
        if ("author".equals(reason)) {
            slack(actions, "Review iste",
                    handle + waited + " review bekliyor: " + item.title() + link(item));
            githubComment(actions, repo, number, "Reviewer'a hatırlat",
                    "Review için bakabilecek biri var mı? Bekleyen tek şey bu.");
            return new Insight(item.id(), "request", "normal",
                    "Senin PR'ın " + handle + waited + " review bekliyor — bir reviewer bul.",
                    capped(actions));
        }
        boolean bug = mentions(item.text().toLowerCase(Locale.ROOT), BUG_WORDS);
        githubComment(actions, repo, number, "Planını yorum olarak yaz",
                "Bu kaydı üstleniyorum. Planım: ");
        if (bug) {
            slack(actions, "Kanala bildir", handle + ": " + item.title() + link(item));
        }
        return new Insight(item.id(), bug ? "bug_report" : "request", bug ? "high" : "normal",
                handle + " sana atandı" + (waiting >= 2 ? " ve " + waiting + " gündür duruyor" : "")
                        + " — nasıl ilerleyeceğini yaz.", capped(actions));
    }

    /** Mail splits three ways: something broke, someone is waiting on you, or neither. */
    private Insight mailNext(BriefItem item, String projectKey) {
        String text = item.text().toLowerCase(Locale.ROOT);
        String who = item.from() == null || item.from().isBlank() ? "Gönderen" : item.from();
        List<Action> actions = new ArrayList<>();

        if (mentions(text, BUG_WORDS)) {
            createIssue(actions, projectKey, item, "Jira kaydı aç");
            slack(actions, "Kanala bildir", item.title() + link(item));
            return new Insight(item.id(), "bug_report", "high",
                    who + " bir arıza bildiriyor: " + item.title()
                            + " — kaydı aç ve kanala haber ver.", capped(actions));
        }
        if (mentions(text, REPLY_WORDS)) {
            if (!draft(actions, item, "Taslak cevap yaz", "Merhaba " + who + ",\n\n\""
                    + item.title() + "\" konusunu aldım, bugün içinde dönüş yapacağım."
                    + "\n\nİyi çalışmalar,")) {
                // Nothing registered can write a draft yet. Rather than fall back to a
                // courtesy action, turn the request into something that can be tracked.
                createIssue(actions, projectKey, item, "Talebi kayda çevir");
            }
            return new Insight(item.id(), "needs_reply", "normal",
                    who + " senden dönüş bekliyor: " + item.title() + " — cevabı bugün yaz.",
                    capped(actions));
        }
        if (mentions(text, MEETING_WORDS)) {
            draft(actions, item, "Uygunluğunu yaz", "Merhaba " + who + ",\n\n\"" + item.title()
                    + "\" için bana uygun zamanlar:\n- \n\nİyi çalışmalar,");
            return new Insight(item.id(), "scheduling", "normal",
                    who + " bir zaman soruyor: " + item.title() + " — uygunluğunu bildir.",
                    capped(actions));
        }
        return new Insight(item.id(), "fyi", "low", "Bilgilendirme: " + item.title() + ".", List.of());
    }

    /** A meeting starting today: the move that helps is telling the people who forgot. */
    private Insight meetingNext(BriefItem item) {
        String at = item.meta() == null || item.meta().isBlank() ? "bugün" : item.meta();
        String where = ref(item, "meetingUrl");
        List<Action> actions = new ArrayList<>();
        slack(actions, "Katılımcılara hatırlat", "Hatırlatma: " + item.title() + " bugün " + at
                + (where.isBlank() ? link(item) : " — " + where));
        return new Insight(item.id(), "scheduling", "normal",
                "Bugün " + at + ": " + item.title() + " — katılımcılara hatırlatma geç.",
                capped(actions));
    }

    // ---- action builders --------------------------------------------------
    // Each one is a no-op when its tool is not registered, so a half-connected workspace
    // gets a shorter list rather than a button that dead-ends.

    private void transition(List<Action> actions, String issueKey, String status, String label) {
        if (issueKey.isBlank() || !has("jira.updateIssue")) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("issueKey", issueKey);
        params.put("status", status);
        actions.add(new Action("jira.updateIssue", label, params));
    }

    private void jiraComment(List<Action> actions, String issueKey, String label, String body) {
        if (issueKey.isBlank() || !has("jira.addComment")) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("issueKey", issueKey);
        params.put("body", body);
        actions.add(new Action("jira.addComment", label, params));
    }

    private void githubComment(List<Action> actions, String repo, Object number, String label,
                               String body) {
        if (repo.isBlank() || !has("github.addComment")) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("repo", repo);
        params.put("number", number);
        params.put("body", body);
        actions.add(new Action("github.addComment", label, params));
    }

    private void slack(List<Action> actions, String label, String text) {
        if (!has("slack.postMessage")) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel", TEAM_CHANNEL);
        params.put("text", text);
        actions.add(new Action("slack.postMessage", label, params));
    }

    private void createIssue(List<Action> actions, String projectKey, BriefItem item, String label) {
        // "Open a ticket" makes no sense for something that is already a ticket.
        if ("jira".equals(item.source()) || !has("jira.createIssue")) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectKey", projectKey);
        params.put("issueType", "Bug");
        params.put("summary", item.title());
        params.put("description", item.title() + "\n\nKaynak: " + item.source()
                + (item.url() == null || item.url().isBlank() ? "" : " — " + item.url()));
        actions.add(new Action("jira.createIssue", label, params));
    }

    /**
     * Offer a reply draft — if anything registered can write one.
     *
     * <p>The tool is found in the registry by what it does, not by a name written down here:
     * the mail draft tool is being built on another branch (#27), and the day it registers
     * itself this suggestion appears without anyone editing this file. Its parameters are
     * seeded from the tool's own schema for the same reason.
     *
     * @return whether a draft action was added
     */
    private boolean draft(List<Action> actions, BriefItem item, String label, String body) {
        Tool tool = draftTool();
        if (tool == null) {
            return false;
        }
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("to", item.ref().getOrDefault("from", item.from()));
        candidates.put("subject", "Re: " + item.title());
        candidates.put("body", body);
        candidates.put("threadId", item.ref().get("threadId"));
        candidates.put("messageId", item.ref().get("messageId"));
        actions.add(new Action(tool.name(), label, fit(tool, candidates)));
        return true;
    }

    /** A registered mail tool that writes a draft instead of sending anything. */
    private Tool draftTool() {
        for (Tool tool : tools.all()) {
            String name = tool.name().toLowerCase(Locale.ROOT);
            if (name.startsWith("gmail.") && name.contains("draft")) {
                return tool;
            }
        }
        return null;
    }

    /** Only the parameters the tool declares — a seed, never a guess at somebody's schema. */
    private static Map<String, Object> fit(Tool tool, Map<String, Object> candidates) {
        JsonNode properties = tool.schema().path("properties");
        if (!properties.isObject()) {
            return candidates;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        candidates.forEach((key, value) -> {
            if (properties.has(key) && value != null && !String.valueOf(value).isBlank()) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    // ---- small helpers ----------------------------------------------------

    private static List<Action> capped(List<Action> actions) {
        return actions.size() <= MAX_ACTIONS ? List.copyOf(actions)
                : List.copyOf(actions.subList(0, MAX_ACTIONS));
    }

    private static String ref(BriefItem item, String field) {
        Object value = item.ref().get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static String link(BriefItem item) {
        return item.url() == null || item.url().isBlank() ? "" : " — " + item.url();
    }

    /** {@code "3g önce"} → {@code 3}. Anything younger than a day is 0. */
    static int daysWaiting(String meta) {
        if (meta == null) {
            return 0;
        }
        Matcher matcher = DAYS_AGO.matcher(meta.trim());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
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
