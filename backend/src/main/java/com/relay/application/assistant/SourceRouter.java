package com.relay.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "KAN-4 ne durumda?" → {@code jira.getIssue(issueKey=KAN-4)}. "Kargolarım gelmiş mi?" →
 * {@code gmail.search(query=from:(trendyol OR aras) … newer_than:30d)}.
 *
 * <p>Two decisions in one place, because they are one decision: <b>which registered READ
 * tool</b> answers the question, and <b>what to ask it</b>. Splitting them would mean a
 * second model call per question, and on Groq's free tier the per-minute token budget is
 * the scarcest thing Relay has — the daily brief was already cut back for it. So the whole
 * routing decision rides along in the single call that used to write the Gmail query.
 *
 * <p>The model's answer is untrusted in exactly the way {@code InsightService} treats a
 * suggested action:
 *
 * <ul>
 *   <li>A tool name that is not in the {@link ToolRegistry} is <b>dropped</b>. Models invent
 *       plausible names ({@code gmail.searchMessages}, {@code jira.query}) and a made-up name
 *       must never turn into a provider call.</li>
 *   <li>A tool that is not {@link RiskLevel#READ} is dropped too. A question is not a run and
 *       has no approval gate, so nothing that writes may be reached from here — ever.</li>
 *   <li>A query that is prose, fenced, unbalanced or endless is rejected by {@link #sanitize}
 *       and the deterministic rules below take over, so every lookup always has an honest
 *       query to run <em>and to show</em>.</li>
 * </ul>
 *
 * <p>With no usable model at all ({@link LlmClient#degraded()}) the routing is entirely
 * deterministic: an issue key routes to Jira, "PR" to GitHub, "toplantı" to the calendar,
 * and everything else to the mailbox — which is where this endpoint started.
 */
public class SourceRouter {

    private static final Logger LOG = System.getLogger(SourceRouter.class.getName());

    public static final String SOURCE_LLM = "llm";
    public static final String SOURCE_HEURISTIC = "heuristic";

    /** Three providers is already four seconds of fan-out; more is a run, not a question. */
    public static final int MAX_LOOKUPS = 3;

    /** Gmail's own limit is generous; anything past this is prose, not a query. */
    private static final int MAX_QUERY_LENGTH = 400;
    private static final int MAX_EXPLANATION_LENGTH = 200;
    /** Enough for the model to read; the tool list is prompt weight on every question. */
    private static final int MAX_DESCRIPTION_LENGTH = 120;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-\\d+)\\b");
    /** "PR", "PR'larım", "pr'ı" — but not the inside of another word. */
    private static final Pattern PULL_REQUEST = Pattern.compile("(?i)\\bpr(?:['’’]\\p{L}+)?\\b");
    /**
     * "Atlassian'dan", "Ayşe'den", "Migros'tan" — Turkish marks the sender of a mail with the
     * ablative suffix after an apostrophe, and "şundan mail gelmiş mi" is the question this
     * whole endpoint exists for.
     */
    private static final Pattern SENDER = Pattern.compile(
            "(\\p{L}[\\p{L}\\p{N}._-]{2,})['’’](?:d|t)[ae]n\\b");

    /**
     * One tool to ask, and what to ask it.
     *
     * @param tool        a name that was found in the registry — never one the model made up
     * @param query       the provider's own query syntax, or empty for a tool that takes none
     * @param explanation one Turkish sentence saying what was looked at, shown next to the query
     */
    public record Lookup(String tool, String query, String explanation) {
    }

    /**
     * @param source {@code llm} when the model's routing survived validation, else {@code heuristic}
     */
    public record Plan(List<Lookup> lookups, String source, long tokens, double costUsd) {

        public Optional<Lookup> primary() {
            return lookups.isEmpty() ? Optional.empty() : Optional.of(lookups.get(0));
        }
    }

    /** A query and the sentence that explains it. */
    private record Query(String query, String explanation) {
    }

    /** One deterministic fallback rule: folded keywords → a Gmail query. */
    private record Rule(List<String> keywords, String query, String explanation) {
    }

    /**
     * Turkey-specific mailbox defaults for the questions people actually ask. This list is the
     * <em>floor</em>, not the ceiling: with a working model the query is generated per
     * question and these rules only run when that query cannot be trusted.
     */
    private static final List<Rule> RULES = List.of(
            new Rule(List.of("kargo", "gonderi", "teslimat", "siparis", "paket", "kurye", "shipment"),
                    "(from:(trendyol OR hepsiburada OR amazon OR aras OR yurtici OR mng OR ptt OR ups OR dhl) "
                            + "OR subject:(kargo OR gönderi OR teslimat OR sipariş)) newer_than:30d",
                    "Son 30 günde kargo ve sipariş maillerini aradım."),
            new Rule(List.of("fatura", "odeme", "makbuz", "invoice", "tahsilat", "abonelik"),
                    "subject:(fatura OR ödeme OR makbuz OR invoice OR receipt) newer_than:30d",
                    "Son 30 günde fatura ve ödeme maillerini aradım."),
            new Rule(List.of("banka", "kredi", "kart ekstre", "ekstre", "havale", "eft"),
                    "from:(garanti OR akbank OR isbank OR yapikredi OR ziraat OR papara) newer_than:30d",
                    "Son 30 günde banka maillerini aradım."),
            new Rule(List.of("toplanti", "davet", "meeting", "gorusme", "randevu"),
                    "subject:(toplantı OR davet OR meeting OR invite) newer_than:14d",
                    "Son 14 günde toplantı ve davet maillerini aradım."),
            new Rule(List.of("mulakat", "basvuru", "is basvuru", "interview", "pozisyon", "ilan"),
                    "subject:(mülakat OR başvuru OR interview OR application) newer_than:60d",
                    "Son 60 günde başvuru ve mülakat maillerini aradım."),
            new Rule(List.of("okunmamis", "unread", "bekleyen"),
                    "is:unread -in:chats newer_than:7d",
                    "Son 7 günün okunmamış maillerini aradım."));

    /** Words that say the question is about a mailbox rather than a tracker or a calendar. */
    private static final List<String> MAIL_WORDS = List.of(
            "mail", "posta", "eposta", "e-posta", "inbox", "gelen kutu", "kutum", "mesaj geldi");
    private static final List<String> ISSUE_WORDS = List.of(
            "jira", "ticket", "issue", "sprint", "gorev", "backlog", "board", "kayit", "atanan",
            "bug", "hata kaydi", "epik", "epic");
    private static final List<String> CODE_WORDS = List.of(
            "github", "pull request", "review", "merge", "repo", "branch", "commit", "kod incele");
    private static final List<String> CALENDAR_WORDS = List.of(
            "toplanti", "takvim", "calendar", "meeting", "randevu", "davet", "etkinlik",
            "musait", "gorusme", "ajanda");

    private final LlmClient llm;
    private final ToolRegistry tools;

    public SourceRouter(LlmClient llm, ToolRegistry tools) {
        this.llm = llm;
        this.tools = tools;
    }

    // ---- entry point ------------------------------------------------------

    public Plan route(String question) {
        String cleaned = question == null ? "" : question.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("soru boş olamaz");
        }
        List<Tool> readable = readTools();
        if (readable.isEmpty()) {
            // Nothing is registered to ask. Say that upstream; do not spend a call on it.
            return new Plan(List.of(), SOURCE_HEURISTIC, 0, 0);
        }
        // The stub answers every purpose with its own summary text; sending it a question
        // would only burn a round trip to produce something the validation must reject anyway.
        if (llm.degraded()) {
            return heuristic(cleaned, 0, 0);
        }
        try {
            LlmResponse response = llm.complete(request(cleaned, readable));
            List<Lookup> lookups = lookups(Json.extract(response.content()), cleaned);
            if (lookups.isEmpty()) {
                LOG.log(Level.INFO, "routing from the model was rejected — using the fallback rules");
                return heuristic(cleaned, response.totalTokens(), response.costUsd());
            }
            return new Plan(lookups, SOURCE_LLM, response.totalTokens(), response.costUsd());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "routing failed ({0}) — using the fallback rules",
                    e.getClass().getSimpleName());
            return heuristic(cleaned, 0, 0);
        }
    }

    // ---- parameters -------------------------------------------------------

    /**
     * The tool's parameters for one lookup, derived from its own schema rather than from a
     * table of provider knowledge kept here.
     *
     * <p>A READ tool's first required string is the thing it searches by — {@code query} for
     * {@code gmail.search}, {@code jql} for {@code jira.searchIssues}, {@code issueKey} for
     * {@code jira.getIssue} — so the routed query goes there and a new READ tool needs no
     * change in this class. Tools with nothing required ("my pull requests", "today") take the
     * query as nothing at all, which is correct: there is no query, there is a list.
     */
    public static ObjectNode params(Tool tool, Lookup lookup, int maxResults) {
        ObjectNode params = Json.object();
        String field = queryField(tool);
        if (field != null) {
            params.put(field, lookup.query());
        }
        if (tool.schema().path("properties").has("maxResults")) {
            params.put("maxResults", maxResults);
        }
        return params;
    }

    /** The first required string parameter, or null when the tool requires none. */
    static String queryField(Tool tool) {
        JsonNode schema = tool.schema();
        for (JsonNode required : schema.path("required")) {
            String field = required.asText("");
            String type = schema.path("properties").path(field).path("type").asText("string");
            if (!field.isBlank() && "string".equals(type)) {
                return field;
            }
        }
        return null;
    }

    // ---- validation -------------------------------------------------------

    /**
     * The trust boundary. Everything rejected here has come back from a model at some point:
     * a tool that does not exist, a tool that writes, prose instead of a query.
     */
    private List<Lookup> lookups(JsonNode root, String question) {
        List<Lookup> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode array = root.isArray() ? root : root.path("lookups");
        if (!array.isArray()) {
            return out;
        }
        Set<String> chosen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            String name = node.path("tool").asText("").trim();
            Tool tool = tools.find(name).orElse(null);
            if (tool == null) {
                if (!name.isBlank()) {
                    LOG.log(Level.INFO, "dropping lookup for unknown tool {0}", name);
                }
                continue;
            }
            if (tool.risk() != RiskLevel.READ) {
                // A question never writes. Nothing here goes through the approval gate,
                // so nothing here may need one.
                LOG.log(Level.INFO, "dropping non-READ tool {0} from a question", name);
                continue;
            }
            if (!chosen.add(name)) {
                continue;
            }
            Lookup lookup = lookup(tool, node.path("query").asText(""),
                    node.path("explanation").asText(""), question);
            if (lookup != null) {
                out.add(lookup);
            }
            if (out.size() >= MAX_LOOKUPS) {
                break;
            }
        }
        return out;
    }

    /** One validated lookup, or null when the tool needs a query and none could be made. */
    private Lookup lookup(Tool tool, String rawQuery, String rawExplanation, String question) {
        String field = queryField(tool);
        String query = "";
        if (field != null) {
            query = sanitize(rawQuery);
            if (query == null) {
                query = heuristicQuery(tool.name(), question);
            }
            if (query.isBlank()) {
                LOG.log(Level.INFO, "dropping {0}: no usable {1}", tool.name(), field);
                return null;
            }
        }
        return new Lookup(tool.name(), query, explanation(rawExplanation, tool.name(), query));
    }

    /**
     * Returns a runnable query, or null when the model did not produce one.
     *
     * <p>Everything rejected here has actually come back from a model: fenced blocks, a
     * leading {@code q=}, a newline-separated list of alternatives, and plain Turkish prose
     * describing what it <em>would</em> search for.
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String query = raw.trim();
        if (query.startsWith("```") || query.contains("```")) {
            return null;
        }
        if (query.regionMatches(true, 0, "q=", 0, 2)) {
            query = query.substring(2).trim();
        }
        // A whole-string quote is packaging, not a phrase search.
        if (query.length() > 1 && query.startsWith("\"") && query.endsWith("\"")
                && query.indexOf('"', 1) == query.length() - 1) {
            query = query.substring(1, query.length() - 1).trim();
        }
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return null; // control characters have no business in a search box
            }
        }
        query = WHITESPACE.matcher(query).replaceAll(" ").trim();
        if (query.length() < 2 || query.length() > MAX_QUERY_LENGTH) {
            return null;
        }
        if (!balanced(query)) {
            return null;
        }
        // No operator and a full sentence's worth of words: that is an answer about a query,
        // not a query. "Kargo mailleri aranacak" would otherwise be searched verbatim.
        // ':' is Gmail and GitHub search, '=' and '~' are JQL — a Turkish sentence has none.
        boolean hasOperator = query.indexOf(':') > 0 || query.indexOf('=') > 0 || query.indexOf('~') > 0;
        if (!hasOperator && query.split(" ").length > 8) {
            return null;
        }
        return query;
    }

    private static boolean balanced(String query) {
        int depth = 0;
        int quotes = 0;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            } else if (c == '"') {
                quotes++;
            }
        }
        return depth == 0 && quotes % 2 == 0;
    }

    private static String explanation(String raw, String toolName, String query) {
        String text = raw == null ? "" : WHITESPACE.matcher(raw.trim()).replaceAll(" ");
        if (text.isBlank()) {
            return query.isBlank()
                    ? toolName + " aracına baktım."
                    : "Soruyu şu aramaya çevirdim: " + query;
        }
        return text.length() > MAX_EXPLANATION_LENGTH
                ? text.substring(0, MAX_EXPLANATION_LENGTH).trim() + "…" : text;
    }

    // ---- deterministic fallback -------------------------------------------

    /**
     * No usable model answer: route on the question's own words.
     *
     * <p>Deliberately narrow. A wrong guess here costs a provider round trip and an honest
     * "bulamadım"; a wide one costs three round trips per question on every question.
     */
    Plan heuristic(String question, long tokens, double costUsd) {
        List<Lookup> out = new ArrayList<>();
        String folded = fold(question);

        Matcher key = ISSUE_KEY.matcher(question);
        if (key.find() && has("jira.getIssue")) {
            add(out, "jira.getIssue", key.group(1), "Jira'da " + key.group(1) + " kaydına baktım.");
        } else if (mentions(folded, ISSUE_WORDS)) {
            Query jql = jql(question);
            if (!add(out, "jira.searchIssues", jql.query(), jql.explanation())) {
                add(out, "jira.listMyIssues", "", "Üzerimdeki açık Jira kayıtlarına baktım.");
            }
        }
        if (PULL_REQUEST.matcher(question).find() || mentions(folded, CODE_WORDS)) {
            add(out, "github.listMyPullRequests", "", "Beni bekleyen ve benim açtığım PR'lara baktım.");
        }
        if (mentions(folded, CALENDAR_WORDS)) {
            if (!add(out, "calendar.listUpcoming", "", "Yaklaşan takvim kayıtlarına baktım.")) {
                add(out, "calendar.listToday", "", "Bugünün takvim kayıtlarına baktım.");
            }
        }
        // The mailbox is the default: it is the one source that carries anything, and it is
        // what this endpoint answered before it could reach anywhere else.
        if (out.isEmpty() || (out.size() < MAX_LOOKUPS && mentions(folded, MAIL_WORDS))) {
            Query mail = mailQuery(question);
            add(out, "gmail.search", mail.query(), mail.explanation());
        }
        if (out.isEmpty()) {
            // Neither Gmail nor anything the rules know about is registered. Ask whatever
            // READ tool exists rather than answering "no sources" while one is sitting there.
            for (Tool tool : readTools()) {
                if (add(out, tool.name(), heuristicQuery(tool.name(), question),
                        tool.name() + " aracına baktım.")) {
                    break;
                }
            }
        }
        return new Plan(List.copyOf(out), SOURCE_HEURISTIC, tokens, costUsd);
    }

    /** Adds the lookup when the tool is registered, READ, and can be given what it requires. */
    private boolean add(List<Lookup> out, String toolName, String query, String explanation) {
        if (out.size() >= MAX_LOOKUPS) {
            return false;
        }
        Tool tool = tools.find(toolName).orElse(null);
        if (tool == null || tool.risk() != RiskLevel.READ) {
            return false;
        }
        if (queryField(tool) != null && (query == null || query.isBlank())) {
            return false;
        }
        out.add(new Lookup(toolName, queryField(tool) == null ? "" : query, explanation));
        return true;
    }

    /** The deterministic query for one tool — what the rules would have searched for. */
    private String heuristicQuery(String toolName, String question) {
        if (toolName.startsWith("gmail.")) {
            return mailQuery(question).query();
        }
        if ("jira.getIssue".equals(toolName)) {
            Matcher key = ISSUE_KEY.matcher(question);
            return key.find() ? key.group(1) : "";
        }
        if (toolName.startsWith("jira.")) {
            return jql(question).query();
        }
        return keywords(question);
    }

    /** The mailbox rules, unchanged: they are the reason "Ahmet'ten teklif geldi mi" works. */
    Query mailQuery(String question) {
        String folded = fold(question);

        Matcher address = EMAIL.matcher(question);
        if (address.find()) {
            return new Query("from:" + address.group() + " newer_than:90d",
                    "Son 90 günde " + address.group() + " adresinden gelen mailleri aradım.");
        }

        Matcher sender = SENDER.matcher(question);
        if (sender.find()) {
            String name = sender.group(1);
            return new Query("from:" + name + " newer_than:90d",
                    "Son 90 günde " + name + " adından gelen mailleri aradım.");
        }

        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (folded.contains(keyword)) {
                    return new Query(rule.query(), rule.explanation());
                }
            }
        }

        String terms = keywords(question);
        return terms.isBlank()
                ? new Query("newer_than:7d -in:chats", "Son 7 günün maillerini aradım.")
                : new Query(terms + " newer_than:90d",
                        "Sorudaki kelimeleri son 90 günün maillerinde aradım: " + terms);
    }

    /** The tracker equivalent: a key is exact, "bende" is mine, anything else is a text search. */
    private Query jql(String question) {
        Matcher key = ISSUE_KEY.matcher(question);
        if (key.find()) {
            return new Query("key = " + key.group(1),
                    "Jira'da " + key.group(1) + " kaydını aradım.");
        }
        String folded = fold(question);
        if (folded.contains("bende") || folded.contains("benim") || folded.contains("uzerim")
                || folded.contains("bana atan")) {
            return new Query("assignee = currentUser() AND status != Done ORDER BY updated DESC",
                    "Üzerimdeki bitmemiş Jira kayıtlarını aradım.");
        }
        String terms = keywords(question);
        if (terms.isBlank()) {
            return new Query("status != Done ORDER BY updated DESC",
                    "Açık Jira kayıtlarını aradım.");
        }
        return new Query("text ~ \"" + terms.replace("\"", "") + "\" ORDER BY updated DESC",
                "Jira'da şu kelimeleri aradım: " + terms);
    }

    /** The question's own content words, minus the Turkish scaffolding around them. */
    private static String keywords(String question) {
        StringBuilder out = new StringBuilder();
        for (String word : WHITESPACE.split(question.trim())) {
            // A Turkish suffix is not part of the word being searched for:
            // "Atlassian'dan" is a mail from Atlassian, not the string "Atlassiandan".
            String bare = word.split("['’’]")[0].replaceAll("[^\\p{L}\\p{N}]", "");
            if (bare.length() < 3 || STOP_WORDS.contains(fold(bare))) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(bare);
            if (out.length() > 60) {
                break;
            }
        }
        return out.toString();
    }

    private static final List<String> STOP_WORDS = List.of(
            "mail", "maili", "mailim", "maillerim", "mailleri", "gelmis", "geldi", "gelen", "var",
            "yok", "bana", "benim", "bir", "bu", "su", "acaba", "lutfen", "misin", "mi", "mu",
            "hic", "sunu", "sundan", "kim", "kimden", "ne", "nedir", "nerede", "ile", "icin",
            "hakkinda", "gonderilmis", "gonderdi", "sordum", "bak", "bakar", "misiniz",
            // Connective filler: live, "Vergi levhası ile ilgili mail geldi mi" searched for
            // the word "ilgili", which no mail about a tax certificate contains.
            "ilgili", "konusunda", "dair", "olan", "diye", "gore", "kadar", "sonra", "once",
            "simdi", "bugun", "dun", "yarin");

    /**
     * Turkish-safe folding for keyword matching.
     *
     * <p>{@code "İADE".toLowerCase(Locale.ROOT)} leaves a combining dot behind and
     * {@code Locale.forLanguageTag("tr")} turns {@code I} into {@code ı}, so neither one
     * alone matches an ASCII keyword list. The dotted/dotless pairs are mapped explicitly
     * first, then everything else is flattened to ASCII.
     */
    static String fold(String text) {
        if (text == null) {
            return "";
        }
        String mapped = text.replace('İ', 'I').replace('ı', 'i').replace('I', 'i')
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(mapped.length());
        for (int i = 0; i < mapped.length(); i++) {
            char c = mapped.charAt(i);
            out.append(switch (c) {
                case 'ş' -> 's';
                case 'ğ' -> 'g';
                case 'ü' -> 'u';
                case 'ö' -> 'o';
                case 'ç' -> 'c';
                case 'â' -> 'a';
                case 'î' -> 'i';
                case 'û' -> 'u';
                // The combining dot above, left by lowercasing a dotted capital I.
                case '\u0307' -> ' ';
                default -> c;
            });
        }
        return out.toString();
    }

    private static boolean mentions(String folded, List<String> words) {
        for (String word : words) {
            if (folded.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean has(String toolName) {
        return tools.find(toolName).filter(tool -> tool.risk() == RiskLevel.READ).isPresent();
    }

    /** Everything a question is allowed to reach: the registered READ tools, nothing else. */
    List<Tool> readTools() {
        List<Tool> out = new ArrayList<>();
        for (Tool tool : tools.all()) {
            if (tool.risk() == RiskLevel.READ) {
                out.add(tool);
            }
        }
        return out;
    }

    // ---- prompt -----------------------------------------------------------

    private LlmRequest request(String question, List<Tool> readable) {
        StringBuilder user = new StringBuilder();
        user.append("SORU: ").append(question).append("\n\n");
        user.append("TOOLS (use the exact name, nothing else exists):\n");
        for (Tool tool : readable) {
            user.append("- ").append(tool.name()).append(": ").append(shortened(tool.description()));
            String field = queryField(tool);
            user.append(field == null ? " [query: none]" : " [query -> " + field + "]").append('\n');
        }
        user.append("\nPick the tools that can answer the question and write each one's query.");

        return LlmRequest.of(LlmPurpose.ASK_ROUTE, systemPrompt(), user.toString(), schema(),
                Map.of("question", question));
    }

    /** First sentence, capped — the tool list is prompt weight paid on every question. */
    private static String shortened(String description) {
        String text = description == null ? "" : WHITESPACE.matcher(description.trim()).replaceAll(" ");
        int stop = text.indexOf(". ");
        if (stop > 20) {
            text = text.substring(0, stop + 1);
        }
        return text.length() > MAX_DESCRIPTION_LENGTH
                ? text.substring(0, MAX_DESCRIPTION_LENGTH).trim() + "…" : text;
    }

    private String systemPrompt() {
        return """
                You route a Turkish question to Relay's read-only tools and write each tool's
                query. You never answer the question itself.
                Rules:
                - Pick 1-3 tools from the TOOLS list, best first. Use the exact name. NEVER invent
                  a tool name; if nothing fits, pick the closest listed one.
                - Prefer ONE tool. Add a second only when the question really spans two systems.
                - query: that provider's own syntax, ONE line, no code fences, no "q=", no prose.
                  * gmail.*  -> Gmail syntax: from:, subject:, is:unread, newer_than:, OR, -,
                    "quoted phrases". Always bound it with newer_than: unless dates are named.
                    Turkish senders matter: shipping is trendyol, hepsiburada, amazon, aras,
                    yurtici, mng, ptt, ups, dhl with subjects kargo / gönderi / teslimat / sipariş;
                    invoices are fatura / ödeme / makbuz; banks are the Turkish bank domains.
                    Cover the plausible senders AND subjects with OR instead of guessing one.
                  * jira.searchIssues -> JQL. jira.getIssue -> the bare issue key (KAN-4).
                  * a tool marked [query: none] takes no query at all: use "".
                - explanation: ONE short TURKISH sentence per tool saying what you looked at.
                - JSON only, matching the schema.
                Examples:
                - "Kargolarım gelmiş mi?" -> {"lookups":[{"tool":"gmail.search","query":\
                "(from:(trendyol OR hepsiburada OR aras OR yurtici OR mng OR ptt) OR subject:(kargo \
                OR gönderi OR teslimat)) newer_than:30d","explanation":"Son 30 günde kargo ve \
                teslimat maillerini aradım."}]}
                - "KAN-4 ne durumda?" -> {"lookups":[{"tool":"jira.getIssue","query":"KAN-4",\
                "explanation":"Jira'da KAN-4 kaydına baktım."}]}
                - "Yarın toplantım var mı?" -> {"lookups":[{"tool":"calendar.listUpcoming",\
                "query":"","explanation":"Yaklaşan takvim kayıtlarına baktım."}]}
                """;
    }

    /** JSON schema the routing response must satisfy. */
    public static JsonNode schema() {
        ObjectNode lookup = Json.object();
        lookup.put("type", "object");
        lookup.putArray("required").add("tool");
        ObjectNode props = lookup.putObject("properties");
        ObjectNode tool = props.putObject("tool");
        tool.put("type", "string");
        tool.put("minLength", 3);
        props.putObject("query").put("type", "string");
        props.putObject("explanation").put("type", "string");

        ObjectNode root = Json.object();
        root.put("type", "object");
        root.putArray("required").add("lookups");
        ObjectNode lookups = root.putObject("properties").putObject("lookups");
        lookups.put("type", "array");
        lookups.set("items", lookup);
        return root;
    }
}
