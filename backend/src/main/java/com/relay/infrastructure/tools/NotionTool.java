package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Notion, authenticated with an internal integration token ({@code ntn_…}).
 *
 * <p>Connection config keys: {@code token} and, optionally, {@code parentDatabaseId} — the
 * database new pages land in when the model does not name one.
 *
 * <p>WHY THIS PROVIDER IS WRITE-ONLY. Every other integration here reads first and writes
 * second, and Notion deliberately does not. A reading tool has to be offered to the model on
 * every brief, and the brief runs whether or not anybody asked for it; measured on this
 * product that is two extra model turns per brief, and the day the daily token wall was hit
 * at 627k it was reading tools that spent it. One WRITE tool costs about a hundred tokens on
 * the runs that actually use it and nothing at all on the ones that do not. So there is no
 * {@code SECTIONS} entry and no {@code BriefService.fetch} for Notion, and adding one is a
 * decision, not a completion.
 *
 * <p>The version header is not optional. Notion rejects a request without
 * {@code Notion-Version} with a 400 before it looks at anything else, so it is pinned here
 * rather than left to a default that does not exist.
 */
public abstract class NotionTool extends AbstractTool {

    /** The API revision this code is written against. Notion refuses a request without it. */
    static final String API_VERSION = "2022-06-28";

    protected NotionTool(ToolsMode mode, FixtureStore fixtures) {
        super(mode, fixtures);
    }

    @Override
    protected boolean usable(Connection connection) {
        String token = connection.get("token");
        return token != null && !token.isBlank();
    }

    protected Map<String, String> headers(Connection connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + connection.get("token"));
        headers.put("Notion-Version", API_VERSION);
        return headers;
    }

    /** Sends a Notion request and rewrites a rejection into a sentence a person can act on. */
    protected static JsonNode notion(String method, String url, Map<String, String> headers, Object body)
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

    /**
     * The human sentence for one Notion rejection. Never contains the raw body.
     *
     * <p>{@code object_not_found} is the one that matters and it is the one that reads
     * wrongly if left alone: Notion answers it for a page that exists and is simply not
     * shared with the integration, and its own wording ("Could not find database with ID…")
     * sends the reader off to check an id that is perfectly correct. This is the same class
     * of failure as Slack's {@code not_in_channel}, it is the number one first-run failure,
     * and the fix is three clicks — so the sentence says which three.
     */
    static String explain(int status, String body) {
        String code = code(body);
        if (status == 401 || "unauthorized".equals(code)) {
            return "Notion kimlik doğrulaması reddedildi (HTTP " + status
                    + "). Bağlantı ayarlarındaki integration token'ını (ntn_…) kontrol edin.";
        }
        if (status == 404 || "object_not_found".equals(code) || "restricted_resource".equals(code)) {
            return "Notion hedef sayfayı ya da veritabanını göremiyor. Bu neredeyse her zaman "
                    + "izin sorunudur, id hatası değil: bir integration yalnızca kendisiyle "
                    + "açıkça paylaşılan sayfaları görebilir. Notion'da hedef sayfayı/veritabanını "
                    + "açın, sağ üstteki ••• menüsünden Connections (Bağlantılar) → Relay "
                    + "integration'ını ekleyin ve adımı tekrar deneyin.";
        }
        String reason = message(body);
        String head = switch (status) {
            case 400 -> "Notion isteği reddetti (HTTP 400)";
            case 429 -> "Notion istek sınırına takıldı (HTTP 429), biraz sonra tekrar deneyin";
            default -> "Notion isteği reddetti (HTTP " + status + ")";
        };
        return reason.isBlank() ? head + "." : head + ": " + reason;
    }

    /** Notion's machine-readable error code, or {@code ""} when the body is not its own JSON. */
    private static String code(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return Json.parse(body).path("code").asText("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Notion's own sentence about what was wrong, redacted like any other quoted body. */
    private static String message(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return HttpJson.redact(Json.parse(body).path("message").asText("")).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------- createPage

    /**
     * Opens a page in a Notion database — the note a lawyer, a consultant or an operations
     * manager keeps after the work is done.
     */
    @Component
    public static class CreatePage extends NotionTool {

        /**
         * Notion refuses more than 100 child blocks in one create, and more than 2000
         * characters in one rich-text run.
         */
        private static final int MAX_BLOCKS = 100;
        private static final int MAX_TEXT = 2000;

        public CreatePage(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures) {
            super(ToolsMode.parse(mode), fixtures);
        }

        @Override
        public String name() {
            return "notion.createPage";
        }

        @Override
        public String description() {
            return "Create a page in a Notion database with a title and body text. "
                    + "Use it to write a note, a summary or a record of what was done. "
                    + "Requires approval by default.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        /**
         * {@code parentDatabaseId} is required here for the same reason {@code projectKey} is
         * required on {@code jira.createIssue}: the container is not a choice, there is no
         * call without it, and being in {@code required} is what lets
         * {@code ToolAgent.CONTAINER_DEFAULTS} fill it from the connection when the model
         * leaves it out. What the user sees is still "optional" — they set it once on
         * Bağlantılar and never type it again.
         *
         * <p>{@code title} and {@code content} are the work itself. Nothing fills them: a
         * step that produced no title is a failed step, not a step to paper over with the
         * goal text.
         */
        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("parentDatabaseId").add("title").add("content");
            ObjectNode props = schema.putObject("properties");
            ObjectNode parent = props.putObject("parentDatabaseId");
            parent.put("type", "string");
            parent.put("description",
                    "Database the page is created in — omit to use the one configured on the connection");
            ObjectNode title = props.putObject("title");
            title.put("type", "string");
            title.put("minLength", 1);
            title.put("description", "One line title of the page");
            ObjectNode content = props.putObject("content");
            content.put("type", "string");
            content.put("minLength", 1);
            content.put("description",
                    "Body text. Plain text, or light markdown: '# ', '## ', '### ' headings and '- ' bullets");
            return schema;
        }

        /**
         * Falls back to the connection's {@code parentDatabaseId} when the database is
         * missing or was left as a placeholder — exactly what {@code SlackTool.PostMessage}
         * does with {@code defaultChannel}, and for the same reason: resolved here rather
         * than inside {@link #call}, the approval screen shows the database the page will
         * actually land in instead of a blank.
         */
        @Override
        public JsonNode withDefaults(JsonNode params, Connection connection) {
            if (connection == null || !params.isObject()) {
                return params;
            }
            String database = params.path("parentDatabaseId").asText("");
            if (!database.isBlank() && !Placeholder.unresolved(database)) {
                return params;
            }
            String fallback = connection.get("parentDatabaseId");
            if (fallback == null || fallback.isBlank()) {
                return params;
            }
            ObjectNode resolved = ((ObjectNode) params).deepCopy();
            resolved.put("parentDatabaseId", fallback.trim());
            return resolved;
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            String database = params.path("parentDatabaseId").asText("").trim();
            if (database.isEmpty()) {
                throw new HttpJson.ToolCallException("Sayfanın hangi Notion veritabanında açılacağı "
                        + "belli değil: parentDatabaseId boş ve Notion bağlantısında varsayılan "
                        + "veritabanı kayıtlı değil.");
            }

            ObjectNode body = Json.object();
            body.putObject("parent").put("database_id", database);
            // Keyed by the title property's *id*, which is the literal string "title" on
            // every Notion database. Keying by its name would mean guessing between "Name",
            // "Ad", "Başlık" and whatever else the user renamed the first column to.
            ObjectNode titleProperty = body.putObject("properties").putObject("title");
            titleProperty.set("title", richText(params.path("title").asText()));
            body.set("children", blocks(params.path("content").asText()));

            return notion("POST", "https://api.notion.com/v1/pages", headers(connection), body);
        }

        /**
         * Where the page is and what it is called. Notion answers a create with the whole
         * page object: every property of the database it landed in, the created_by and
         * last_edited_by user objects, the cover, the icon, the parent, the archived and
         * in_trash flags and a request_id. The trail needs to be able to say "this page now
         * exists, here, under this name" and nothing on that list helps it.
         */
        @Override
        protected JsonNode project(JsonNode raw) {
            ObjectNode out = Json.object();
            out.put("id", raw.path("id").asText(""));
            out.put("url", raw.path("url").asText(""));
            out.put("title", titleOf(raw));
            out.put("created", true);
            return out;
        }

        /**
         * The page's own title, from wherever it ended up.
         *
         * <p>Live, that is the one property of type {@code title} — its name is whatever the
         * user called the first column, so it is found by type rather than by key. Replayed,
         * the fixture already carries the projected shape, so a flat {@code title} wins.
         */
        private static String titleOf(JsonNode raw) {
            if (raw.path("title").isTextual()) {
                return raw.path("title").asText("");
            }
            for (JsonNode property : raw.path("properties")) {
                if (!"title".equals(property.path("type").asText(""))) {
                    continue;
                }
                StringBuilder text = new StringBuilder();
                for (JsonNode run : property.path("title")) {
                    text.append(run.path("plain_text").asText(""));
                }
                return text.toString();
            }
            return "";
        }

        /**
         * Plain text, or the little markdown a model actually writes, as Notion blocks.
         *
         * <p>Three heading levels and a bullet, and everything else is a paragraph. A full
         * markdown parser here would be a second thing to keep correct for a body that is
         * usually four sentences; a line that starts with something unrecognised is worth
         * more on the page verbatim than dropped for not parsing.
         */
        private static ArrayNode blocks(String content) {
            ArrayNode children = Json.mapper().createArrayNode();
            for (String line : content.split("\r?\n")) {
                String text = line.strip();
                if (text.isEmpty() || children.size() >= MAX_BLOCKS) {
                    continue;
                }
                String type = "paragraph";
                if (text.startsWith("### ")) {
                    type = "heading_3";
                    text = text.substring(4);
                } else if (text.startsWith("## ")) {
                    type = "heading_2";
                    text = text.substring(3);
                } else if (text.startsWith("# ")) {
                    type = "heading_1";
                    text = text.substring(2);
                } else if (text.startsWith("- ") || text.startsWith("* ")) {
                    type = "bulleted_list_item";
                    text = text.substring(2);
                }
                ObjectNode block = children.addObject();
                block.put("object", "block");
                block.put("type", type);
                block.putObject(type).set("rich_text", richText(text));
            }
            if (children.isEmpty()) {
                ObjectNode block = children.addObject();
                block.put("object", "block");
                block.put("type", "paragraph");
                block.putObject("paragraph").set("rich_text", richText(content.strip()));
            }
            return children;
        }

        /** One rich-text run, cut to the 2000 characters Notion accepts in one. */
        private static ArrayNode richText(String text) {
            ArrayNode runs = Json.mapper().createArrayNode();
            String value = text == null ? "" : text;
            ObjectNode run = runs.addObject();
            run.put("type", "text");
            run.putObject("text").put("content",
                    value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value);
            return runs;
        }
    }
}
