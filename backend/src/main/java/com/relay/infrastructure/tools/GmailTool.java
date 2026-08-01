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
import java.util.Locale;
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
 *
 * <p>Three of the four tools here read. The fourth, {@link CreateDraft}, is the only write
 * Relay makes into a mailbox, and it is a draft: nothing is ever sent.
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

    // --------------------------------------------------------- createDraft

    /**
     * A reply the user still has to send.
     *
     * <p>Relay read mail and could not answer it, so everything it wrote went to Jira,
     * Slack or GitHub — a tool for people who ship software. A draft is the write that
     * fits the thesis instead of straining it: it is undoable by construction. Nothing
     * leaves the mailbox, the text sits in Drafts, and the approval gate has already
     * shown it (and let it be edited) before it is created.
     *
     * <p>Only {@code drafts.create} is reachable from here: one endpoint, one method, and a
     * test that fails the moment a second URL appears. That is the whole of the guarantee —
     * the scope behind it, {@code gmail.compose}, is a restricted scope that also permits
     * {@code messages.send} ("Manage drafts and send emails" on the consent screen), and
     * Gmail offers nothing narrower that can create a draft. The user's mail stays put
     * because this class refuses to move it, not because Google is stopping us.
     */
    @Component
    public static class CreateDraft extends GmailTool {

        /** The one endpoint this tool talks to. */
        static final String DRAFTS = API + "/drafts";

        /**
         * What a token issued before {@code gmail.compose} is told. It names the screen and
         * the reason, because "insufficient authentication scopes" names neither.
         */
        static final String NEEDS_CONSENT =
                "Google izni yalnız okuma; taslak yazmak için Bağlantılar'dan Google'a yeniden "
                + "bağlan (yeni izin: taslak oluşturma). Mevcut bağlantın okuma işlerini "
                + "yapmaya devam ediyor.";

        public CreateDraft(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                           GoogleOAuth oauth) {
            super(ToolsMode.parse(mode), fixtures, oauth);
        }

        @Override
        public String name() {
            return "gmail.createDraft";
        }

        @Override
        public String description() {
            return "Write a reply into the user's Gmail drafts folder — it is NEVER sent, the "
                    + "user opens Gmail and presses send. Pass threadId (and inReplyTo when the "
                    + "message id is known) so the draft hangs under the conversation it answers. "
                    + "Use it whenever a mail is waiting for an answer.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("to").add("subject").add("body");
            ObjectNode props = schema.putObject("properties");
            ObjectNode to = props.putObject("to");
            to.put("type", "string");
            to.put("minLength", 3);
            to.put("description", "Recipient, e.g. \"Ayşe Yıldız <ayse@example.com>\" — "
                    + "for a reply, the from field of the message being answered");
            ObjectNode subject = props.putObject("subject");
            subject.put("type", "string");
            subject.put("minLength", 1);
            ObjectNode body = props.putObject("body");
            body.put("type", "string");
            body.put("minLength", 1);
            body.put("description", "The reply itself, plain text, in the language of the mail");
            ObjectNode thread = props.putObject("threadId");
            thread.put("type", "string");
            thread.put("description", "Gmail thread id of the conversation being answered — "
                    + "omit for a brand-new mail");
            ObjectNode inReplyTo = props.putObject("inReplyTo");
            inReplyTo.put("type", "string");
            inReplyTo.put("description", "RFC 2822 Message-ID of the mail being answered, "
                    + "e.g. \"<CAB1@mail.gmail.com>\"");
            return schema;
        }

        /**
         * Puts the {@code Re:} on a reply here rather than at call time, so the subject the
         * approval screen shows is the subject Gmail will store.
         */
        @Override
        public JsonNode withDefaults(JsonNode params, Connection connection) {
            if (!params.isObject() || !isReply(params)) {
                return params;
            }
            String subject = params.path("subject").asText("").trim();
            if (subject.isEmpty() || subject.toLowerCase(Locale.ROOT).startsWith("re:")) {
                return params;
            }
            ObjectNode out = ((ObjectNode) params).deepCopy();
            out.put("subject", "Re: " + subject);
            return out;
        }

        private static boolean isReply(JsonNode params) {
            return !params.path("threadId").asText("").isBlank()
                    || !params.path("inReplyTo").asText("").isBlank();
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            if (!GoogleOAuth.granted(connection, GoogleOAuth.COMPOSE_SCOPE)) {
                throw new HttpJson.ToolCallException(NEEDS_CONSENT);
            }
            String to = oneLine(params.path("to").asText(""));
            String subject = oneLine(params.path("subject").asText(""));
            String threadId = oneLine(params.path("threadId").asText(""));
            String inReplyTo = messageId(oneLine(params.path("inReplyTo").asText("")));

            ObjectNode message = Json.object();
            message.put("raw", raw(to, subject, params.path("body").asText(""), inReplyTo));
            if (!threadId.isBlank()) {
                // The handle Gmail threads on. In-Reply-To/References are for every other
                // mail client, once the user presses send.
                message.put("threadId", threadId);
            }
            ObjectNode payload = Json.object();
            payload.set("message", message);

            JsonNode created;
            try {
                created = post(DRAFTS, headers(connection), payload);
            } catch (HttpJson.ToolCallException e) {
                throw explain(e);
            }

            ObjectNode out = Json.object();
            out.put("draftId", created.path("id").asText(""));
            out.put("threadId", created.path("message").path("threadId").asText(threadId));
            out.put("messageId", created.path("message").path("id").asText(""));
            out.put("to", to);
            out.put("subject", subject);
            // Said out loud in the result, because the timeline is where a reader decides
            // what Relay just did to their mailbox.
            out.put("sent", false);
            out.put("status", "draft");
            out.put("url", "https://mail.google.com/mail/u/0/#drafts");
            return out;
        }

        /**
         * The single network call, isolated so a test can watch it. Everything that would
         * make this tool send mail would have to be added here — and a test asserts nothing
         * but {@link #DRAFTS} ever is.
         */
        JsonNode post(String url, Map<String, String> headers, JsonNode body) throws Exception {
            return HttpJson.send("POST", url, headers, body);
        }

        /**
         * Google answers a token that predates {@code gmail.compose} with 401/403 and
         * "Request had insufficient authentication scopes". That is the same problem as the
         * pre-flight check catches, reached by a different road — a connection whose recorded
         * scope we could not read, or a grant the user revoked from Google's own settings —
         * so it gets the same sentence. The provider's body is never repeated.
         */
        private static RuntimeException explain(HttpJson.ToolCallException failure) {
            int status = failure.status();
            String body = failure.body() == null ? "" : failure.body().toLowerCase(Locale.ROOT);
            if ((status == 401 || status == 403)
                    && (body.contains("insufficient") || body.contains("scope"))) {
                return new HttpJson.ToolCallException(NEEDS_CONSENT, status, failure.body());
            }
            if (status == 401 || status == 403) {
                return new HttpJson.ToolCallException("Google taslağı reddetti (HTTP " + status
                        + "). Bağlantılar'dan Google'a yeniden bağlanmayı dene.", status,
                        failure.body());
            }
            return failure;
        }

        // ---- RFC 2822 ------------------------------------------------------

        /**
         * Headers plus body, base64url-encoded the way {@code drafts.create} wants its
         * {@code raw} field.
         *
         * <p>The body is base64 with an explicit UTF-8 charset rather than dropped in as
         * text: a Turkish reply is not ASCII, and a MIME part that lies about its encoding
         * arrives as mojibake in the reader's client — the one place the user cannot fix it.
         */
        static String raw(String to, String subject, String body, String inReplyTo) {
            StringBuilder mime = new StringBuilder();
            mime.append("To: ").append(address(to)).append("\r\n");
            mime.append("Subject: ").append(encodedWord(subject)).append("\r\n");
            if (!inReplyTo.isBlank()) {
                mime.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
                mime.append("References: ").append(inReplyTo).append("\r\n");
            }
            mime.append("MIME-Version: 1.0\r\n");
            mime.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n");
            mime.append("Content-Transfer-Encoding: base64\r\n\r\n");
            mime.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mime.toString().getBytes(StandardCharsets.UTF_8));
        }

        /** {@code Ayşe Yıldız <ayse@x.dev>} — only the display name may be encoded. */
        static String address(String to) {
            int open = to.lastIndexOf('<');
            int close = to.lastIndexOf('>');
            if (open < 0 || close < open) {
                return encodedWord(to);
            }
            String name = to.substring(0, open).trim();
            if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).trim();
            }
            String angle = to.substring(open, close + 1);
            return name.isEmpty() ? angle : encodedWord(name) + " " + angle;
        }

        /**
         * RFC 2047 encoded word.
         *
         * <p>A header line is US-ASCII, so "Ödeme servisi patlıyor" cannot travel as itself.
         * The 45-byte chunking is not decoration: an encoded word is capped at 75 characters,
         * and a Turkish subject long enough to exceed it comes out truncated or garbled in
         * clients that enforce the limit. Chunks split on code points, never inside one.
         */
        static String encodedWord(String text) {
            if (isAscii(text)) {
                return text;
            }
            List<String> words = new ArrayList<>();
            StringBuilder chunk = new StringBuilder();
            int bytes = 0;
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                int width = new String(Character.toChars(codePoint), 0, Character.charCount(codePoint))
                        .getBytes(StandardCharsets.UTF_8).length;
                if (bytes > 0 && bytes + width > MAX_WORD_BYTES) {
                    words.add(word(chunk.toString()));
                    chunk.setLength(0);
                    bytes = 0;
                }
                chunk.appendCodePoint(codePoint);
                bytes += width;
                i += Character.charCount(codePoint);
            }
            if (chunk.length() > 0) {
                words.add(word(chunk.toString()));
            }
            // Folded onto continuation lines — two encoded words on one line would exceed 78.
            return String.join("\r\n ", words);
        }

        /** 45 UTF-8 bytes → 60 base64 chars → 72 with the "=?UTF-8?B??=" wrapper. */
        private static final int MAX_WORD_BYTES = 45;

        private static String word(String part) {
            return "=?UTF-8?B?"
                    + Base64.getEncoder().encodeToString(part.getBytes(StandardCharsets.UTF_8))
                    + "?=";
        }

        private static boolean isAscii(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 127) {
                    return false;
                }
            }
            return true;
        }

        /**
         * A header value is one line.
         *
         * <p>Every one of these strings arrives from a language model. A newline inside
         * {@code subject} would end the Subject header and start whatever came next —
         * {@code Bcc:} being the interesting one. Folding it back into a space costs a
         * cosmetic space and closes the hole.
         */
        static String oneLine(String value) {
            return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
        }

        /** {@code CAB1@mail.gmail.com} → {@code <CAB1@mail.gmail.com>}; already-angled ids pass. */
        static String messageId(String id) {
            if (id.isBlank()) {
                return "";
            }
            return id.startsWith("<") && id.endsWith(">") ? id : "<" + id + ">";
        }
    }
}
