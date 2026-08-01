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
 * Google Sheets v4 — one row, appended to the end of a sheet, on the same {@code google}
 * connection Gmail and Calendar already use.
 *
 * <p>Why a spreadsheet at all, in a product whose other writes are Jira records and Slack
 * messages: the spreadsheet is where the people who do not open Jira keep their work. A
 * scan that ends in a Slack message ends in a channel; a scan that ends in a row ends in
 * the sheet the team already reviews on Monday.
 *
 * <p>Two things it deliberately cannot do. It cannot overwrite: {@code values.append} with
 * {@code INSERT_ROWS} adds after the last used row and never lands on an existing one. And
 * it cannot read: no cell ever comes back through this tool, so a sheet full of salaries
 * cannot arrive on the run timeline by accident. Both are properties of the one endpoint
 * below, and a test holds it there.
 *
 * <p>Deliberately <em>not</em> in the brief's {@code SECTIONS}. A reading tool in the brief
 * costs two model turns on every refresh; this one costs about a hundred tokens on the runs
 * that use it and nothing at all on the ones that do not.
 */
public abstract class SheetsTool extends GoogleTool {

    /**
     * Every Sheets tool answers in Relay's own shape: {@code call} reads the append response
     * and builds the reply, so Google's {@code tableRange}, its nested {@code updates} object
     * and its echo of the spreadsheet id never reach the result.
     */
    @Override
    protected JsonNode project(JsonNode raw) {
        return raw;
    }

    protected static final String API = "https://sheets.googleapis.com/v4/spreadsheets";

    protected SheetsTool(ToolsMode mode, FixtureStore fixtures, GoogleOAuth oauth) {
        super(mode, fixtures, oauth);
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
         * {@code https://docs.google.com/spreadsheets/d/1AbC…/edit#gid=0} → {@code 1AbC…}; a
         * bare id passes through untouched.
         */
        static String spreadsheetId(String raw) {
            String value = raw == null ? "" : raw.trim();
            Matcher matcher = URL_ID.matcher(value);
            return matcher.find() ? matcher.group(1) : value;
        }

        private static final Pattern URL_ID =
                Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]+)");

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
         * Google answers a token that predates {@code spreadsheets} with 401/403 and "Request
         * had insufficient authentication scopes" — the same problem the pre-flight check
         * catches, reached by a different road, so it gets the same sentence. A 404 is the
         * other common one and means something quite different: the id is wrong, or the sheet
         * belongs to somebody who has not shared it with this account.
         */
        private static RuntimeException explain(HttpJson.ToolCallException failure, String sheet) {
            int status = failure.status();
            String body = failure.body() == null ? "" : failure.body().toLowerCase(Locale.ROOT);
            if ((status == 401 || status == 403)
                    && (body.contains("insufficient") || body.contains("scope"))) {
                return new HttpJson.ToolCallException(NEEDS_CONSENT, status, failure.body());
            }
            if (status == 401 || status == 403) {
                return new HttpJson.ToolCallException("Google tabloyu reddetti (HTTP " + status
                        + "). Tablo bu hesapla paylaşılmamış olabilir; Bağlantılar'dan Google'a "
                        + "yeniden bağlanmayı da dene.", status, failure.body());
            }
            if (status == 400 && body.contains("unable to parse range")) {
                return new HttpJson.ToolCallException("Tabloda \"" + sheet + "\" adlı bir sayfa "
                        + "yok. Bağlantılar'daki Google ayarlarında varsayılan sayfa adını "
                        + "düzelt (Türkçe Sheets'te ilk sayfa Sayfa1, İngilizce'de Sheet1).",
                        status, failure.body());
            }
            if (status == 404) {
                return new HttpJson.ToolCallException("Böyle bir tablo bulunamadı. E-tablo "
                        + "kimliğini ve tablonun bu Google hesabıyla paylaşıldığını kontrol et.",
                        status, failure.body());
            }
            return failure;
        }
    }
}
