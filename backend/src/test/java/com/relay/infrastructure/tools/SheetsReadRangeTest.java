package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rules that make reading a shared spreadsheet acceptable.
 *
 * <p>{@code sheets.appendRow} shipped with "it cannot read" as a listed guarantee, so a
 * tool that reads has to say precisely how that guarantee changes: it does not. The append
 * tool still cannot read — a tool asked to add a line must not also look around. This tool
 * is asked to look, the plan says so, and what it may do is bounded the same way the
 * append is:
 *
 * <ul>
 *   <li><b>It only reads.</b> The {@code spreadsheets} scope can rewrite every cell of
 *       every sheet the account opens; the one URL this tool may reach is a GET of
 *       {@code /values/}, and an edit that adds {@code :append} or {@code :batchUpdate}
 *       has to break a test to get there.</li>
 *   <li><b>Fifty rows, and it says so.</b> A tool result travels into the next step's
 *       prompt and down the SSE stream; a whole-sheet export would drown both, and a
 *       digest built from a capped read must not present the cap as the whole sheet.</li>
 *   <li><b>The destination is the user's.</b> Same as the append: the configured sheet
 *       resolves before the plan runs, a pasted URL is read as the id it contains, and a
 *       tab-less range gets the configured tab rather than whichever happens to be
 *       first.</li>
 * </ul>
 */
class SheetsReadRangeTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String SHEET_ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-x";
    private static final String READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly "
            + "https://www.googleapis.com/auth/calendar.readonly openid email";

    // ---- what leaves the machine ------------------------------------------

    @Test
    void the_only_thing_this_tool_can_do_to_a_sheet_is_look_at_it() throws Exception {
        Recording tool = new Recording();

        tool.call(read("Sayfa1!A1:D20"), google(GoogleOAuth.SCOPES));

        assertThat(tool.url).contains("/values/");
        assertThat(tool.url).contains(SHEET_ID);
        // Everything that writes lives on other verbs and other suffixes; none may appear.
        assertThat(tool.url).doesNotContain(":append").doesNotContain(":update")
                .doesNotContain(":clear").doesNotContain(":batchUpdate");
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    void what_the_sheet_answers_is_rows_of_text_and_a_link() throws Exception {
        Recording tool = new Recording();
        tool.rows = 3;

        JsonNode result = tool.call(read("Sayfa1!A1:D20"), google(GoogleOAuth.SCOPES));

        assertThat(result.path("spreadsheetId").asText()).isEqualTo(SHEET_ID);
        assertThat(result.path("range").asText()).isNotBlank();
        assertThat(result.path("rows")).hasSize(3);
        assertThat(result.path("rows").get(0).get(0).isTextual()).isTrue();
        assertThat(result.path("rowCount").asInt()).isEqualTo(3);
        assertThat(result.path("truncated").asBoolean(true)).isFalse();
        assertThat(result.path("url").asText()).contains(SHEET_ID);
        // Google's majorDimension echo and anything else of its envelope stays behind.
        assertThat(result.toString()).doesNotContain("majorDimension");
    }

    /**
     * The cap is the projection's promise: a result travels into the next prompt and onto
     * the timeline, and it has to admit being a window rather than posing as the sheet.
     */
    @Test
    void more_rows_than_the_cap_come_back_capped_and_the_result_says_so() throws Exception {
        Recording tool = new Recording();
        tool.rows = SheetsTool.ReadRange.MAX_ROWS + 10;

        JsonNode result = tool.call(read("Sayfa1!A:F"), google(GoogleOAuth.SCOPES));

        assertThat(result.path("rows")).hasSize(SheetsTool.ReadRange.MAX_ROWS);
        assertThat(result.path("rowCount").asInt()).isEqualTo(SheetsTool.ReadRange.MAX_ROWS);
        assertThat(result.path("truncated").asBoolean(false)).isTrue();
    }

    // ---- the destination --------------------------------------------------

    @Test
    void the_configured_sheet_and_tab_resolve_before_the_plan_runs() {
        SheetsTool.ReadRange tool = new SheetsTool.ReadRange("replay", FIXTURES, null);
        Connection connection = google(GoogleOAuth.SCOPES,
                Map.of("defaultSpreadsheetId", SHEET_ID, "defaultSheetName", "Takip"));

        ObjectNode missing = Json.object();
        missing.put("range", "A1:F50");
        JsonNode resolved = tool.withDefaults(missing, connection);

        assertThat(resolved.path("spreadsheetId").asText()).isEqualTo(SHEET_ID);
        // A bare range would read whichever tab happens to be first; the user already said
        // which tab their work lives in. Quoted, because 'Takip 2026'!A1 is valid always.
        assertThat(resolved.path("range").asText()).isEqualTo("'Takip'!A1:F50");

        // A range that already names its tab is the model reading the goal — left alone.
        ObjectNode explicit = Json.object();
        explicit.put("range", "Arşiv!A1:B5");
        assertThat(tool.withDefaults(explicit, connection).path("range").asText())
                .isEqualTo("Arşiv!A1:B5");
    }

    @Test
    void a_pasted_url_is_read_as_the_id_it_contains_and_nothing_else_is_invented() {
        SheetsTool.ReadRange tool = new SheetsTool.ReadRange("replay", FIXTURES, null);

        ObjectNode params = Json.object();
        params.put("spreadsheetId",
                "https://docs.google.com/spreadsheets/d/" + SHEET_ID + "/edit#gid=0");
        params.put("range", "A1:D20");

        JsonNode resolved = tool.withDefaults(params, google(GoogleOAuth.SCOPES));
        assertThat(resolved.path("spreadsheetId").asText()).isEqualTo(SHEET_ID);
        // No defaultSheetName configured → the range stays bare; Google reads the first tab.
        assertThat(resolved.path("range").asText()).isEqualTo("A1:D20");

        // And with nothing configured at all, a missing id fails the gate, not the provider.
        ObjectNode empty = Json.object();
        empty.put("range", "A1:D20");
        assertThat(SchemaValidator.validate(tool.schema(),
                tool.withDefaults(empty, google(GoogleOAuth.SCOPES))).valid()).isFalse();
    }

    // ---- what is refused ----------------------------------------------------

    /** A read over "whatever you find" is not a step anybody can verify. */
    @Test
    void a_read_without_a_range_never_reaches_google() {
        SheetsTool.ReadRange tool = new SheetsTool.ReadRange("replay", FIXTURES, null);

        ObjectNode params = Json.object();
        params.put("spreadsheetId", SHEET_ID);

        assertThat(SchemaValidator.validate(tool.schema(), params).valid()).isFalse();
        assertThat(tool.execute(params, null).mode()).isEqualTo("rejected");
    }

    /** READ runs without a gate — that is the whole point of the risk ladder. */
    @Test
    void reading_a_range_is_auto_and_asks_nobody() {
        SheetsTool.ReadRange tool = new SheetsTool.ReadRange("replay", FIXTURES, null);
        assertThat(tool.risk()).isEqualTo(RiskLevel.READ);
        assertThat(tool.risk().defaultMode().wire()).isEqualTo("auto");
        // Same namespace, same connection: the read rides the grant the append already has.
        assertThat(tool.provider()).isEqualTo("google");
    }

    // ---- the permission -----------------------------------------------------

    @Test
    void a_grant_without_the_sheets_permission_is_refused_before_anything_leaves() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(read("Sayfa1!A1:D20"), google(READ_ONLY)))
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

        assertThatThrownBy(() -> tool.call(read("Sayfa1!A1:D20"), google("")))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("yeniden bağlan")
                .hasMessageNotContaining("PERMISSION_DENIED")
                .hasMessageNotContaining("ya29.");
    }

    /** A range Google cannot parse is the step's own mistake, and the message shows the shape. */
    @Test
    void an_unparseable_range_names_the_range_and_the_fix() {
        Recording tool = new Recording();
        tool.failure = HttpJson.failure(400, "sheets.googleapis.com",
                "{\"error\":{\"code\":400,\"message\":\"Unable to parse range: Sekme!A1:D20\"}}");

        assertThatThrownBy(() -> tool.call(read("Sekme!A1:D20"), google(GoogleOAuth.SCOPES)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("Sekme!A1:D20")
                .hasMessageContaining("Sayfa1!A1:D20");
    }

    // ---- plumbing ---------------------------------------------------------

    /** A sheets tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends SheetsTool.ReadRange {

        private String url;
        private int calls;
        private int rows = 2;
        private RuntimeException failure;

        Recording() {
            super("live", FIXTURES, null);
        }

        @Override
        protected Map<String, String> headers(Connection connection) {
            return Map.of("Authorization", "Bearer test-token");
        }

        @Override
        JsonNode get(String url, Map<String, String> headers) {
            this.url = url;
            this.calls++;
            if (failure != null) {
                throw failure;
            }
            ObjectNode response = Json.object();
            response.put("range", "Sayfa1!A1:D" + rows);
            response.put("majorDimension", "ROWS");
            ArrayNode values = response.putArray("values");
            for (int i = 0; i < rows; i++) {
                values.addArray().add("2026-08-0" + (i % 9 + 1)).add("3").add("KAN-11").add("not");
            }
            return response;
        }
    }

    private static ObjectNode read(String range) {
        ObjectNode params = Json.object();
        params.put("spreadsheetId", SHEET_ID);
        params.put("range", range);
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
