package com.relay.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Kargolarım gelmiş mi?" → {@code from:(trendyol OR aras OR yurtici) OR subject:(kargo OR
 * teslimat) newer_than:30d}.
 *
 * <p>The model writes the query, this class decides whether it is allowed to run. Two reasons
 * it works that way:
 *
 * <ul>
 *   <li>A hard-coded phrasebook cannot answer "Ahmet'ten teklif geldi mi" — only a model can
 *       read an arbitrary question. So the model produces the query.</li>
 *   <li>A model asked for a query happily answers with a sentence, a fenced block, three
 *       queries, or the stub's own summary text. {@link #sanitize} rejects all of that and the
 *       deterministic {@link #heuristic} takes over, so the endpoint always has <em>some</em>
 *       honest query to run and to show.</li>
 * </ul>
 *
 * <p>The chosen query is always returned to the caller and displayed: an answer built on a
 * search the user cannot see is a black box.
 */
public class MailQueryTranslator {

    private static final Logger LOG = System.getLogger(MailQueryTranslator.class.getName());

    /** Gmail's own limit is generous; anything past this is prose, not a query. */
    private static final int MAX_QUERY_LENGTH = 400;
    private static final int MAX_EXPLANATION_LENGTH = 200;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    public static final String SOURCE_LLM = "llm";
    public static final String SOURCE_HEURISTIC = "heuristic";

    /**
     * @param query       the Gmail query that will actually be executed
     * @param explanation one Turkish sentence saying what was searched, shown next to the query
     * @param source      {@code llm} when the model's query survived validation, else {@code heuristic}
     */
    public record Translation(String query, String explanation, String source, long tokens, double costUsd) {
    }

    /** One deterministic fallback rule: folded keywords → a Gmail query. */
    private record Rule(List<String> keywords, String query, String explanation) {
    }

    /**
     * Turkey-specific defaults for the questions people actually ask. This list is the
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

    private final LlmClient llm;

    public MailQueryTranslator(LlmClient llm) {
        this.llm = llm;
    }

    // ---- entry point ------------------------------------------------------

    public Translation translate(String question) {
        String cleaned = question == null ? "" : question.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("soru boş olamaz");
        }
        // The stub answers every purpose with its own summary text; sending it a question
        // would only burn a round trip to produce something sanitize() must reject anyway.
        if (llm.degraded()) {
            return heuristic(cleaned, 0, 0);
        }
        try {
            LlmResponse response = llm.complete(request(cleaned));
            JsonNode root = Json.extract(response.content());
            String query = root == null ? null : sanitize(root.path("query").asText(""));
            if (query == null) {
                LOG.log(Level.INFO, "mail query from the model was rejected — using the fallback rules");
                return heuristic(cleaned, response.totalTokens(), response.costUsd());
            }
            return new Translation(query, explanation(root.path("explanation").asText(""), query),
                    SOURCE_LLM, response.totalTokens(), response.costUsd());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "mail query translation failed ({0}) — using the fallback rules",
                    e.getClass().getSimpleName());
            return heuristic(cleaned, 0, 0);
        }
    }

    // ---- validation -------------------------------------------------------

    /**
     * Returns a runnable Gmail query, or null when the model did not produce one.
     *
     * <p>Everything rejected here has actually come back from a model at some point:
     * fenced blocks, a leading {@code q=}, a newline-separated list of alternatives, and
     * plain Turkish prose describing what it <em>would</em> search for.
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
        // A whole-string quote is packaging, not a Gmail phrase search.
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
        // No operator and a full sentence's worth of words: that is an answer about a
        // query, not a query. "Kargo mailleri aranacak" would otherwise be searched verbatim.
        boolean hasOperator = query.indexOf(':') > 0;
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

    private static String explanation(String raw, String query) {
        String text = raw == null ? "" : WHITESPACE.matcher(raw.trim()).replaceAll(" ");
        if (text.isBlank()) {
            return "Soruyu şu Gmail aramasına çevirdim: " + query;
        }
        return text.length() > MAX_EXPLANATION_LENGTH
                ? text.substring(0, MAX_EXPLANATION_LENGTH).trim() + "…" : text;
    }

    // ---- deterministic fallback -------------------------------------------

    /** No usable model answer: match the question against the rules, then against itself. */
    Translation heuristic(String question, long tokens, double costUsd) {
        String folded = fold(question);

        Matcher address = EMAIL.matcher(question);
        if (address.find()) {
            String query = "from:" + address.group() + " newer_than:90d";
            return new Translation(query, "Son 90 günde " + address.group()
                    + " adresinden gelen mailleri aradım.", SOURCE_HEURISTIC, tokens, costUsd);
        }

        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (folded.contains(keyword)) {
                    return new Translation(rule.query(), rule.explanation(), SOURCE_HEURISTIC,
                            tokens, costUsd);
                }
            }
        }

        String terms = keywords(question);
        String query = terms.isBlank() ? "newer_than:7d -in:chats" : terms + " newer_than:90d";
        return new Translation(query, terms.isBlank()
                ? "Son 7 günün maillerini aradım."
                : "Sorudaki kelimeleri son 90 günün maillerinde aradım: " + terms,
                SOURCE_HEURISTIC, tokens, costUsd);
    }

    /** The question's own content words, minus the Turkish scaffolding around them. */
    private static String keywords(String question) {
        StringBuilder out = new StringBuilder();
        for (String word : WHITESPACE.split(question.trim())) {
            String bare = word.replaceAll("[^\\p{L}\\p{N}]", "");
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

    // ---- prompt -----------------------------------------------------------

    private LlmRequest request(String question) {
        String user = "SORU: " + question + "\n\n"
                + "Bu soruyu tek bir Gmail arama sorgusuna çevir.";
        return LlmRequest.of(LlmPurpose.MAIL_QUERY, systemPrompt(), user, schema(),
                Map.of("question", question));
    }

    private String systemPrompt() {
        return """
                You translate a Turkish question about someone's mailbox into ONE Gmail search
                query. You never answer the question itself.
                Rules:
                - Output Gmail search syntax only: from:, to:, subject:, has:attachment, is:unread,
                  newer_than:, older_than:, label:, OR, -, parentheses, "quoted phrases".
                - ONE line. No code fences, no "q=", no explanation inside the query.
                - Always bound the time range with newer_than: unless the question names dates.
                - Turkish senders matter: shipping is trendyol, hepsiburada, amazon, aras, yurtici,
                  mng, ptt, ups, dhl and subjects like kargo / gönderi / teslimat / sipariş;
                  invoices are fatura / ödeme / makbuz; banks are the Turkish bank domains.
                  Cover the plausible senders AND subjects with OR instead of guessing one.
                - Prefer a slightly wider query over an empty result set, but never drop the topic.
                - explanation: ONE short TURKISH sentence saying what you searched for.
                - JSON only, matching the schema.
                Examples:
                - "Kargolarım gelmiş mi?" -> {"query":"(from:(trendyol OR hepsiburada OR aras OR \
                yurtici OR mng OR ptt) OR subject:(kargo OR gönderi OR teslimat)) newer_than:30d",\
                "explanation":"Son 30 günde kargo ve teslimat maillerini aradım."}
                - "Ahmet'ten teklif geldi mi?" -> {"query":"from:ahmet (teklif OR proposal) \
                newer_than:60d","explanation":"Son 60 günde Ahmet'ten gelen teklif maillerini aradım."}
                """;
    }

    /** JSON schema the translation must satisfy. */
    public static JsonNode schema() {
        ObjectNode root = Json.object();
        root.put("type", "object");
        root.putArray("required").add("query");
        ObjectNode props = root.putObject("properties");
        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("minLength", 2);
        props.putObject("explanation").put("type", "string");
        return root;
    }
}
