package com.relay.application.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The paragraph at the top of the Bugün screen: what today looks like, what to do first and
 * why, and one piece of advice.
 *
 * <p>{@link InsightService} judges items one by one; this judges the <em>day</em>. It runs
 * after the insights and reuses them, so the ordering it produces is grounded in the same
 * classification the cards show.
 *
 * <p><b>It is skipped entirely when the model is degraded.</b> A daily summary is prose with
 * no verifiable content — when the stub writes it, it produces filler like "Adımlar
 * yürütüldü; ayrıntılar zaman çizelgesinde", which reads as an insight and is not one. An
 * absent {@code digest} field is honest; a generated one is not. So there is no template
 * fallback here, on purpose.
 *
 * <p>Bulk mail is not work and never appears here — not in the summary, not in the ordering.
 */
public class DigestService {

    private static final Logger LOG = System.getLogger(DigestService.class.getName());

    private static final int MAX_ITEMS = 12;
    private static final int MAX_PRIORITIES = 5;
    private static final int MAX_SUMMARY_LENGTH = 600;
    private static final int MAX_WHY_LENGTH = 160;
    private static final int MAX_ADVICE_LENGTH = 400;

    private final LlmClient llm;

    public DigestService(LlmClient llm) {
        this.llm = llm;
    }

    /** One line of the ordering: which item, and why it sits there. */
    public record Priority(String itemId, String why) {

        public Map<String, Object> view() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("itemId", itemId);
            map.put("why", why);
            return map;
        }
    }

    public record Digest(String summary, List<Priority> priorities, String advice,
                         long tokens, double costUsd) {

        /** {@code {summary, priorities:[{itemId, why}], advice}} — the shape the brief carries. */
        public Map<String, Object> view() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("summary", summary);
            List<Map<String, Object>> rows = new ArrayList<>();
            priorities.forEach(priority -> rows.add(priority.view()));
            map.put("priorities", rows);
            map.put("advice", advice);
            return map;
        }
    }

    // ---- entry point ------------------------------------------------------

    /**
     * @param items    everything the brief collected (calendar included — it is context for the day)
     * @param insights what {@link InsightService} decided about them, for kind and urgency
     * @return empty when the model is degraded, when there is nothing to summarise, or when
     *         the answer does not carry a usable summary
     */
    public Optional<Digest> digest(List<BriefItem> items, List<InsightService.Insight> insights) {
        if (llm.degraded()) {
            return Optional.empty();
        }
        List<BriefItem> subject = actionable(items);
        if (subject.isEmpty()) {
            return Optional.empty();
        }

        Map<String, InsightService.Insight> byId = new LinkedHashMap<>();
        if (insights != null) {
            insights.forEach(insight -> byId.put(insight.itemId(), insight));
        }

        try {
            LlmResponse response = llm.complete(request(subject, byId));
            return parse(response, subject);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "digest call failed ({0}) — the brief goes out without one",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Bulk mail is not work: it is dropped before the model ever sees the day. */
    private static List<BriefItem> actionable(List<BriefItem> items) {
        List<BriefItem> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (BriefItem item : items) {
            if (Boolean.TRUE.equals(item.ref().get("bulk"))) {
                continue;
            }
            out.add(item);
            if (out.size() >= MAX_ITEMS) {
                break;
            }
        }
        return out;
    }

    // ---- parsing ----------------------------------------------------------

    private Optional<Digest> parse(LlmResponse response, List<BriefItem> subject) {
        if (response.fallback()) {
            // The keys ran out between the degraded() check and the call. Same rule as above:
            // the stub does not write the day's summary.
            return Optional.empty();
        }
        JsonNode root = Json.extract(response.content());
        if (root == null) {
            LOG.log(Level.INFO, "digest answer was not JSON — skipping the field");
            return Optional.empty();
        }
        String summary = clamp(root.path("summary").asText(""), MAX_SUMMARY_LENGTH);
        if (summary.isBlank() || unusable("summary", summary)) {
            // No summary, no digest. An empty paragraph is worse than a missing one — and
            // live, the Bugün screen printed two ellipses because a small model copied the
            // shape we showed it ({"summary":"…"}) instead of filling it in.
            return Optional.empty();
        }

        Set<String> known = new LinkedHashSet<>();
        subject.forEach(item -> known.add(item.id()));

        List<Priority> priorities = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : root.path("priorities")) {
            String itemId = node.path("itemId").asText(node.path("id").asText(""));
            // An id we never sent is either a hallucination or a bulk item we dropped.
            if (!known.contains(itemId) || !seen.add(itemId)) {
                continue;
            }
            String why = clamp(node.path("why").asText(node.path("reason").asText("")), MAX_WHY_LENGTH);
            if (why.isBlank() || unusable("why", why)) {
                continue;
            }
            priorities.add(new Priority(itemId, why));
            if (priorities.size() >= MAX_PRIORITIES) {
                break;
            }
        }

        String advice = clamp(root.path("advice").asText(""), MAX_ADVICE_LENGTH);
        return Optional.of(new Digest(summary, priorities,
                unusable("advice", advice) ? "" : advice,
                response.totalTokens(), response.costUsd()));
    }

    // ---- the acceptance gate ----------------------------------------------

    /**
     * Would a person recognise this as something Relay wrote for them?
     *
     * <p>Live, on a healthy model — not degraded, not rate limited — the paragraph at the top
     * of the screen read <em>"…birlikte beberapaNeeds_reply mailleri bekliyor.
     * id=gmail:19fbb199a0786906, id=github-pr:…#43…"</em> and, on the next generation,
     * <em>"…önemli bir vấn"</em>. Three separate failures, all of them cheap to recognise
     * afterwards and none of them recognisable from inside the prompt: an Indonesian word, a
     * Vietnamese one, the raw {@code kind} enum, and the internal item ids the prompt uses to
     * name a row. A 70B model asked for Turkish drifts, and no amount of prompt is a
     * guarantee — so the answer is checked instead of trusted.
     *
     * <p>The rule when a check fires is the one this class already follows: <b>drop the
     * field</b>. A bad sentence is worse than a missing one, and the counted line
     * ({@code today}) is written by {@link DayTally} without a model, so the screen still
     * says what is waiting.
     *
     * @param field only for the log line — which part of the answer was thrown away
     */
    private static boolean unusable(String field, String text) {
        String reason = defect(text);
        if (reason == null) {
            return false;
        }
        LOG.log(Level.INFO, "digest {0} dropped: {1}", field, reason);
        return true;
    }

    /** @return what is wrong with the text, or {@code null} when it can be shown */
    static String defect(String text) {
        if (text == null || isTemplate(text)) {
            return "placeholder";
        }
        // NFC first: "vấn" arrives either as one code point or as "a" plus two combining
        // marks, and only the composed form is a letter the scan below can judge.
        String normalised = Normalizer.normalize(text, Normalizer.Form.NFC);
        if (MACHINE_HANDLE.matcher(normalised).find()) {
            return "internal item id";
        }
        if (PROMPT_FIELD.matcher(normalised).find()) {
            return "prompt field name";
        }
        if (RAW_ENUM.matcher(normalised).find()) {
            return "raw enum value";
        }
        String foreign = foreignWord(normalised);
        if (foreign != null) {
            return "non-Turkish word: " + foreign;
        }
        return null;
    }

    /**
     * {@code id=gmail:19fb…} — the handles the prompt uses to name a row, never the user's
     * words. The colon has to be followed by something for a match, because a sentence that
     * opens "Jira: bugün üç kayıt var" is prose and an id is not.
     */
    private static final Pattern MACHINE_HANDLE = Pattern.compile(
            "(?iu)\\b(?:item)?id\\s*=|\\b(?:github-pr|github-issue|github|gmail|jira|calendar|slack|notion):\\S");

    /** The field names of the prompt's own item lines, echoed back instead of read. */
    private static final Pattern PROMPT_FIELD = Pattern.compile(
            "(?iu)\\b(?:kaynak|tür|aciliyet|başlık|kimden|zaman)\\s*=");

    /**
     * A {@code kind} value shown to the user as it is stored.
     *
     * <p>No word boundary in front of the underscored ones on purpose: live the leak arrived
     * glued to the word before it ({@code beberapaNeeds_reply}), which {@code \b} would let
     * through. {@code request} and {@code scheduling} are deliberately not here — "pull
     * request" is ordinary Turkish prose about GitHub, and dropping the day's summary over it
     * would cost more than it saves.
     */
    private static final Pattern RAW_ENUM =
            Pattern.compile("(?iu)needs_reply|bug_report|\\bfyi\\b");

    /** Turkish letters outside ASCII, plus the circumflexes Turkish still writes (kâğıt). */
    private static final String TURKISH_LETTERS = "çğıöşüâîûÇĞİÖŞÜÂÎÛ";

    /**
     * Latin-script words that belong to neither language the screen speaks.
     *
     * <p>The script scan below catches Vietnamese, Cyrillic, CJK and Arabic because they are
     * written with letters Turkish does not have. Indonesian is written with the same
     * twenty-six letters, so it needs naming: {@code beberapa} ("birkaç") is the one that
     * reached the screen, and its neighbours in a drifting sentence are listed with it. None
     * of these is a Turkish or English word — a Turkish summary that trips this list does not
     * exist.
     */
    private static final Set<String> FOREIGN_WORDS = Set.of(
            "beberapa", "dengan", "untuk", "yang", "adalah", "tetapi", "sebuah", "banyak",
            "sedang", "harus", "tidak", "sudah", "juga", "atau", "karena", "sangat", "dalam",
            "kepada", "lainnya", "menunggu", "pesan");

    /**
     * The first word that is not Turkish or English, or {@code null} when there is none.
     *
     * <p>Both alphabets are ASCII plus, for Turkish, {@link #TURKISH_LETTERS} — so a letter
     * outside that set is by itself proof the model changed language mid-sentence. The
     * Turkish characters are the whole point of the allow-list: {@code çğıöşü} must survive,
     * only what is not ours is thrown out.
     */
    private static String foreignWord(String text) {
        StringBuilder word = new StringBuilder();
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetter(codePoint)) {
                if (codePoint > 127 && TURKISH_LETTERS.indexOf(codePoint) < 0) {
                    return new String(Character.toChars(codePoint));
                }
                word.appendCodePoint(codePoint);
                continue;
            }
            String finished = ours(word);
            if (finished != null) {
                return finished;
            }
        }
        return ours(word);
    }

    private static String ours(StringBuilder word) {
        if (word.isEmpty()) {
            return null;
        }
        String candidate = word.toString();
        word.setLength(0);
        return FOREIGN_WORDS.contains(InsightService.fold(candidate)) ? candidate : null;
    }

    /**
     * Did the model hand back the placeholder from the example instead of an answer?
     *
     * <p>The prompt ends with {@code JSON döndür: {"summary":"…","advice":"…"}} — a small
     * model under pressure copies that literally, and the screen then shows an ellipsis
     * where the day's summary belongs.
     */
    private static boolean isTemplate(String text) {
        String stripped = text.replace("…", "").replace(".", "").trim();
        return stripped.isEmpty();
    }

    private static String clamp(String raw, int max) {
        String text = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        return text.length() > max ? text.substring(0, max).trim() + "…" : text;
    }

    // ---- prompt -----------------------------------------------------------

    private LlmRequest request(List<BriefItem> items, Map<String, InsightService.Insight> byId) {
        StringBuilder user = new StringBuilder("BUGÜNÜ BEKLEYENLER:\n");
        for (BriefItem item : items) {
            InsightService.Insight insight = byId.get(item.id());
            user.append("- id=").append(item.id())
                    .append(" | kaynak=").append(item.source())
                    .append(" | tür=").append(insight == null ? item.kind() : insight.kind())
                    .append(" | aciliyet=").append(insight == null ? "?" : insight.urgency())
                    .append(" | başlık=").append(item.rowTitle())
                    .append(" | kimden=").append(item.from())
                    .append(" | zaman=").append(item.meta())
                    .append('\n');
        }
        user.append("\nJSON döndür: {\"summary\":\"…\",\"priorities\":[{\"itemId\":\"…\",\"why\":\"…\"}],"
                + "\"advice\":\"…\"}");
        return LlmRequest.of(LlmPurpose.DIGEST, systemPrompt(), user.toString(), schema(),
                Map.of("count", items.size()));
    }

    private String systemPrompt() {
        return """
                You are the Digest agent of Relay. You get everything waiting for one person today
                and you write the top of their screen, in TURKISH.
                Rules:
                - summary: ONE paragraph, 2-4 sentences, what today looks like. Name the two or
                  three things that actually matter, by their own words — the Jira key, the mail
                  subject, the PR title, the meeting hour. Counting categories is not a summary:
                  "12 bildirim var, 7'si mail" tells the user nothing they cannot see. No
                  motivational filler, no meta-commentary about being an assistant.
                - priorities: the items in the order they should be handled, most important first.
                  Give a row for everything that genuinely needs the user today — up to 5 — not
                  just the single top one. Each row: the itemId EXACTLY as given, and "why" — ONE
                  short sentence saying why it sits THERE, in that position ("müşteri demosu
                  14:00'te ve bu hata onu bloke ediyor"). A timestamp is not a reason: "1 saat
                  önce geldi" says nothing about importance. Only use ids from the list.
                - advice: ONE sentence of practical advice for this specific day — what to protect
                  time for, what to postpone, what to delegate. If there is nothing worth saying,
                  return an empty string rather than a platitude.
                - Never invent an item, a deadline, a person or a number that is not in the list.
                - JSON only, matching the schema. No prose outside it.
                """;
    }

    /** JSON schema the digest response must satisfy. */
    public static JsonNode schema() {
        ObjectNode priority = Json.object();
        priority.put("type", "object");
        priority.putArray("required").add("itemId").add("why");
        ObjectNode priorityProps = priority.putObject("properties");
        priorityProps.putObject("itemId").put("type", "string");
        priorityProps.putObject("why").put("type", "string");

        ObjectNode root = Json.object();
        root.put("type", "object");
        root.putArray("required").add("summary");
        ObjectNode props = root.putObject("properties");
        props.putObject("summary").put("type", "string");
        props.putObject("advice").put("type", "string");
        ObjectNode priorities = props.putObject("priorities");
        priorities.put("type", "array");
        priorities.set("items", priority);
        return root;
    }
}
