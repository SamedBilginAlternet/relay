package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Jira Cloud, authenticated with e-mail + API token (HTTP basic) — no OAuth dance.
 *
 * <p>Connection config keys: {@code baseUrl}, {@code email}, {@code apiToken}.
 * Each action is its own {@code Tool} so the registry, the policy engine and the LLM
 * all address them individually.
 */
public abstract class JiraTool extends AbstractTool {

    protected JiraTool(ToolsMode mode, FixtureStore fixtures) {
        super(mode, fixtures);
    }

    @Override
    protected boolean usable(Connection connection) {
        return notBlank(connection.get("baseUrl")) && notBlank(connection.get("email"))
                && notBlank(connection.get("apiToken"));
    }

    protected Map<String, String> headers(Connection connection) {
        String basic = Base64.getEncoder().encodeToString(
                (connection.get("email") + ":" + connection.get("apiToken")).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Basic " + basic);
        return headers;
    }

    protected String base(Connection connection) {
        String url = connection.get("baseUrl").trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Guarantees the query is <em>bounded</em>.
     *
     * <p>{@code /search/jql} answers HTTP 400 ("Unbounded JQL queries are not allowed here")
     * to anything without a restricting clause, and the planner is free to emit exactly that
     * — {@code ORDER BY updated DESC} on its own is a plausible plan and a guaranteed failure
     * mid-run. {@code project is not EMPTY} restricts nothing in practice but always satisfies
     * the validator, so we AND it in front of whatever came in.
     */
    protected static String bound(String jql) {
        String query = jql == null ? "" : jql.trim();
        int orderAt = lastOrderBy(query);
        String where = orderAt < 0 ? query : query.substring(0, orderAt).trim();
        String order = orderAt < 0 ? "" : " " + query.substring(orderAt).trim();
        return where.isEmpty()
                ? "project is not EMPTY" + order
                : "project is not EMPTY AND (" + where + ")" + order;
    }

    /** Index of the trailing {@code order by}, or -1 when the query has none. */
    private static int lastOrderBy(String query) {
        String lower = query.toLowerCase();
        int at = lower.lastIndexOf("order by");
        // Inside parentheses it would belong to a subquery, not to this query's tail.
        if (at < 0 || query.substring(at).contains(")")) {
            return -1;
        }
        return at;
    }

    /**
     * Finds the transition that gets an issue to {@code target}.
     *
     * <p>The model says "Done" but a Turkish board offers "Bitti", and a board in any
     * language names its transitions however its admin felt that day. Exact match first,
     * then the same meaning, then a prefix — anything looser would pick a wrong transition,
     * and moving an issue to the wrong column is worse than refusing.
     *
     * @return the transition id, or {@code null} when nothing matches
     */
    static String matchTransition(JsonNode transitions, String target) {
        String wanted = normalise(target);
        String wantedGroup = synonymGroup(wanted);

        for (int pass = 0; pass < 3; pass++) {
            for (JsonNode transition : transitions) {
                String name = normalise(transition.path("name").asText(""));
                String toName = normalise(transition.path("to").path("name").asText(""));
                boolean hit = switch (pass) {
                    case 0 -> name.equals(wanted) || toName.equals(wanted);
                    case 1 -> wantedGroup != null
                            && (wantedGroup.equals(synonymGroup(name)) || wantedGroup.equals(synonymGroup(toName)));
                    default -> !wanted.isEmpty()
                            && (name.startsWith(wanted) || toName.startsWith(wanted));
                };
                if (hit) {
                    return transition.path("id").asText();
                }
            }
        }
        return null;
    }

    /** What the board actually offers right now — shown to the user when nothing matched. */
    private static String names(JsonNode transitions) {
        List<String> out = new ArrayList<>();
        for (JsonNode transition : transitions) {
            String to = transition.path("to").path("name").asText("");
            out.add(to.isBlank() ? transition.path("name").asText("") : to);
        }
        return out.isEmpty() ? "(hiçbiri)" : String.join(", ", out);
    }

    /** Lowercase without Turkish-specific casing traps, punctuation or spacing. */
    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.trim().toLowerCase(Locale.ROOT)
                .replace('ı', 'i').replace('İ', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c');
        return lower.replaceAll("[^a-z0-9]", "");
    }

    /** Status names that mean the same thing across languages and board conventions. */
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("done", "done"), Map.entry("bitti", "done"), Map.entry("tamamlandi", "done"),
            Map.entry("tamam", "done"), Map.entry("closed", "done"), Map.entry("kapali", "done"),
            Map.entry("kapandi", "done"), Map.entry("resolved", "done"), Map.entry("cozuldu", "done"),
            Map.entry("complete", "done"), Map.entry("completed", "done"),
            Map.entry("inprogress", "progress"), Map.entry("devamediyor", "progress"),
            Map.entry("yapiliyor", "progress"), Map.entry("basladi", "progress"),
            Map.entry("todo", "todo"), Map.entry("yapilacak", "todo"), Map.entry("acik", "todo"),
            Map.entry("open", "todo"), Map.entry("backlog", "todo"));

    private static String synonymGroup(String normalised) {
        return SYNONYMS.get(normalised);
    }

    // ------------------------------------------------------------ error text

    /**
     * Sends a Jira request, and rewrites a rejection into a sentence a person can act on.
     *
     * <p>Atlassian answers a bad create with {@code {"errors":{"issuetype":"Geçerli bir konu
     * türü belirtin"}}} — the field name is right there, and dumping the JSON at the user
     * throws it away. A 401 body is worse than useless: it can carry back the credential it
     * just refused, so it never leaves this method.
     */
    protected static JsonNode jira(String method, String url, Map<String, String> headers, Object body)
            throws Exception {
        try {
            return HttpJson.send(method, url, headers, body);
        } catch (HttpJson.ToolCallException e) {
            if (e.status() == 0) {
                throw e;
            }
            throw new HttpJson.ToolCallException(explain(e.status(), e.body()), e.status(), e.body());
        }
    }

    /** The human sentence for one Atlassian rejection. Never contains the raw body. */
    static String explain(int status, String body) {
        if (status == 401 || status == 403) {
            return "Jira kimlik doğrulaması reddedildi (HTTP " + status
                    + "). Bağlantı ayarlarındaki e-posta ve API token'ı kontrol edin.";
        }
        List<String> reasons = reasons(body);
        String head = switch (status) {
            case 404 -> "Jira böyle bir kayıt bulamadı (HTTP 404)";
            case 429 -> "Jira istek sınırına takıldı (HTTP 429), biraz sonra tekrar deneyin";
            default -> "Jira isteği reddetti (HTTP " + status + ")";
        };
        return reasons.isEmpty() ? head + "." : head + ": " + String.join("; ", reasons);
    }

    /** {@code errorMessages} verbatim, {@code errors} as "alan: sebep". */
    static List<String> reasons(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        JsonNode parsed;
        try {
            parsed = Json.parse(body);
        } catch (RuntimeException e) {
            return out;
        }
        for (JsonNode message : parsed.path("errorMessages")) {
            String text = message.asText("").trim();
            if (!text.isEmpty()) {
                out.add(HttpJson.redact(text));
            }
        }
        JsonNode errors = parsed.path("errors");
        errors.fieldNames().forEachRemaining(field ->
                out.add(fieldLabel(field) + ": " + HttpJson.redact(errors.path(field).asText("").trim())));
        return out;
    }

    /** Field names as the person filling the form knows them. */
    private static final Map<String, String> FIELD_LABELS = Map.of(
            "issuetype", "konu türü (issuetype)", "project", "proje (project)",
            "summary", "özet (summary)", "description", "açıklama (description)",
            "priority", "öncelik (priority)", "assignee", "atanan (assignee)",
            "reporter", "bildiren (reporter)", "labels", "etiketler (labels)",
            "duedate", "bitiş tarihi (duedate)", "parent", "üst kayıt (parent)");

    private static String fieldLabel(String field) {
        return FIELD_LABELS.getOrDefault(field.toLowerCase(Locale.ROOT), field);
    }

    // ------------------------------------------------------------ issue types

    /**
     * Picks the issue type the project actually offers.
     *
     * <p>"Bug" is not a Jira constant, it is one project's name for a kind of work. A Turkish
     * board offers "Hata"/"Görev", a team-managed project renames them again, and posting the
     * literal string the model produced is how {@code jira.createIssue} came to answer
     * "issuetype: Geçerli bir konu türü belirtin" on every single card.
     *
     * <p>Sub-task types are excluded unless one was asked for: they need a parent and would
     * fail the create for a second, more confusing reason.
     *
     * @return the matching type node, or {@code null} when the project offers nothing like it
     */
    static JsonNode matchIssueType(JsonNode types, String wanted) {
        String target = normalise(wanted);
        String group = typeGroup(target);
        boolean wantsSubtask = "subtask".equals(group);

        for (int pass = 0; pass < 3; pass++) {
            for (JsonNode type : types) {
                if (type.path("subtask").asBoolean(false) != wantsSubtask) {
                    continue;
                }
                String name = normalise(type.path("name").asText(""));
                boolean hit = switch (pass) {
                    case 0 -> !target.isEmpty() && name.equals(target);
                    case 1 -> group != null && group.equals(typeGroup(name));
                    default -> !target.isEmpty() && !name.isEmpty()
                            && (name.startsWith(target) || target.startsWith(name));
                };
                if (hit) {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * The type to fall back on: the project's own "task" flavour, else its first non-sub-task.
     *
     * <p>Refusing would be the safer instinct — it is the right one for a transition, where the
     * wrong column is a lie about the state of the work. Here it is not: a ticket filed under
     * a neighbouring type is one dropdown away from correct, and no ticket at all is the bug
     * being reported.
     */
    static JsonNode defaultIssueType(JsonNode types) {
        JsonNode first = null;
        for (JsonNode type : types) {
            if (type.path("subtask").asBoolean(false)) {
                continue;
            }
            if ("task".equals(typeGroup(normalise(type.path("name").asText(""))))) {
                return type;
            }
            if (first == null) {
                first = type;
            }
        }
        return first;
    }

    /** The names a project can give the same kind of work. */
    private static final Map<String, String> TYPE_SYNONYMS = Map.ofEntries(
            Map.entry("task", "task"), Map.entry("gorev", "task"), Map.entry("is", "task"),
            Map.entry("isemri", "task"), Map.entry("todo", "task"),
            Map.entry("bug", "bug"), Map.entry("hata", "bug"), Map.entry("defect", "bug"),
            Map.entry("ariza", "bug"), Map.entry("sorun", "bug"), Map.entry("problem", "bug"),
            Map.entry("story", "story"), Map.entry("hikaye", "story"),
            Map.entry("userstory", "story"), Map.entry("kullanicihikayesi", "story"),
            Map.entry("epic", "epic"), Map.entry("epik", "epic"),
            Map.entry("subtask", "subtask"), Map.entry("altgorev", "subtask"),
            Map.entry("altkonu", "subtask"), Map.entry("altis", "subtask"));

    private static String typeGroup(String normalised) {
        return TYPE_SYNONYMS.get(normalised);
    }

    /**
     * The issue types this project offers, straight from {@code createmeta}.
     *
     * @return the type array, or {@code null} when the question could not be asked — a
     *         missing answer must not block the create, only a wrong project must
     * @throws HttpJson.ToolCallException when the project itself does not exist
     */
    protected JsonNode issueTypes(Connection connection, String project) throws Exception {
        String url = base(connection) + "/rest/api/3/issue/createmeta/"
                + HttpJson.encode(project) + "/issuetypes?maxResults=100";
        JsonNode response;
        try {
            response = HttpJson.send("GET", url, headers(connection), null);
        } catch (HttpJson.ToolCallException e) {
            if (e.status() == 404) {
                throw new HttpJson.ToolCallException(unknownProject(connection, project), 404, e.body());
            }
            // 401/403/5xx: the create call is about to hit the same wall and will say so.
            return null;
        }
        JsonNode values = response.has("values") ? response.path("values") : response.path("issueTypes");
        return values.isArray() && !values.isEmpty() ? values : null;
    }

    /** "There is no such project" is only useful next to the ones there are. */
    private String unknownProject(Connection connection, String project) {
        String message = "Jira'da '" + project + "' anahtarlı bir proje yok "
                + "(ya da bu API token onu görmüyor).";
        try {
            JsonNode found = HttpJson.send("GET",
                    base(connection) + "/rest/api/3/project/search?maxResults=20",
                    headers(connection), null);
            List<String> keys = new ArrayList<>();
            for (JsonNode candidate : found.path("values")) {
                String key = candidate.path("key").asText("");
                String name = candidate.path("name").asText("");
                if (!key.isBlank()) {
                    keys.add(name.isBlank() ? key : key + " (" + name + ")");
                }
            }
            if (!keys.isEmpty()) {
                return message + " Erişilebilen projeler: " + String.join(", ", keys)
                        + ". Bağlantı ayarlarındaki proje anahtarını düzeltin.";
            }
        } catch (Exception e) {
            // Listing the projects is a courtesy; failing at it must not replace the real error.
        }
        return message;
    }

    /** Type names as the project spells them — quoted back when a request could not be met. */
    static String typeNames(JsonNode types) {
        List<String> out = new ArrayList<>();
        for (JsonNode type : types) {
            String name = type.path("name").asText("");
            if (!name.isBlank() && !type.path("subtask").asBoolean(false)) {
                out.add(name);
            }
        }
        return out.isEmpty() ? "(hiçbiri)" : String.join(", ", out);
    }

    /**
     * The project key to file under: what was asked for, else the one the connection was
     * configured with.
     *
     * <p>The brief's suggestion carries a project key that came from a config default, and it
     * is wrong as often as it is right. The connection is what the user actually set up.
     */
    protected static String projectKey(JsonNode params, Connection connection) {
        String asked = params.path("projectKey").asText("").trim();
        if (!asked.isEmpty()) {
            return asked.toUpperCase(Locale.ROOT);
        }
        String configured = connection == null ? null : firstConfigured(connection, "projectKey", "defaultProject");
        return configured == null ? "" : configured.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstConfigured(Connection connection, String... keys) {
        for (String key : keys) {
            String value = connection.get(key);
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /** Atlassian Document Format wrapper for a plain-text comment body. */
    protected static ObjectNode adf(String text) {
        ObjectNode doc = Json.object();
        doc.put("type", "doc");
        doc.put("version", 1);
        ObjectNode paragraph = doc.putArray("content").addObject();
        paragraph.put("type", "paragraph");
        ObjectNode textNode = paragraph.putArray("content").addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        return doc;
    }

    // ------------------------------------------------------------ searchIssues

    @Component
    public static class SearchIssues extends JiraTool {

        public SearchIssues(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "jira.searchIssues";
        }

        @Override
        public String description() {
            return "Search Jira issues with JQL. Returns key, summary, status and assignee for each match.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("jql");
            ObjectNode props = schema.putObject("properties");
            ObjectNode jql = props.putObject("jql");
            jql.put("type", "string");
            jql.put("description", "JQL query, e.g. \"sprint in openSprints() AND status != Done\"");
            ObjectNode max = props.putObject("maxResults");
            max.put("type", "integer");
            max.put("minimum", 1);
            max.put("maximum", 50);
            max.put("description", "How many issues to return (default 10)");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String jql = bound(params.path("jql").asText());
            int max = params.path("maxResults").asInt(10);
            // /rest/api/3/search was REMOVED by Atlassian (HTTP 410, CHANGE-2046).
            // The replacement is /rest/api/3/search/jql, which takes the same query
            // parameters but returns `isLast` instead of `total` for pagination.
            String url = base(connection) + "/rest/api/3/search/jql?jql=" + HttpJson.encode(jql)
                    + "&maxResults=" + max + "&fields=summary,status,assignee,priority";
            return jira("GET", url, headers(connection), null);
        }
    }

    // ------------------------------------------------------------ listMyIssues

    @Component
    public static class ListMyIssues extends JiraTool {

        public ListMyIssues(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "jira.listMyIssues";
        }

        @Override
        public String description() {
            return "List the open Jira issues assigned to me (assignee = currentUser() AND status != Done). "
                    + "This is the 'work on my plate' section of the daily brief.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            ObjectNode max = schema.putObject("properties").putObject("maxResults");
            max.put("type", "integer");
            max.put("minimum", 1);
            max.put("maximum", 50);
            max.put("description", "How many issues to return (default 15)");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            int max = params.path("maxResults").asInt(15);
            String jql = "assignee = currentUser() AND status != Done ORDER BY updated DESC";
            // /rest/api/3/search is gone (HTTP 410, CHANGE-2046) — /search/jql replaced it.
            String url = base(connection) + "/rest/api/3/search/jql?jql=" + HttpJson.encode(jql)
                    + "&maxResults=" + max + "&fields=summary,status,assignee,priority,updated,issuetype";
            return jira("GET", url, headers(connection), null);
        }
    }

    // ------------------------------------------------------------ createIssue

    @Component
    public static class CreateIssue extends JiraTool {

        public CreateIssue(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "jira.createIssue";
        }

        @Override
        public String description() {
            return "Create a new Jira issue in a project (bug, task, story…). "
                    + "The main action of the daily brief. Requires approval by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("projectKey").add("summary");
            ObjectNode props = schema.putObject("properties");
            ObjectNode project = props.putObject("projectKey");
            project.put("type", "string");
            project.put("description", "Project key the issue belongs to, e.g. RELAY or KAN");
            ObjectNode type = props.putObject("issueType");
            type.put("type", "string");
            type.put("description", "Issue type name — Bug, Task, Story… (default Task)");
            ObjectNode summary = props.putObject("summary");
            summary.put("type", "string");
            summary.put("minLength", 3);
            summary.put("description", "One line title of the issue");
            ObjectNode description = props.putObject("description");
            description.put("type", "string");
            description.put("description", "Body text (plain text — converted to ADF)");
            ObjectNode priority = props.putObject("priority");
            priority.put("type", "string");
            priority.put("description", "Priority name, e.g. Highest / High / Medium — omit for the project default");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String project = projectKey(params, connection);
            if (project.isEmpty()) {
                throw new HttpJson.ToolCallException("Kaydın hangi projede açılacağı belli değil: "
                        + "projectKey boş ve Jira bağlantısında varsayılan proje anahtarı yok.");
            }

            String requestedType = params.path("issueType").asText("").trim();
            JsonNode available = issueTypes(connection, project);
            String note = null;

            ObjectNode fields = Json.object();
            fields.putObject("project").put("key", project);
            String typeName = "";
            if (available == null) {
                // createmeta is unavailable (older Jira, or the token cannot read it).
                // Send the name and let the create call be the one that judges it.
                typeName = requestedType.isEmpty() ? "Task" : requestedType;
                fields.putObject("issuetype").put("name", typeName);
            } else {
                JsonNode chosen = matchIssueType(available, requestedType);
                if (chosen == null) {
                    chosen = defaultIssueType(available);
                    if (chosen == null) {
                        throw new HttpJson.ToolCallException(project
                                + " projesinde kayıt açılabilecek bir konu türü yok.");
                    }
                    if (!requestedType.isEmpty()) {
                        note = "'" + requestedType + "' bu projede yok; '"
                                + chosen.path("name").asText() + "' kullanıldı. "
                                + project + " projesindeki türler: " + typeNames(available);
                    }
                }
                typeName = chosen.path("name").asText("");
                // By id, not by name: the id is what the project actually keys on, and two
                // types can share a display name across a site.
                fields.putObject("issuetype").put("id", chosen.path("id").asText());
            }

            fields.put("summary", params.path("summary").asText());
            if (params.hasNonNull("description") && !params.path("description").asText().isBlank()) {
                fields.set("description", adf(params.path("description").asText()));
            }
            if (params.hasNonNull("priority") && !params.path("priority").asText().isBlank()) {
                fields.putObject("priority").put("name", params.path("priority").asText());
            }

            JsonNode response = create(connection, fields);
            String dropped = response.path("relayDroppedFields").asText("");

            ObjectNode out = Json.object();
            out.put("id", response.path("id").asText(""));
            out.put("issueKey", response.path("key").asText(""));
            out.put("summary", params.path("summary").asText());
            out.put("projectKey", project);
            if (!typeName.isEmpty()) {
                out.put("issueType", typeName);
            }
            out.put("url", base(connection) + "/browse/" + response.path("key").asText(""));
            out.put("created", true);
            if (note != null) {
                out.put("note", note);
            }
            if (!dropped.isEmpty()) {
                out.put("droppedFields", dropped);
            }
            return out;
        }

        /**
         * POSTs the issue, and retries once without the optional fields the project refused.
         *
         * <p>A team-managed project routinely has no priority field on its create screen, and
         * Jira rejects the whole request for it — losing a ticket over a field nobody asked
         * for. Required fields are never dropped: the retry only removes what Relay added on
         * its own initiative, so a rejection that matters still surfaces.
         */
        private JsonNode create(Connection connection, ObjectNode fields) throws Exception {
            String url = base(connection) + "/rest/api/3/issue";
            ObjectNode body = Json.object();
            body.set("fields", fields);
            try {
                return jira("POST", url, headers(connection), body);
            } catch (HttpJson.ToolCallException e) {
                List<String> droppable = droppable(e.status(), e.body());
                if (droppable.isEmpty()) {
                    throw e;
                }
                ObjectNode retryFields = fields.deepCopy();
                droppable.forEach(retryFields::remove);
                ObjectNode retry = Json.object();
                retry.set("fields", retryFields);
                JsonNode created = jira("POST", url, headers(connection), retry);
                ObjectNode annotated = created.deepCopy();
                annotated.put("relayDroppedFields", String.join(", ", droppable));
                return annotated;
            }
        }
    }

    /** Fields Relay volunteered and can therefore give up — only when Jira named them. */
    private static final java.util.Set<String> OPTIONAL_FIELDS =
            java.util.Set.of("priority", "description", "labels");

    /**
     * Which of the rejected fields the retry may drop.
     *
     * <p>Empty unless <em>every</em> field Jira complained about is one Relay added by itself:
     * dropping half the complaint would only produce the same 400 with one field fewer.
     */
    static List<String> droppable(int status, String body) {
        List<String> out = new ArrayList<>();
        if (status != 400 || body == null) {
            return List.of();
        }
        JsonNode parsed;
        try {
            parsed = Json.parse(body);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!parsed.path("errorMessages").isEmpty() || !parsed.path("errors").fieldNames().hasNext()) {
            return List.of();
        }
        java.util.Iterator<String> names = parsed.path("errors").fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!OPTIONAL_FIELDS.contains(field.toLowerCase(Locale.ROOT))) {
                return List.of();
            }
            out.add(field);
        }
        return out;
    }

    // --------------------------------------------------------------- getIssue

    @Component
    public static class GetIssue extends JiraTool {

        public GetIssue(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "jira.getIssue";
        }

        @Override
        public String description() {
            return "Read one Jira issue by key, including description, status and comments.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("issueKey");
            ObjectNode key = schema.putObject("properties").putObject("issueKey");
            key.put("type", "string");
            key.put("description", "Issue key, e.g. RELAY-14");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String url = base(connection) + "/rest/api/3/issue/"
                    + HttpJson.encode(params.path("issueKey").asText());
            return jira("GET", url, headers(connection), null);
        }
    }

    // ------------------------------------------------------------ updateIssue

    @Component
    public static class UpdateIssue extends JiraTool {

        public UpdateIssue(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "jira.updateIssue";
        }

        @Override
        public String description() {
            return "Move a Jira issue to another status and/or change its summary. Requires approval by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("issueKey");
            ObjectNode props = schema.putObject("properties");
            props.putObject("issueKey").put("type", "string");
            ObjectNode status = props.putObject("status");
            status.put("type", "string");
            status.put("description", "Target status name, e.g. \"In Progress\" or \"Done\"");
            ObjectNode summary = props.putObject("summary");
            summary.put("type", "string");
            summary.put("description", "New summary — omit to keep the current one");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String issueKey = params.path("issueKey").asText();
            String issueUrl = base(connection) + "/rest/api/3/issue/" + HttpJson.encode(issueKey);
            ObjectNode result = Json.object();
            result.put("issueKey", issueKey);

            if (params.hasNonNull("summary")) {
                ObjectNode body = Json.object();
                body.putObject("fields").put("summary", params.path("summary").asText());
                jira("PUT", issueUrl, headers(connection), body);
                result.put("summaryUpdated", true);
            }

            if (params.hasNonNull("status")) {
                String target = params.path("status").asText();
                JsonNode transitions = jira("GET", issueUrl + "/transitions", headers(connection), null);
                String transitionId = matchTransition(transitions.path("transitions"), target);
                if (transitionId == null) {
                    throw new HttpJson.ToolCallException(
                            issueKey + " için '" + target + "' geçişi yok. Bu kayıtta şu an mümkün olanlar: "
                                    + names(transitions.path("transitions")));
                }
                ObjectNode body = Json.object();
                body.putObject("transition").put("id", transitionId);
                jira("POST", issueUrl + "/transitions", headers(connection), body);
                result.put("status", target);
                result.put("transitionId", transitionId);
            }
            result.put("updated", true);
            return result;
        }
    }

    // ------------------------------------------------------------- addComment

    @Component
    public static class AddComment extends JiraTool {

        public AddComment(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "jira.addComment";
        }

        @Override
        public String description() {
            return "Add a comment to a Jira issue. Requires approval by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("issueKey").add("body");
            ObjectNode props = schema.putObject("properties");
            props.putObject("issueKey").put("type", "string");
            ObjectNode body = props.putObject("body");
            body.put("type", "string");
            body.put("minLength", 1);
            body.put("description", "Comment text (plain text)");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String url = base(connection) + "/rest/api/3/issue/"
                    + HttpJson.encode(params.path("issueKey").asText()) + "/comment";
            ObjectNode body = Json.object();
            body.set("body", adf(params.path("body").asText()));
            return jira("POST", url, headers(connection), body);
        }
    }
}
