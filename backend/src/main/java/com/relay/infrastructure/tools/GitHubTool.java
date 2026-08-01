package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub, authenticated with a fine-grained personal access token — no OAuth dance,
 * which makes it the cheapest integration of the daily brief (BRIEF §6).
 *
 * <p>Connection config keys: {@code token} (required) and {@code login} (optional).
 * A missing or non-username {@code login} is resolved from the token itself — see
 * {@link #me(Connection)}.
 *
 * <p>Responses are normalised here, so the fixtures, the brief and the frontend all see
 * one flat shape instead of GitHub's raw search payload.
 */
public abstract class GitHubTool extends AbstractTool {

    protected static final String API = "https://api.github.com";

    protected GitHubTool(ToolsMode mode, FixtureStore fixtures) {
        super(mode, fixtures);
    }

    @Override
    protected boolean usable(Connection connection) {
        return notBlank(connection.get("token"));
    }

    protected Map<String, String> headers(Connection connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + connection.get("token"));
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "relay-agent");
        return headers;
    }

    /**
     * The GitHub username to put in a search qualifier.
     *
     * <p>Search qualifiers take a login, not an address: live, the connection carried
     * {@code login = samed.bilgin@alternet.com.tr} and every query came back HTTP 422, so
     * the whole KOD section of the brief showed an error. {@code @me} is not a valid REST
     * qualifier either — that shorthand only works in GitHub's own UI.
     *
     * <p>So anything that is not a plain username is ignored and the real login is read
     * from the token itself. The answer is cached per token: it cannot change without the
     * credential changing.
     */
    protected String me(Connection connection) {
        String login = connection.get("login");
        if (isUsername(login)) {
            return login.trim();
        }
        return resolveLogin(connection);
    }

    /** GitHub usernames are alphanumeric with single hyphens — no dots, no @, no spaces. */
    private static boolean isUsername(String value) {
        return value != null && value.trim().matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    }

    private static final Map<String, String> LOGIN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private String resolveLogin(Connection connection) {
        String token = connection.get("token");
        if (!notBlank(token)) {
            return "@me";
        }
        return LOGIN_CACHE.computeIfAbsent(token, key -> {
            try {
                JsonNode user = HttpJson.send("GET", API + "/user", headers(connection), null);
                String login = user.path("login").asText("");
                return isUsername(login) ? login : "@me";
            } catch (Exception e) {
                // Leave the qualifier out rather than guessing; the caller's error is clearer.
                return "@me";
            }
        });
    }

    protected JsonNode search(Connection connection, String query, int perPage) throws Exception {
        String url = API + "/search/issues?per_page=" + perPage + "&sort=updated&order=desc&q="
                + HttpJson.encode(query);
        return HttpJson.send("GET", url, headers(connection), null);
    }

    /**
     * Turns GitHub's flat permission errors into the sentence that fixes them.
     *
     * <p>"Resource not accessible by personal access token" is the same 403 whether the
     * token is read-only, the repository is outside its scope, or an organisation has not
     * approved fine-grained tokens at all — and the message names none of those.
     */
    static HttpJson.ToolCallException explain(HttpJson.ToolCallException failure, String repo) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("403")) {
            return new HttpJson.ToolCallException(
                    "GitHub bu token'a yazma izni vermiyor (403). Token'ın izinlerinde"
                            + " Issues ve Pull requests \"Read and write\" olmalı ve " + repo
                            + " token'ın erişim listesinde bulunmalı. Repo bir organizasyona aitse"
                            + " fine-grained token'ları organizasyonun ayrıca onaylaması gerekir.");
        }
        if (message.contains("404")) {
            return new HttpJson.ToolCallException(
                    repo + " token tarafından görülemiyor (404). Depo adı yanlış olabilir ya da"
                            + " token'ın repository erişim listesinde değildir.");
        }
        return failure;
    }

    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Every GitHub tool answers in Relay's own shape: {@link #normalise} and the per-tool
     * {@code call} bodies pick the fields out of the search response, so the provider's own
     * urls, node ids and user objects never reach the result in the first place.
     */
    @Override
    protected JsonNode project(JsonNode raw) {
        return raw;
    }

    /** {@code https://api.github.com/repos/acme/payments} → {@code acme/payments}. */
    protected static String repoOf(JsonNode item) {
        String url = item.path("repository_url").asText("");
        int repos = url.indexOf("/repos/");
        if (repos >= 0) {
            return url.substring(repos + "/repos/".length());
        }
        String html = item.path("html_url").asText("");
        String[] parts = html.split("/");
        return parts.length >= 5 ? parts[3] + "/" + parts[4] : "";
    }

    protected static ObjectNode normalise(JsonNode item, String reason) {
        ObjectNode out = Json.object();
        out.put("repo", repoOf(item));
        out.put("number", item.path("number").asInt());
        out.put("title", item.path("title").asText(""));
        out.put("url", item.path("html_url").asText(""));
        out.put("author", item.path("user").path("login").asText(""));
        out.put("state", item.path("state").asText("open"));
        out.put("updatedAt", item.path("updated_at").asText(""));
        out.put("comments", item.path("comments").asInt(0));
        out.put("draft", item.path("draft").asBoolean(false));
        if (reason != null) {
            out.put("reason", reason);
        }
        ArrayNode labels = out.putArray("labels");
        for (JsonNode label : item.path("labels")) {
            labels.add(label.path("name").asText(""));
        }
        return out;
    }

    // -------------------------------------------------- listMyPullRequests

    @Component
    public static class ListMyPullRequests extends GitHubTool {

        public ListMyPullRequests(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "github.listMyPullRequests";
        }

        @Override
        public String description() {
            return "List open GitHub pull requests that either await my review or were opened by me. "
                    + "Returns repo, number, title, url and why it is on my plate.";
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
            max.put("description", "How many pull requests to return per query (default 15)");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            int max = params.path("maxResults").asInt(15);
            String who = me(connection);

            ObjectNode out = Json.object();
            ArrayNode items = out.putArray("pullRequests");
            Set<String> seen = new LinkedHashSet<>();

            for (String[] query : new String[][] {
                    {"is:open is:pr review-requested:" + who, "review_requested"},
                    {"is:open is:pr author:" + who, "author"}}) {
                JsonNode response = search(connection, query[0], max);
                for (JsonNode item : response.path("items")) {
                    String url = item.path("html_url").asText("");
                    if (!url.isEmpty() && !seen.add(url)) {
                        continue;
                    }
                    items.add(normalise(item, query[1]));
                }
            }
            out.put("total", items.size());
            return out;
        }
    }

    // -------------------------------------------------------- listMyIssues

    @Component
    public static class ListMyIssues extends GitHubTool {

        public ListMyIssues(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "github.listMyIssues";
        }

        @Override
        public String description() {
            return "List open GitHub issues assigned to me. Returns repo, number, title, labels and url.";
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
            JsonNode response = search(connection, "is:open is:issue assignee:" + me(connection), max);

            ObjectNode out = Json.object();
            ArrayNode items = out.putArray("issues");
            for (JsonNode item : response.path("items")) {
                items.add(normalise(item, "assigned"));
            }
            out.put("total", items.size());
            return out;
        }
    }

    // --------------------------------------------------------- addComment

    @Component
    public static class AddComment extends GitHubTool {

        public AddComment(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "github.addComment";
        }

        @Override
        public String description() {
            return "Comment on a GitHub issue or pull request. Requires approval by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("repo").add("number").add("body");
            ObjectNode props = schema.putObject("properties");
            ObjectNode repo = props.putObject("repo");
            repo.put("type", "string");
            repo.put("description", "owner/name, e.g. acme/payments");
            ObjectNode number = props.putObject("number");
            number.put("type", "integer");
            number.put("minimum", 1);
            number.put("description", "Issue or pull request number");
            ObjectNode body = props.putObject("body");
            body.put("type", "string");
            body.put("minLength", 1);
            body.put("description", "Comment text (GitHub markdown)");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String repo = params.path("repo").asText().trim();
            int number = params.path("number").asInt();
            String url = API + "/repos/" + repo + "/issues/" + number + "/comments";
            ObjectNode body = Json.object();
            body.put("body", params.path("body").asText());
            JsonNode response;
            try {
                response = HttpJson.send("POST", url, headers(connection), body);
            } catch (HttpJson.ToolCallException e) {
                throw explain(e, repo);
            }

            ObjectNode out = Json.object();
            out.put("repo", repo);
            out.put("number", number);
            out.put("commentId", response.path("id").asLong());
            out.put("url", response.path("html_url").asText(""));
            out.put("body", params.path("body").asText());
            out.put("created", true);
            return out;
        }
    }

    // --------------------------------------------------------- createIssue

    /**
     * Opens an issue — the GitHub half of "maili işe çevir".
     *
     * <p>{@code jira.createIssue} is the demo's spine, and a team that lives on GitHub
     * Issues instead of Jira had no equivalent: Relay could comment under their records
     * ({@code addComment}) but never open one, so the flow that turns a bug mail into a
     * tracked piece of work simply did not exist for them.
     *
     * <p>{@code repo} is a container, not a record pointer: it comes from the goal, from an
     * earlier read, or from the connection's {@code defaultRepo} — {@code
     * ToolAgent.CONTAINER_DEFAULTS} already knows the key, this tool just gives it a write
     * worth filling for. {@code title} and {@code body} are the work itself. Nothing fills
     * them, nothing borrows the goal text for them; a step that produced no title is a
     * failed step, and the Filler gate holds the body to that.
     */
    @Component
    public static class CreateIssue extends GitHubTool {

        public CreateIssue(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "github.createIssue";
        }

        @Override
        public String description() {
            return "Open a GitHub issue with a title and body. repo is owner/name; labels are "
                    + "optional existing label names. Requires approval by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("repo").add("title").add("body");
            ObjectNode props = schema.putObject("properties");
            ObjectNode repo = props.putObject("repo");
            repo.put("type", "string");
            repo.put("description",
                    "owner/name, e.g. acme/payments — omit to use the connection's default repo");
            ObjectNode title = props.putObject("title");
            title.put("type", "string");
            title.put("minLength", 1);
            title.put("description", "One line title of the issue");
            ObjectNode body = props.putObject("body");
            body.put("type", "string");
            body.put("minLength", 1);
            body.put("description", "Issue body (GitHub markdown) — say where this came from, "
                    + "e.g. the mail subject and sender it was opened for");
            ObjectNode labels = props.putObject("labels");
            labels.put("type", "array");
            labels.put("description", "Optional label names, e.g. [\"bug\"]");
            labels.putObject("items").put("type", "string");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String repo = params.path("repo").asText().trim();
            ObjectNode body = Json.object();
            body.put("title", params.path("title").asText());
            body.put("body", params.path("body").asText());
            ArrayNode labels = Json.mapper().createArrayNode();
            for (JsonNode label : params.path("labels")) {
                String name = label.asText("").trim();
                if (!name.isEmpty()) {
                    labels.add(name);
                }
            }
            if (!labels.isEmpty()) {
                // Absent means absent: an empty labels array is a statement about labels,
                // and this step is not making one.
                body.set("labels", labels);
            }

            JsonNode response;
            try {
                response = post(API + "/repos/" + repo + "/issues", headers(connection), body);
            } catch (HttpJson.ToolCallException e) {
                // 410 is GitHub's word for "this repository has its Issues tab switched
                // off" — a setting, not a permission, and the generic 403/404 sentences
                // would both send the reader to the wrong screen.
                if (e.status() == 410) {
                    throw new HttpJson.ToolCallException("Bu depoda Issues kapalı (HTTP 410). "
                            + repo + " → Settings → Features altından Issues açılmadan kayıt "
                            + "açılamaz.", e.status(), e.body());
                }
                throw explain(e, repo);
            }

            ObjectNode out = Json.object();
            out.put("repo", repo);
            out.put("number", response.path("number").asInt());
            out.put("title", response.path("title").asText(params.path("title").asText()));
            out.put("url", response.path("html_url").asText(""));
            out.put("state", response.path("state").asText("open"));
            ArrayNode outLabels = out.putArray("labels");
            for (JsonNode label : response.path("labels")) {
                outLabels.add(label.path("name").asText(""));
            }
            out.put("created", true);
            return out;
        }

        /**
         * The single network call, isolated so a test can watch exactly what would land in
         * somebody's issue tracker — and that nothing else ever leaves this tool.
         */
        JsonNode post(String url, Map<String, String> headers, JsonNode body) throws Exception {
            return HttpJson.send("POST", url, headers, body);
        }
    }
}
