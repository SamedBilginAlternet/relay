package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.text.Placeholder;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google Sheets v4 — one namespace, two narrow doors, on the same {@code google}
 * connection Gmail and Calendar already use.
 *
 * <p>Why a spreadsheet at all, in a product whose other writes are Jira records and Slack
 * messages: the spreadsheet is where the people who do not open Jira keep their work. A
 * scan that ends in a Slack message ends in a channel; a scan that ends in a row ends in
 * the sheet the team already reviews on Monday.
 *
 * <p>{@link AppendRow} can only add to the end; {@link ReadRange} can only look, and at no
 * more than fifty rows of the range it was asked for. Each is one endpoint with a test
 * holding it there, because the {@code spreadsheets} scope itself can rewrite every cell of
 * every sheet the account opens — the narrowness is our code's promise, not the grant's.
 *
 * <p>Deliberately <em>not</em> in the brief's {@code SECTIONS} — neither of them. A reading
 * tool in the brief costs two model turns on every refresh whether anybody asked or not;
 * offered to the planner only, {@code sheets.readRange} costs its ~60–130 tokens of schema
 * on the runs whose plan mentions a sheet, and nothing at all on the ones that do not.
 */
public abstract class SheetsTool extends GoogleTool {

    /**
     * Every Sheets tool answers in Relay's own shape: {@code call} reads the provider
     * response and builds the reply, so Google's {@code tableRange}, its nested
     * {@code updates} object and its {@code majorDimension} echo never reach the result.
     */
    @Override
    protected JsonNode project(JsonNode raw) {
        return raw;
    }

    protected static final String API = "https://sheets.googleapis.com/v4/spreadsheets";

    protected SheetsTool(ToolsMode mode, FixtureStore fixtures, GoogleOAuth oauth) {
        super(mode, fixtures, oauth);
    }

    /**
     * {@code https://docs.google.com/spreadsheets/d/1AbC…/edit#gid=0} → {@code 1AbC…}; a
     * bare id passes through untouched. Asked for an id, a model hands over the address it
     * read — taking the id out of it is reading what was given, not inventing what was not.
     */
    static String spreadsheetId(String raw) {
        String value = raw == null ? "" : raw.trim();
        Matcher matcher = URL_ID.matcher(value);
        return matcher.find() ? matcher.group(1) : value;
    }

    private static final Pattern URL_ID =
            Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]+)");

    /**
     * The Google rejections both Sheets tools hit, as one sentence each.
     *
     * <p>The scope problem arrives two ways — a grant we could read was too old, or a grant
     * we could not read was revoked by hand — and both get {@code consent}, the sentence
     * naming the screen to press. {@code parseProblem} is the tool's own wording for
     * "Unable to parse range", because what that means differs: for a write it is almost
     * always the configured tab name, for a read it is the range the step asked for.
     */
    static RuntimeException explain(HttpJson.ToolCallException failure, String consent,
                                    String parseProblem) {
        int status = failure.status();
        String body = failure.body() == null ? "" : failure.body().toLowerCase(Locale.ROOT);
        if ((status == 401 || status == 403)
                && (body.contains("insufficient") || body.contains("scope"))) {
            return new HttpJson.ToolCallException(consent, status, failure.body());
        }
        if (status == 401 || status == 403) {
            return new HttpJson.ToolCallException("Google tabloyu reddetti (HTTP " + status
                    + "). Tablo bu hesapla paylaşılmamış olabilir; Bağlantılar'dan Google'a "
                    + "yeniden bağlanmayı da dene.", status, failure.body());
        }
        if (status == 400 && body.contains("unable to parse range")) {
            return new HttpJson.ToolCallException(parseProblem, status, failure.body());
        }
        if (status == 404) {
            return new HttpJson.ToolCallException("Böyle bir tablo bulunamadı. E-tablo "
                    + "kimliğini ve tablonun bu Google hesabıyla paylaşıldığını kontrol et.",
                    status, failure.body());
        }
        return failure;
    }

    // ------------------------------------------------------------- appendRow

    @Component
    public static class AppendRow extends SheetsTool {

        /**
         * Turkish Sheets calls the first tab {@code Sayfa1}; English calls it {@code Sheet1}.
         * The default is the Turkish one because that is the interface this product ships in,
         * and the connection's {@code defaultSheetName} overrides it for anyone else.
         */
        static final String DEFAULT_SHEET = "Sayfa1";

        /**
         * What a token issued before {@code spreadsheets} is told. It names the screen and
         * the reason, because Google's "insufficient authentication scopes" names neither.
         */
        static final String NEEDS_CONSENT =
                "Google izni tabloya yazmayı kapsamıyor; Bağlantılar'dan Google'a yeniden "
                + "bağlan (yeni izin: e-tablo). Mevcut bağlantın okuma işlerini ve mail "
                + "taslaklarını yapmaya devam ediyor.";

        public AppendRow(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                         GoogleOAuth oauth) {
            super(ToolsMode.parse(mode), fixtures, oauth);
        }

        @Override
        public String name() {
            return "sheets.appendRow";
        }

        @Override
        public String description() {
            return "Append one row to the end of a Google Sheets sheet. spreadsheetId is the "
                    + "long id in the sheet's URL; values is the row, left to right, one string "
                    + "per column. It only ever adds a row — it cannot overwrite or read one. "
                    + "Requires approval.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("spreadsheetId").add("values");
            ObjectNode props = schema.putObject("properties");
            ObjectNode id = props.putObject("spreadsheetId");
            id.put("type", "string");
            id.put("minLength", 8);
            id.put("description", "Spreadsheet id — the long token in "
                    + "docs.google.com/spreadsheets/d/<id>/edit. A full URL is accepted too");
            ObjectNode sheet = props.putObject("sheetName");
            sheet.put("type", "string");
            sheet.put("description", "Tab name, e.g. Sayfa1 — omit to use the connection's default");
            ObjectNode values = props.putObject("values");
            values.put("type", "array");
            values.put("minItems", 1);
            values.put("description", "The row, left to right: one string per column");
            values.putObject("items").put("type", "string");
            return schema;
        }

        /**
         * Resolves the sheet the row will actually land in, before anybody approves it.
         *
         * <p>Same shape as {@code SlackTool.PostMessage}, and for the same reason: a
         * configured default is worth nothing if the tool never reaches for it, and the
         * approval screen has to name the real destination rather than a blank or a
         * {@code {{steps[2].spreadsheetId}}} that no substitution is ever going to fill.
         *
         * <p>It also unwraps a URL. Asked for an id, a model hands over the address it read
         * — {@code https://docs.google.com/spreadsheets/d/1AbC…/edit#gid=0} — and taking the
         * id out of it is reading what was given, not inventing what was not.
         */
        @Override
        public JsonNode withDefaults(JsonNode params, Connection connection) {
            if (!params.isObject()) {
                return params;
            }
            ObjectNode out = ((ObjectNode) params).deepCopy();

            String id = params.path("spreadsheetId").asText("").trim();
            if (id.isEmpty() || Placeholder.unresolved(id)) {
                String fallback = setting(connection, "defaultSpreadsheetId");
                if (fallback != null) {
                    out.put("spreadsheetId", spreadsheetId(fallback));
                }
            } else {
                out.put("spreadsheetId", spreadsheetId(id));
            }

            String sheet = params.path("sheetName").asText("").trim();
            if (sheet.isEmpty() || Placeholder.unresolved(sheet)) {
                String fallback = setting(connection, "defaultSheetName");
                out.put("sheetName", fallback == null ? DEFAULT_SHEET : fallback);
            }
            return out;
        }

        private static String setting(Connection connection, String key) {
            if (connection == null) {
                return null;
            }
            String value = connection.get(key);
            return value == null || value.isBlank() ? null : value.trim();
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            if (!GoogleOAuth.granted(connection, GoogleOAuth.SPREADSHEETS_SCOPE)) {
                throw new HttpJson.ToolCallException(NEEDS_CONSENT);
            }
            String id = spreadsheetId(params.path("spreadsheetId").asText(""));
            String sheet = params.path("sheetName").asText("").trim();
            if (sheet.isEmpty()) {
                sheet = DEFAULT_SHEET;
            }

            ArrayNode row = Json.mapper().createArrayNode();
            for (JsonNode cell : params.path("values")) {
                row.add(cell(cell.asText("")));
            }
            if (row.isEmpty()) {
                throw new HttpJson.ToolCallException("Tabloya satır eklenmedi: yazılacak hiçbir "
                        + "hücre yok. Boş bir satır eklemek bir sonuç değil.");
            }
            ObjectNode body = Json.object();
            body.putArray("values").add(row);

            String url = API + "/" + HttpJson.encode(id) + "/values/" + HttpJson.encode(sheet)
                    + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS";

            JsonNode appended;
            try {
                appended = post(url, headers(connection), body);
            } catch (HttpJson.ToolCallException e) {
                throw explain(e, sheet);
            }

            JsonNode updates = appended.path("updates");
            ObjectNode out = Json.object();
            out.put("spreadsheetId", id);
            out.put("sheetName", sheet);
            out.put("updatedRange", updates.path("updatedRange").asText(""));
            out.put("updatedRows", updates.path("updatedRows").asInt(1));
            out.put("updatedCells", updates.path("updatedCells").asInt(row.size()));
            out.set("values", row);
            // Said out loud, because the timeline is where a reader decides what Relay did to
            // a file other people are also editing.
            out.put("overwritten", false);
            out.put("url", "https://docs.google.com/spreadsheets/d/" + id + "/edit");
            return out;
        }

        /**
         * The single network call, isolated so a test can watch it. Everything that would let
         * this tool overwrite a cell or read one back would have to be added here — and a
         * test asserts nothing but a {@code :append} POST ever is.
         */
        JsonNode post(String url, Map<String, String> headers, JsonNode body) throws Exception {
            return HttpJson.send("POST", url, headers, body);
        }

        /**
         * A cell is text, and stays text.
         *
         * <p>{@code valueInputOption} has two settings and this is the one that matters.
         * {@code USER_ENTERED} parses what it is given the way the browser would, which means
         * a cell a language model wrote beginning with {@code =} becomes a formula in
         * somebody's shared spreadsheet — {@code =IMPORTXML(…)} reaches the network from
         * inside the sheet, {@code =HYPERLINK} rewrites what a reader clicks. {@code RAW}
         * stores the characters. The cost is that "1.500" stays a string rather than becoming
         * a number, and that is the cheaper of the two prices.
         *
         * <p>Newlines are kept: unlike a mail header, a cell may legitimately hold one.
         */
        static String cell(String value) {
            return value == null ? "" : value;
        }

        /**
         * A write's "Unable to parse range" is almost always the configured tab name — the
         * fix lives on the Bağlantılar screen, so the sentence points there.
         */
        private static RuntimeException explain(HttpJson.ToolCallException failure, String sheet) {
            return SheetsTool.explain(failure, NEEDS_CONSENT,
                    "Tabloda \"" + sheet + "\" adlı bir sayfa yok. Bağlantılar'daki Google "
                            + "ayarlarında varsayılan sayfa adını düzelt (Türkçe Sheets'te ilk "
                            + "sayfa Sayfa1, İngilizce'de Sheet1).");
        }
    }

    // ------------------------------------------------------------- readRange

    /**
     * The read that lets a run look at the sheet it writes to.
     *
     * <p>{@code appendRow} shipped write-only, and write-only left a hole in the middle of
     * ordinary work: Relay could add a line to the tracking sheet and could never answer
     * "what is already in it" — no "read the open items and send the digest", no "find the
     * row and add beneath it", and no way for a verifier to check that an append actually
     * landed. Reading is the half of "derin entegrasyon" a chain stands on.
     *
     * <p>What made {@code appendRow} unable to read was never that reading is dangerous —
     * it was that a tool asked only to add a line must not <em>also</em> read. This tool is
     * asked to read, a person can see that in the plan, and READ steps run without a gate
     * exactly like every other read in the product.
     */
    @Component
    public static class ReadRange extends SheetsTool {

        /**
         * The most rows one read may put on the timeline. A tool result travels into the
         * next step's prompt and down the SSE stream; a 5.000-row export would drown both.
         * Fifty rows answer "what is in the tracking sheet" — a run that needs more than
         * fifty is doing analytics, and this is not the tool for that.
         */
        static final int MAX_ROWS = 50;

        /** The same missing grant as {@code appendRow}'s, worded for a step that reads. */
        static final String NEEDS_CONSENT =
                "Google izni e-tabloları kapsamıyor; Bağlantılar'dan Google'a yeniden bağlan "
                + "(yeni izin: e-tablo). Mevcut bağlantın diğer okuma işlerini ve mail "
                + "taslaklarını yapmaya devam ediyor.";

        public ReadRange(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                         GoogleOAuth oauth) {
            super(ToolsMode.parse(mode), fixtures, oauth);
        }

        @Override
        public String name() {
            return "sheets.readRange";
        }

        @Override
        public String description() {
            return "Read cell values from a Google Sheets range. spreadsheetId is the long id "
                    + "in the sheet's URL; range is A1 notation like Sayfa1!A1:D20. Returns the "
                    + "rows as text, at most " + MAX_ROWS + " of them. Read-only: it cannot "
                    + "change a cell.";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("spreadsheetId").add("range");
            ObjectNode props = schema.putObject("properties");
            ObjectNode id = props.putObject("spreadsheetId");
            id.put("type", "string");
            id.put("minLength", 8);
            id.put("description", "Spreadsheet id — the long token in "
                    + "docs.google.com/spreadsheets/d/<id>/edit. A full URL is accepted too");
            ObjectNode range = props.putObject("range");
            range.put("type", "string");
            range.put("minLength", 2);
            range.put("description", "A1 notation, e.g. Sayfa1!A1:D20. Without a sheet name "
                    + "the connection's default tab (or the first tab) is read");
            return schema;
        }

        /**
         * Resolves the sheet before the plan is shown, the same way {@code appendRow} does:
         * the id comes from the connection when the model leaves it blank, a pasted URL is
         * read as the id it contains, and a range with no tab name gets the connection's
         * {@code defaultSheetName} — the user said which tab their work lives in once, and
         * a bare {@code A1:D20} would silently read whatever tab happens to be first.
         */
        @Override
        public JsonNode withDefaults(JsonNode params, Connection connection) {
            if (!params.isObject()) {
                return params;
            }
            ObjectNode out = ((ObjectNode) params).deepCopy();

            String id = params.path("spreadsheetId").asText("").trim();
            if (id.isEmpty() || Placeholder.unresolved(id)) {
                String fallback = setting(connection, "defaultSpreadsheetId");
                if (fallback != null) {
                    out.put("spreadsheetId", spreadsheetId(fallback));
                }
            } else {
                out.put("spreadsheetId", spreadsheetId(id));
            }

            String range = params.path("range").asText("").trim();
            if (!range.isEmpty() && !range.contains("!") && !Placeholder.unresolved(range)) {
                String sheet = setting(connection, "defaultSheetName");
                if (sheet != null) {
                    // Always quoted: 'Takip 2026'!A1:D20 is valid for every tab name,
                    // unquoted is not.
                    out.put("range", "'" + sheet.replace("'", "''") + "'!" + range);
                }
            }
            return out;
        }

        private static String setting(Connection connection, String key) {
            if (connection == null) {
                return null;
            }
            String value = connection.get(key);
            return value == null || value.isBlank() ? null : value.trim();
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) throws Exception {
            if (!GoogleOAuth.granted(connection, GoogleOAuth.SPREADSHEETS_SCOPE)) {
                throw new HttpJson.ToolCallException(NEEDS_CONSENT);
            }
            String id = spreadsheetId(params.path("spreadsheetId").asText(""));
            String range = params.path("range").asText("").trim();

            String url = API + "/" + HttpJson.encode(id) + "/values/" + HttpJson.encode(range)
                    + "?majorDimension=ROWS";

            JsonNode response;
            try {
                response = get(url, headers(connection));
            } catch (HttpJson.ToolCallException e) {
                throw explain(e, NEEDS_CONSENT,
                        "\"" + range + "\" aralığı okunamadı: sayfa adı ya da aralık hatalı "
                                + "(örn. Sayfa1!A1:D20). Bağlantılar'daki varsayılan sayfa "
                                + "adını da kontrol et.");
            }

            // FORMATTED_VALUE (the endpoint's default): every cell arrives as the text a
            // person sees in the browser, so the result is strings and only strings.
            ObjectNode out = Json.object();
            out.put("spreadsheetId", id);
            out.put("range", response.path("range").asText(range));
            ArrayNode rows = out.putArray("rows");
            int total = 0;
            for (JsonNode row : response.path("values")) {
                total++;
                if (total > MAX_ROWS) {
                    continue;
                }
                ArrayNode cells = rows.addArray();
                for (JsonNode cell : row) {
                    cells.add(cell.asText(""));
                }
            }
            out.put("rowCount", rows.size());
            // Said out loud: a digest built from a capped read must not present fifty rows
            // as the whole sheet.
            out.put("truncated", total > MAX_ROWS);
            out.put("url", "https://docs.google.com/spreadsheets/d/" + id + "/edit");
            return out;
        }

        /**
         * The single network call, isolated so a test can watch it. Everything that would
         * let this tool write a cell — {@code :append}, {@code :update}, {@code
         * :batchUpdate} — would have to be added here, and a test asserts nothing but a GET
         * of {@code /values/} ever is.
         */
        JsonNode get(String url, Map<String, String> headers) throws Exception {
            return HttpJson.send("GET", url, headers, null);
        }
    }
}
