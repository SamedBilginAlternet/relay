package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
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
            String jql = params.path("jql").asText();
            int max = params.path("maxResults").asInt(10);
            // /rest/api/3/search was REMOVED by Atlassian (HTTP 410, CHANGE-2046).
            // The replacement is /rest/api/3/search/jql, which takes the same query
            // parameters but returns `isLast` instead of `total` for pagination.
            String url = base(connection) + "/rest/api/3/search/jql?jql=" + HttpJson.encode(jql)
                    + "&maxResults=" + max + "&fields=summary,status,assignee,priority";
            return HttpJson.send("GET", url, headers(connection), null);
        }
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
            return HttpJson.send("GET", url, headers(connection), null);
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
                HttpJson.send("PUT", issueUrl, headers(connection), body);
                result.put("summaryUpdated", true);
            }

            if (params.hasNonNull("status")) {
                String target = params.path("status").asText();
                JsonNode transitions = HttpJson.send("GET", issueUrl + "/transitions", headers(connection), null);
                String transitionId = null;
                for (JsonNode transition : transitions.path("transitions")) {
                    String name = transition.path("name").asText("");
                    String toName = transition.path("to").path("name").asText("");
                    if (name.equalsIgnoreCase(target) || toName.equalsIgnoreCase(target)) {
                        transitionId = transition.path("id").asText();
                        break;
                    }
                }
                if (transitionId == null) {
                    throw new HttpJson.ToolCallException(
                            "no transition to '" + target + "' available on " + issueKey);
                }
                ObjectNode body = Json.object();
                body.putObject("transition").put("id", transitionId);
                HttpJson.send("POST", issueUrl + "/transitions", headers(connection), body);
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
            return HttpJson.send("POST", url, headers(connection), body);
        }
    }
}
