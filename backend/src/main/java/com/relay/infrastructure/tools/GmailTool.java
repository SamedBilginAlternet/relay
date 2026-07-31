package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gmail over the REST API v1, with an OAuth access token from the {@code google} connection.
 *
 * <p>Gmail's list endpoint returns ids only, so {@code listToday} fans the metadata reads out
 * over virtual threads and normalises them into one flat array — the brief never sees
 * base64url payload parts.
 */
public abstract class GmailTool extends GoogleTool {

    protected static final String API = "https://gmail.googleapis.com/gmail/v1/users/me";

    protected GmailTool(ToolsMode mode, FixtureStore fixtures, GoogleOAuth oauth) {
        super(mode, fixtures, oauth);
    }

    protected static String header(JsonNode message, String name) {
        for (JsonNode header : message.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asText(""))) {
                return header.path("value").asText("");
            }
        }
        return "";
    }

    protected static String isoDate(JsonNode message) {
        long millis = message.path("internalDate").asLong(0);
        return millis <= 0 ? "" : Instant.ofEpochMilli(millis).toString();
    }

    /** Gmail's own tabs. Anything Google filed away from Primary is a mailing, not a request. */
    private static final String[] BULK_LABELS = {
        "CATEGORY_PROMOTIONS", "CATEGORY_UPDATES", "CATEGORY_FORUMS", "CATEGORY_SOCIAL"};

    /**
     * Is this a mailing rather than a person writing to this user?
     *
     * <p>{@code List-Unsubscribe} is the definitive signal but not a universal one — live,
     * a DEV Community digest arrived without it and came back classified as a high-urgency
     * bug report, because its subject contained the word "bugs". Gmail had already filed it
     * under a category tab; that verdict is free and we were ignoring it.
     */
    private static boolean isBulk(JsonNode message) {
        if (!header(message, "List-Unsubscribe").isBlank() || !header(message, "Precedence").isBlank()) {
            return true;
        }
        for (String label : BULK_LABELS) {
            if (hasLabel(message, label)) {
                return true;
            }
        }
        return false;
    }

    protected static boolean hasLabel(JsonNode message, String label) {
        for (JsonNode id : message.path("labelIds")) {
            if (label.equals(id.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * List + hydrate: Gmail's list endpoint returns ids only, so the metadata reads fan out
     * over virtual threads and come back as one flat, normalised array.
     *
     * <p>Shared by {@code gmail.listToday} and {@code gmail.search} — the only difference
     * between them is the query, and a second copy of this loop would be a second place to
     * forget the {@code List-Unsubscribe} header that keeps newsletters out of the work lane.
     */
    protected static ObjectNode listMessages(String query, int max, Map<String, String> headers)
            throws Exception {
        JsonNode list = HttpJson.send("GET", API + "/messages?maxResults=" + max
                + "&q=" + HttpJson.encode(query), headers, null);

        List<String> ids = new ArrayList<>();
        for (JsonNode message : list.path("messages")) {
            ids.add(message.path("id").asText());
        }

        ObjectNode out = Json.object();
        ArrayNode messages = out.putArray("messages");
        if (!ids.isEmpty()) {
            // One HTTP round trip per message — virtual threads keep it inside the
            // brief's 8s budget instead of 15 sequential calls.
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<JsonNode>> futures = new ArrayList<>();
                for (String id : ids) {
                    futures.add(pool.submit(() -> HttpJson.send("GET", API + "/messages/" + id
                            + "?format=metadata&metadataHeaders=From&metadataHeaders=To"
                            + "&metadataHeaders=Subject&metadataHeaders=Date"
                            // The one header that separates a person writing to you from a
                            // mailing that went to thousands. Bulk mail carries it by law
                            // in most jurisdictions, and guessing from the subject line
                            // gets newsletters classified as bug reports.
                            + "&metadataHeaders=List-Unsubscribe"
                            + "&metadataHeaders=Precedence", headers, null)));
                }
                for (Future<JsonNode> future : futures) {
                    JsonNode message = future.get();
                    ObjectNode item = messages.addObject();
                    item.put("id", message.path("id").asText(""));
                    item.put("threadId", message.path("threadId").asText(""));
                    item.put("from", header(message, "From"));
                    item.put("subject", header(message, "Subject"));
                    item.put("snippet", message.path("snippet").asText(""));
                    item.put("receivedAt", isoDate(message));
                    item.put("unread", hasLabel(message, "UNREAD"));
                    item.put("bulk", isBulk(message));
                }
            }
        }
        out.put("total", messages.size());
        out.put("query", query);
        return out;
    }

    /** Walks the MIME tree and returns the first text/plain body, decoded. */
    protected static String plainText(JsonNode payload) {
        String mime = payload.path("mimeType").asText("");
        String data = payload.path("body").path("data").asText("");
        if (mime.startsWith("text/plain") && !data.isEmpty()) {
            return decode(data);
        }
        for (JsonNode part : payload.path("parts")) {
            String text = plainText(part);
            if (!text.isBlank()) {
                return text;
            }
        }
        if (!data.isEmpty()) {
            return decode(data);
        }
        return "";
    }

    private static String decode(String base64Url) {
        try {
            return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ----------------------------------------------------------- listToday

    @Component
    public static class ListToday extends GmailTool {

        public ListToday(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                         GoogleOAuth oauth) {
            super(ToolsMode.parse(mode), fixtures, oauth);
        }

        @Override
        public String name() {
            return "gmail.listToday";
        }

        @Override
        public String description() {
            return "List today's Gmail messages (newer_than:1d) with sender, subject and snippet. "
                    + "The inbox section of the daily brief.";
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
            ObjectNode props = schema.putObject("properties");
            ObjectNode max = props.putObject("maxResults");
            max.put("type", "integer");
            max.put("minimum", 1);
            max.put("maximum", 50);
            max.put("description", "How many messages to return (default 15)");
            ObjectNode query = props.putObject("query");
            query.put("type", "string");
            query.put("description", "Gmail search query — defaults to \"newer_than:1d -in:chats\"");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            int max = params.path("maxResults").asInt(15);
            String query = params.path("query").asText("");
            if (query.isBlank()) {
                query = "newer_than:1d -in:chats";
            }
            return listMessages(query, max, headers(connection));
        }
    }

    // ------------------------------------------------------------- search

    /**
     * Free-form Gmail search — the tool behind "şundan mail gelmiş mi?".
     *
     * <p>{@code query} is Gmail's own search syntax and arrives from
     * {@code MailQueryTranslator}, which turns a Turkish question into it. Read-only by
     * construction: the API call is a GET against the search endpoint and nothing else.
     */
    @Component
    public static class Search extends GmailTool {

        public Search(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                      GoogleOAuth oauth) {
            super(ToolsMode.parse(mode), fixtures, oauth);
        }

        @Override
        public String name() {
            return "gmail.search";
        }

        @Override
        public String description() {
            return "Search Gmail with the provider's own query syntax "
                    + "(e.g. \"from:trendyol newer_than:7d\", \"subject:(kargo OR teslimat)\") "
                    + "and return matching messages with sender, subject, snippet and date. "
                    + "Use it to answer questions about what did or did not arrive by mail.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("query");
            ObjectNode props = schema.putObject("properties");
            ObjectNode query = props.putObject("query");
            query.put("type", "string");
            query.put("minLength", 2);
            query.put("description", "Gmail search query, e.g. \"from:(aras OR yurtici) newer_than:30d\"");
            ObjectNode max = props.putObject("maxResults");
            max.put("type", "integer");
            max.put("minimum", 1);
            max.put("maximum", 50);
            max.put("description", "How many messages to return (default 15)");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            int max = Math.min(50, Math.max(1, params.path("maxResults").asInt(15)));
            return listMessages(params.path("query").asText().trim(), max, headers(connection));
        }
    }

    // ---------------------------------------------------------- getMessage

    @Component
    public static class GetMessage extends GmailTool {

        public GetMessage(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                          GoogleOAuth oauth) {
            super(ToolsMode.parse(mode), fixtures, oauth);
        }

        @Override
        public String name() {
            return "gmail.getMessage";
        }

        @Override
        public String description() {
            return "Read one Gmail message in full: sender, recipients, subject and plain-text body.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("messageId");
            ObjectNode id = schema.putObject("properties").putObject("messageId");
            id.put("type", "string");
            id.put("description", "Gmail message id, as returned by gmail.listToday");
            return schema;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String url = API + "/messages/" + HttpJson.encode(params.path("messageId").asText())
                    + "?format=full";
            JsonNode message = HttpJson.send("GET", url, headers(connection), null);

            ObjectNode out = Json.object();
            out.put("id", message.path("id").asText(""));
            out.put("threadId", message.path("threadId").asText(""));
            out.put("from", header(message, "From"));
            out.put("to", header(message, "To"));
            out.put("subject", header(message, "Subject"));
            out.put("receivedAt", isoDate(message));
            out.put("unread", hasLabel(message, "UNREAD"));
            out.put("snippet", message.path("snippet").asText(""));
            out.put("body", plainText(message.path("payload")));
            return out;
        }
    }
}
