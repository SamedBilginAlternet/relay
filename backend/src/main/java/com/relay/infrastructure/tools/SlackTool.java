package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.text.Placeholder;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Slack, authenticated with a bot token ({@code xoxb-…}).
 *
 * <p>Connection config key: {@code botToken}. Slack answers HTTP 200 even for failures,
 * so {@code ok:false} is mapped to an exception here.
 */
public abstract class SlackTool extends AbstractTool {

    protected SlackTool(ToolsMode mode, FixtureStore fixtures) {
        super(mode, fixtures);
    }

    @Override
    protected boolean usable(Connection connection) {
        String token = connection.get("botToken");
        return token != null && !token.isBlank();
    }

    protected Map<String, String> headers(Connection connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + connection.get("botToken"));
        return headers;
    }

    protected JsonNode checked(JsonNode response) {
        if (!response.path("ok").asBoolean(false)) {
            throw new HttpJson.ToolCallException("slack error: " + response.path("error").asText("unknown")
                    + scopeDetail(response));
        }
        return response;
    }

    /**
     * Slack says which scopes it wanted and which the token carries. Without those two
     * lists {@code missing_scope} is unfixable guesswork — and the usual cause is invisible:
     * scopes are baked into the token at install time, so adding them in the app settings
     * changes nothing until the app is reinstalled and the new token is saved here.
     */
    private static String scopeDetail(JsonNode response) {
        String needed = response.path("needed").asText("");
        String provided = response.path("provided").asText("");
        if (needed.isBlank() && provided.isBlank()) {
            return "";
        }
        return " — gereken: " + (needed.isBlank() ? "?" : needed)
                + " · token'daki: " + (provided.isBlank() ? "(yok)" : provided)
                + ". Scope eklendiyse uygulamayı yeniden kurup YENİ xoxb- token'ını kaydet.";
    }

    // ------------------------------------------------------------ listChannels

    @Component
    public static class ListChannels extends SlackTool {

        public ListChannels(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "slack.listChannels";
        }

        @Override
        public String description() {
            return "List the Slack channels the bot can post to. Use it to resolve a channel name before posting.";
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
            ObjectNode limit = schema.putObject("properties").putObject("limit");
            limit.put("type", "integer");
            limit.put("minimum", 1);
            limit.put("maximum", 200);
            limit.put("description", "How many channels to return (default 50)");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            int limit = params.path("limit").asInt(50);
            // Asking for private channels demands groups:read, and Slack refuses the whole
            // call when that scope is missing — so a workspace that only granted the public
            // scopes could not list anything at all. Private channels are opt-in now.
            String types = "true".equalsIgnoreCase(connection.get("includePrivate"))
                    ? "public_channel,private_channel"
                    : "public_channel";
            String url = "https://slack.com/api/conversations.list?exclude_archived=true&limit=" + limit
                    + "&types=" + types;
            return checked(HttpJson.send("GET", url, headers(connection), null));
        }
    }

    // ------------------------------------------------------------- postMessage

    @Component
    public static class PostMessage extends SlackTool {

        public PostMessage(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "slack.postMessage";
        }

        @Override
        public String description() {
            return "Post a message to a Slack channel (or reply in a thread). Requires approval by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("channel").add("text");
            ObjectNode props = schema.putObject("properties");
            ObjectNode channel = props.putObject("channel");
            channel.put("type", "string");
            channel.put("description", "Channel id or #name");
            ObjectNode text = props.putObject("text");
            text.put("type", "string");
            text.put("minLength", 1);
            ObjectNode thread = props.putObject("threadTs");
            thread.put("type", "string");
            thread.put("description", "Reply into this thread timestamp — omit for a new message");
            return schema;
        }

        /**
         * Falls back to the connection's {@code defaultChannel} when the channel is missing
         * or was left as a placeholder.
         *
         * <p>The run that exposed this asked Slack to post to {@code {{steps[3].channel}}}
         * while {@code #all-samed} sat configured and unused. Resolving here — rather than
         * inside {@link #call} — means the approval screen shows the channel the message
         * will actually go to.
         */
        @Override
        public JsonNode withDefaults(JsonNode params, Connection connection) {
            if (connection == null || !params.isObject()) {
                return params;
            }
            String channel = params.path("channel").asText("");
            if (!channel.isBlank() && !Placeholder.unresolved(channel)) {
                return params;
            }
            String fallback = connection.get("defaultChannel");
            if (fallback == null || fallback.isBlank()) {
                return params;
            }
            ObjectNode resolved = ((ObjectNode) params).deepCopy();
            resolved.put("channel", fallback.trim());
            return resolved;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            ObjectNode body = Json.object();
            body.put("channel", params.path("channel").asText());
            body.put("text", params.path("text").asText());
            if (params.hasNonNull("threadTs")) {
                body.put("thread_ts", params.path("threadTs").asText());
            }
            return checked(HttpJson.send("POST", "https://slack.com/api/chat.postMessage",
                    headers(connection), body));
        }
    }
}
