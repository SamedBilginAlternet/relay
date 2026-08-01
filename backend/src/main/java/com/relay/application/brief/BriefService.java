package com.relay.application.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.Clock;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.LlmClient;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Bugün" screen, assembled (BRIEF §5).
 *
 * <p>Three properties matter here and each one is deliberate:
 *
 * <ul>
 *   <li><b>Parallel.</b> Every READ tool runs at once on a virtual thread with its own
 *       timeout, so the brief costs the slowest provider, not their sum.</li>
 *   <li><b>Partial success.</b> A section carries {@code ok | unavailable | error} next to
 *       its items. A missing Gmail connection greys out one card; it never fails the call.
 *       {@code reason} is shown to the user verbatim, so it is a Turkish sentence — never a
 *       stack trace and never a response body that could echo a token back.</li>
 *   <li><b>Suggestion ≠ action.</b> This class only proposes. Executing goes through
 *       {@code POST /api/runs/from-suggestion} and the normal approval gate.</li>
 * </ul>
 */
public class BriefService {

    private static final Logger LOG = System.getLogger(BriefService.class.getName());

    public static final String OK = "ok";
    public static final String UNAVAILABLE = "unavailable";
    public static final String ERROR = "error";

    private static final int PRIORITY_LIMIT = 5;
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})");

    private final ToolRegistry tools;
    private final ConnectionRepository connections;
    private final InsightService insights;
    private final DigestService digests;
    private final LlmClient llm;
    private final Clock clock;
    private final Executor executor;
    private final Duration toolTimeout;
    private final Duration cacheTtl;
    private final ZoneId zone;
    private final String defaultProjectKey;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    /**
     * The generation that is already running, if there is one.
     *
     * <p>Pressing Yenile twice built the brief twice. Each build calls all five READ tools
     * and spends two model turns, so the second press cost another 5 208 tokens for a
     * result that was thrown away the moment the first one finished writing the cache —
     * the two answers were stamped one millisecond apart. Tokens are the scarcest thing
     * this product has (§10), which makes doing the work twice worse than doing it slowly.
     */
    private final AtomicReference<CompletableFuture<Map<String, Object>>> inFlight =
            new AtomicReference<>();

    public BriefService(ToolRegistry tools, ConnectionRepository connections, InsightService insights,
                        DigestService digests, LlmClient llm, Clock clock, Executor executor,
                        Duration toolTimeout, Duration cacheTtl, String timezone,
                        String defaultProjectKey) {
        this.tools = tools;
        this.connections = connections;
        this.insights = insights;
        this.digests = digests;
        this.llm = llm;
        this.clock = clock;
        this.executor = executor;
        this.toolTimeout = toolTimeout;
        this.cacheTtl = cacheTtl;
        this.zone = zoneOf(timezone);
        this.defaultProjectKey = defaultProjectKey == null || defaultProjectKey.isBlank()
                ? "RELAY" : defaultProjectKey.trim().toUpperCase(Locale.ROOT);
    }

    private record Cached(Map<String, Object> body, Instant at) {
    }

    /** One fetched tool call, already judged. {@code reason} is user-facing Turkish. */
    private record Fetched(String status, JsonNode data, String reason, String mode, long durationMs) {
    }

    // ---- entry point ------------------------------------------------------

    public Map<String, Object> brief() {
        return brief(false);
    }

    public Map<String, Object> brief(boolean refresh) {
        Cached cached = cache.get();
        if (!refresh && cached != null
                && Duration.between(cached.at(), clock.now()).compareTo(cacheTtl) < 0) {
            Map<String, Object> body = new LinkedHashMap<>(cached.body());
            body.put("cached", true);
            body.put("cachedAt", cached.at().toString());
            return body;
        }
        return await(generation());
    }

    /**
     * One build at a time: whoever asks while a brief is being made waits for that one
     * instead of starting another.
     *
     * <p>The button spends four or five seconds waiting, so a second press is ordinary user
     * behaviour rather than abuse — and it doubled the bill. Both callers get the same map,
     * so both answers carry the same {@code generatedAt}, which is what makes it visible
     * from outside that only one generation happened.
     */
    private CompletableFuture<Map<String, Object>> generation() {
        CompletableFuture<Map<String, Object>> mine = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> running = inFlight.compareAndExchange(null, mine);
        if (running != null) {
            return running;
        }
        try {
            Map<String, Object> body = build();
            cache.set(new Cached(body, clock.now()));
            mine.complete(body);
        } catch (RuntimeException | Error e) {
            // Everyone waiting on this build fails with it, rather than hanging until the
            // request times out.
            mine.completeExceptionally(e);
        } finally {
            inFlight.compareAndSet(mine, null);
        }
        return mine;
    }

    /** A failed build reaches the caller as the exception it was, not wrapped in a join. */
    private static Map<String, Object> await(CompletableFuture<Map<String, Object>> generation) {
        try {
            return generation.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Drops the cache — used by {@code POST /api/brief/refresh} and by tests. */
    public void invalidate() {
        cache.set(null);
    }

    // ---- assembly ---------------------------------------------------------

    private Map<String, Object> build() {
        Instant now = clock.now();

        CompletableFuture<Fetched> inbox = fetch("gmail.listToday", Map.of("maxResults", 15));
        CompletableFuture<Fetched> work = fetch("jira.listMyIssues", Map.of("maxResults", 15));
        CompletableFuture<Fetched> pulls = fetch("github.listMyPullRequests", Map.of("maxResults", 15));
        CompletableFuture<Fetched> codeIssues = fetch("github.listMyIssues", Map.of("maxResults", 15));
        CompletableFuture<Fetched> calendar = fetch("calendar.listToday", Map.of("maxResults", 20));

        String jiraBase = baseUrlOf("jira");

        Fetched inboxResult = inbox.join();
        Fetched workResult = work.join();
        Fetched pullsResult = pulls.join();
        Fetched issuesResult = codeIssues.join();
        Fetched calendarResult = calendar.join();

        List<BriefItem> inboxItems = OK.equals(inboxResult.status())
                ? gmailItems(inboxResult.data(), now) : List.of();
        List<BriefItem> workItems = OK.equals(workResult.status())
                ? jiraItems(workResult.data(), jiraBase, now) : List.of();
        List<BriefItem> codeItems = new ArrayList<>();
        if (OK.equals(pullsResult.status())) {
            codeItems.addAll(githubItems(pullsResult.data(), "pullRequests", "pr", now));
        }
        if (OK.equals(issuesResult.status())) {
            codeItems.addAll(githubItems(issuesResult.data(), "issues", "issue", now));
        }
        List<BriefItem> calendarItems = OK.equals(calendarResult.status())
                ? calendarItems(calendarResult.data()) : List.of();

        // The priority lane looks at what the user can act on. Calendar is context, not a task.
        // Interleaved, not concatenated: the insight layer only reads the first handful, and
        // a full inbox used to consume every slot — live, fifteen mails pushed the assigned
        // Jira issue and six pull requests out of the priority lane entirely.
        List<BriefItem> analysed = interleave(inboxItems, workItems, codeItems);

        InsightService.Result insight = insights.analyze(analysed, projectKeyFrom(workItems));

        // The day as a whole: one paragraph, an order and one piece of advice. Absent
        // whenever the model cannot write it — see DigestService.
        List<BriefItem> forDigest = new ArrayList<>(analysed);
        forDigest.addAll(calendarItems);
        Optional<DigestService.Digest> digest = digests.digest(forDigest, insight.insights());

        Map<String, Object> llmInfo = new LinkedHashMap<>();
        llmInfo.put("provider", llm.name());
        llmInfo.put("degraded", llm.degraded());
        llmInfo.put("source", insight.source());
        llmInfo.put("tokens", insight.tokens() + digest.map(DigestService.Digest::tokens).orElse(0L));
        llmInfo.put("costUsd", insight.costUsd() + digest.map(DigestService.Digest::costUsd).orElse(0.0));

        // Counted, not written: the one line that answers "bugün ne var" must not vanish
        // with the token budget. See DayTally.
        int urgent = 0;
        for (InsightService.Insight card : insight.insights()) {
            if ("high".equals(card.urgency())) {
                urgent++;
            }
        }
        DayTally tally = DayTally.of(inboxItems, workItems, codeItems, calendarItems, urgent);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", now.toString());
        body.put("localDate", java.time.LocalDate.ofInstant(now, zone).toString());
        body.put("timezone", zone.getId());
        body.put("generatedAt", now.toString());
        body.put("cached", false);
        body.put("cachedAt", null);
        body.put("ttlSeconds", cacheTtl.toSeconds());
        body.put("llm", llmInfo);
        body.put("today", tally.view());
        // Additive: the key is simply absent when there is no digest, so a client that does
        // not know about it renders exactly what it rendered before.
        digest.ifPresent(value -> body.put("digest", value.view()));
        body.put("priority", priorityCards(analysed, insight));
        body.put("inbox", section(inboxResult, inboxItems, "gmail"));
        body.put("work", section(workResult, workItems, "jira"));
        body.put("code", codeSection(pullsResult, issuesResult, codeItems));
        body.put("calendar", section(calendarResult, calendarItems, "calendar"));
        return body;
    }

    // ---- tool fetch -------------------------------------------------------

    private CompletableFuture<Fetched> fetch(String toolName, Map<String, Object> params) {
        String provider = providerLabel(toolName.substring(0, Math.max(0, toolName.indexOf('.'))));
        Tool tool = tools.find(toolName).orElse(null);
        if (tool == null) {
            return CompletableFuture.completedFuture(new Fetched(UNAVAILABLE, null,
                    provider + " entegrasyonu henüz bağlı değil.", null, 0));
        }
        return CompletableFuture
                .supplyAsync(() -> run(tool, params, provider), executor)
                .completeOnTimeout(new Fetched(ERROR, null,
                                provider + " zamanında yanıt vermedi.", null, toolTimeout.toMillis()),
                        toolTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    LOG.log(Level.WARNING, "brief: " + toolName + " failed", e);
                    return new Fetched(ERROR, null, provider + " çağrısı başarısız oldu.", null, 0);
                });
    }

    private Fetched run(Tool tool, Map<String, Object> params, String provider) {
        Connection connection = connections.findByProvider(tool.provider()).orElse(null);
        ToolResult result = tool.execute(Json.toNode(params), connection);
        if (!result.ok()) {
            LOG.log(Level.WARNING, "brief: {0} failed: {1}", tool.name(), result.error());
            return new Fetched(ERROR, null, failureReason(provider, result.error()),
                    result.mode(), result.durationMs());
        }
        if ("replay (no connection)".equals(result.mode())) {
            // Live deployment, provider not connected: say so instead of passing off
            // demo fixtures as the user's real inbox.
            return new Fetched(UNAVAILABLE, null,
                    provider + " bağlı değil — Ayarlar'dan bağlayabilirsin.",
                    result.mode(), result.durationMs());
        }
        return new Fetched(OK, result.data(), null, result.mode(), result.durationMs());
    }

    /**
     * Provider errors are shown to the user verbatim, so they are translated here and
     * never passed through: a raw message can carry a URL, a request body or a token.
     */
    public static String failureReason(String provider, String raw) {
        String message = raw == null ? "" : raw;
        Matcher matcher = HTTP_STATUS.matcher(message);
        if (matcher.find()) {
            int status = Integer.parseInt(matcher.group(1));
            if (status == 401 || status == 403) {
                return provider + " kimlik bilgilerini kabul etmedi (HTTP " + status
                        + ") — Ayarlar'dan bağlantıyı yenile.";
            }
            if (status == 404) {
                return provider + " istenen kaydı bulamadı (HTTP 404).";
            }
            if (status == 429) {
                return provider + " hız sınırına takıldı (HTTP 429) — birazdan tekrar dene.";
            }
            if (status >= 500) {
                return provider + " şu an yanıt veremiyor (HTTP " + status + ").";
            }
            return provider + " çağrısı HTTP " + status + " ile başarısız oldu.";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return provider + " zamanında yanıt vermedi.";
        }
        if (lower.contains("invalid params")) {
            return provider + " çağrısı geçersiz parametrelerle reddedildi.";
        }
        return provider + " çağrısı başarısız oldu.";
    }

    private static String providerLabel(String provider) {
        return switch (provider) {
            case "gmail" -> "Gmail";
            case "calendar", "google" -> "Google Takvim";
            case "github" -> "GitHub";
            case "jira" -> "Jira";
            case "slack" -> "Slack";
            default -> provider == null || provider.isBlank() ? "Entegrasyon" : provider;
        };
    }

    // ---- sections ---------------------------------------------------------

    private Map<String, Object> section(Fetched fetched, List<BriefItem> items, String provider) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", fetched.status());
        map.put("reason", fetched.reason());
        map.put("count", items.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        items.forEach(item -> rows.add(item.row()));
        map.put("items", rows);
        map.put("provider", provider);
        map.put("mode", fetched.mode());
        map.put("durationMs", fetched.durationMs());
        return map;
    }

    /** Two tools feed one card: whichever answered wins, and both failing is still one status. */
    private Map<String, Object> codeSection(Fetched pulls, Fetched issues, List<BriefItem> items) {
        String status = OK.equals(pulls.status()) || OK.equals(issues.status()) ? OK
                : ERROR.equals(pulls.status()) || ERROR.equals(issues.status()) ? ERROR
                : UNAVAILABLE;
        String reason = OK.equals(status) ? null : firstReason(pulls, issues);
        long duration = Math.max(pulls.durationMs(), issues.durationMs());
        String mode = pulls.mode() != null ? pulls.mode() : issues.mode();

        Map<String, Object> map = section(new Fetched(status, null, reason, mode, duration),
                items, "github");
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("pullRequests", pulls.status());
        parts.put("issues", issues.status());
        map.put("parts", parts);
        return map;
    }

    private static String firstReason(Fetched... fetched) {
        for (Fetched f : fetched) {
            if (f.reason() != null) {
                return f.reason();
            }
        }
        return null;
    }

    /** The AI lane: a flat array of cards, highest urgency first. */
    private List<Map<String, Object>> priorityCards(List<BriefItem> items, InsightService.Result result) {
        Map<String, InsightService.Insight> byId = new LinkedHashMap<>();
        result.insights().forEach(insight -> byId.put(insight.itemId(), insight));

        List<BriefItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingInt(item -> urgencyRank(byId.get(item.id()))));

        List<Map<String, Object>> cards = new ArrayList<>();
        for (BriefItem item : ordered) {
            InsightService.Insight insight = byId.get(item.id());
            if (insight == null) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", item.id());
            card.put("source", item.source());
            card.put("title", item.title());
            card.put("from", item.from());
            card.put("kind", insight.kind());
            card.put("urgency", insight.urgency());
            card.put("summary", insight.summary());
            List<Map<String, Object>> actions = new ArrayList<>();
            insight.actions().forEach(action -> actions.add(action.view()));
            card.put("suggestedActions", actions);
            card.put("url", item.url());
            card.put("subtitle", item.subtitle());
            cards.add(card);
            if (cards.size() >= PRIORITY_LIMIT) {
                break;
            }
        }
        return cards;
    }

    private static int urgencyRank(InsightService.Insight insight) {
        if (insight == null) {
            return 9;
        }
        return switch (insight.urgency()) {
            case "high" -> 0;
            case "normal" -> insight.actions().isEmpty() ? 3 : 1;
            default -> insight.actions().isEmpty() ? 5 : 4;
        };
    }

    // ---- normalisation ----------------------------------------------------

    /**
     * Round-robins the sections so every source reaches the insight layer.
     *
     * <p>Order matters downstream: the classifier reads only the first items, so a busy
     * inbox otherwise decides the whole priority lane on its own.
     */
    @SafeVarargs
    private static List<BriefItem> interleave(List<BriefItem>... sections) {
        List<BriefItem> out = new ArrayList<>();
        int longest = 0;
        for (List<BriefItem> section : sections) {
            longest = Math.max(longest, section.size());
        }
        for (int index = 0; index < longest; index++) {
            for (List<BriefItem> section : sections) {
                if (index < section.size()) {
                    out.add(section.get(index));
                }
            }
        }
        return out;
    }

    private List<BriefItem> gmailItems(JsonNode data, Instant now) {
        List<BriefItem> out = new ArrayList<>();
        for (JsonNode message : data.path("messages")) {
            String id = message.path("id").asText("");
            String from = message.path("from").asText("");
            boolean unread = message.path("unread").asBoolean(false);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("messageId", id);
            // The handle a reply draft hangs on — without it gmail.createDraft can only
            // start a new conversation next to the one it is answering.
            ref.put("threadId", message.path("threadId").asText(id));
            ref.put("from", from);
            ref.put("subject", message.path("subject").asText(""));
            ref.put("snippet", message.path("snippet").asText(""));
            // Carried through so the insight layer can tell a person from a mailing list.
            ref.put("bulk", message.path("bulk").asBoolean(false));
            String at = message.path("receivedAt").asText("");
            out.add(new BriefItem("gmail:" + id, "gmail", "mail", "",
                    message.path("subject").asText("(konusuz)"),
                    person(from), relative(at, now), person(from),
                    "https://mail.google.com/mail/u/0/#inbox/" + id, at,
                    unread ? BriefItem.WARN : BriefItem.DEFAULT, ref));
        }
        return out;
    }

    private List<BriefItem> jiraItems(JsonNode data, String baseUrl, Instant now) {
        List<BriefItem> out = new ArrayList<>();
        for (JsonNode issue : data.path("issues")) {
            String key = issue.path("key").asText("");
            JsonNode fields = issue.path("fields");
            String status = fields.path("status").path("name").asText("");
            String priority = fields.path("priority").path("name").asText("");
            String assignee = fields.path("assignee").path("displayName").asText("");
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("issueKey", key);
            ref.put("status", status);
            ref.put("priority", priority);
            ref.put("projectKey", key.contains("-") ? key.substring(0, key.indexOf('-')) : key);
            String at = fields.path("updated").asText("");
            String subtitle = join(status, assignee.isBlank() ? priority : assignee);
            out.add(new BriefItem("jira:" + key, "jira", "issue", key,
                    fields.path("summary").asText(""), subtitle, relative(at, now),
                    assignee.isBlank() ? key : assignee,
                    baseUrl.isBlank() ? "" : baseUrl + "/browse/" + key, at,
                    jiraTone(status, priority), ref));
        }
        return out;
    }

    private static String jiraTone(String status, String priority) {
        String s = status.toLowerCase(Locale.ROOT);
        if (s.contains("blocked") || s.contains("engel")) {
            return BriefItem.DANGER;
        }
        if (s.contains("done") || s.contains("tamam")) {
            return BriefItem.SUCCESS;
        }
        String p = priority.toLowerCase(Locale.ROOT);
        return p.contains("highest") || p.contains("high") ? BriefItem.WARN : BriefItem.DEFAULT;
    }

    private List<BriefItem> githubItems(JsonNode data, String field, String kind, Instant now) {
        List<BriefItem> out = new ArrayList<>();
        for (JsonNode node : data.path(field)) {
            String repo = node.path("repo").asText("");
            int number = node.path("number").asInt();
            String reason = node.path("reason").asText("");
            String author = node.path("author").asText("");
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("repo", repo);
            ref.put("number", number);
            ref.put("reason", reason);
            String at = node.path("updatedAt").asText("");
            boolean bug = false;
            for (JsonNode label : node.path("labels")) {
                bug = bug || label.asText("").toLowerCase(Locale.ROOT).contains("bug");
            }
            String tone = bug ? BriefItem.DANGER
                    : "review_requested".equals(reason) ? BriefItem.WARN : BriefItem.DEFAULT;
            out.add(new BriefItem("github-" + kind + ":" + repo + "#" + number, "github", kind,
                    repo + "#" + number, node.path("title").asText(""),
                    join(reasonLabel(reason), author), relative(at, now),
                    author.isBlank() ? repo : author,
                    node.path("url").asText(""), at, tone, ref));
        }
        return out;
    }

    private static String reasonLabel(String reason) {
        return switch (reason) {
            case "review_requested" -> "review bekliyor";
            case "author" -> "senin PR'ın";
            case "assigned" -> "sana atandı";
            default -> reason;
        };
    }

    private List<BriefItem> calendarItems(JsonNode data) {
        List<BriefItem> out = new ArrayList<>();
        for (JsonNode event : data.path("events")) {
            String id = event.path("id").asText("");
            String start = event.path("start").asText("");
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("eventId", id);
            ref.put("start", start);
            ref.put("end", event.path("end").asText(""));
            ref.put("meetingUrl", event.path("meetingUrl").asText(""));
            String location = event.path("location").asText("");
            List<String> attendees = new ArrayList<>();
            event.path("attendees").forEach(a -> attendees.add(a.asText("")));
            String subtitle = location.isBlank()
                    ? (attendees.isEmpty() ? "" : String.join(", ", attendees)) : location;
            out.add(new BriefItem("calendar:" + id, "calendar", "event", "",
                    event.path("title").asText(""), subtitle, clockLabel(start),
                    attendees.isEmpty() ? "" : attendees.get(0),
                    event.path("url").asText(""), start, BriefItem.DEFAULT, ref));
        }
        return out;
    }

    // ---- small helpers ----------------------------------------------------

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " · " + second;
    }

    /** {@code "Ayşe Yıldız <ayse@x.dev>"} → {@code Ayşe Yıldız}. */
    static String person(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int bracket = raw.indexOf('<');
        String name = bracket > 0 ? raw.substring(0, bracket) : raw;
        return name.trim().replaceAll("^\"|\"$", "");
    }

    /** {@code 2026-07-31T14:00:00+03:00} → {@code 14:00}. */
    /**
     * The clock the user reads, in the user's own zone.
     *
     * <p>This used to slice the characters after the {@code T} straight out of the ISO
     * string, which is the instant's UTC clock: a 05:00 Istanbul meeting was shown as
     * "02:00". Google returns whatever offset the event carries, so the string can never be
     * read as a local time — it has to be converted.
     */
    String clockLabel(String isoStart) {
        if (isoStart == null || isoStart.isBlank()) {
            return "";
        }
        try {
            return java.time.OffsetDateTime.parse(isoStart).atZoneSameInstant(zone)
                    .toLocalTime().toString().substring(0, 5);
        } catch (RuntimeException e) {
            // An all-day event is a plain date with no clock at all — nothing to show.
            int t = isoStart.indexOf('T');
            return t > 0 && isoStart.length() >= t + 6 ? isoStart.substring(t + 1, t + 6) : "";
        }
    }

    /** Turkish relative time for the right-aligned meta text. */
    static String relative(String iso, Instant now) {
        Instant then = parse(iso);
        if (then == null) {
            return "";
        }
        long minutes = Duration.between(then, now).toMinutes();
        if (minutes < 0) {
            long ahead = -minutes;
            if (ahead < 60) {
                return ahead + "dk sonra";
            }
            return ahead < 24 * 60 ? (ahead / 60) + "sa sonra" : (ahead / 1440) + "g sonra";
        }
        if (minutes < 1) {
            return "az önce";
        }
        if (minutes < 60) {
            return minutes + "dk önce";
        }
        if (minutes < 24 * 60) {
            return (minutes / 60) + "sa önce";
        }
        return (minutes / 1440) + "g önce";
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(iso).toInstant();
            } catch (RuntimeException alsoIgnored) {
                try {
                    // Jira stamps offsets without a colon: 2026-07-31T08:12:00.000+0300
                    return OffsetDateTime.parse(iso, JIRA_STAMP).toInstant();
                } catch (RuntimeException e) {
                    return null;
                }
            }
        }
    }

    private static final java.time.format.DateTimeFormatter JIRA_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z");

    /**
     * Which project a "Jira ticket aç" suggestion should target.
     *
     * <p>An issue already in today's brief is the best evidence — that is where this person's
     * work lives. Next best is the project key on the Jira connection: the user typed it, so
     * it exists and they can write to it. {@code defaultProjectKey} is last on purpose. It is
     * a config default ("RELAY") that matched no real project on the deployed instance, so
     * every suggestion came pre-loaded with a project key Jira answers 404 to.
     */
    private String projectKeyFrom(List<BriefItem> workItems) {
        for (BriefItem item : workItems) {
            Object key = item.ref().get("projectKey");
            if (key != null && !String.valueOf(key).isBlank()) {
                return String.valueOf(key);
            }
        }
        String configured = connections.findByProvider("jira")
                .map(connection -> connection.getOrDefault("projectKey",
                        connection.getOrDefault("defaultProject", "")))
                .orElse("");
        return configured.isBlank() ? defaultProjectKey : configured.trim().toUpperCase(Locale.ROOT);
    }

    private String baseUrlOf(String provider) {
        return connections.findByProvider(provider)
                .map(connection -> {
                    String url = connection.getOrDefault("baseUrl", "");
                    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                })
                .orElse("");
    }

    private static ZoneId zoneOf(String raw) {
        try {
            return ZoneId.of(raw == null || raw.isBlank() ? "Europe/Istanbul" : raw);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "unknown timezone {0}, falling back to Europe/Istanbul", raw);
            return ZoneId.of("Europe/Istanbul");
        }
    }
}
