package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code hr.logLeave} is the tool that lets İK exist on the Ekip screen at all (#171),
 * so what it claims has to be exactly what it can do. Four claims:
 *
 * <p>ONE — it rides the {@code google} connection. There is no HR provider to connect
 * (#169): the leave ledger is a Google sheet, and a member holding a credential of its
 * own would be the costume the crew page forbids. Same pattern, same test, as
 * {@code ConfluenceTool.provider() → "jira"}.
 *
 * <p>TWO — the record has a shape. {@code sheets.appendRow} writes whatever
 * {@code values[]} the model orders that morning; a leave ledger whose columns wander is
 * one nobody can read back. This tool writes {@code person · startDate · endDate · type},
 * always, and an end before a start is refused in Turkish before any mode — live or
 * replay — can act on it.
 *
 * <p>THREE — it is the same one narrow door. One {@code :append} POST with {@code RAW}
 * and {@code INSERT_ROWS}; anything that could read a cell or overwrite one has to be
 * added to {@code post()} and break this test on the way.
 *
 * <p>FOUR — the destination is the user's ledger. {@code leaveSpreadsheetId} first,
 * because absence records usually live in their own file; the general
 * {@code defaultSpreadsheetId} as the fallback, so one-file workspaces configure nothing
 * twice. Resolved in {@code withDefaults}, before the approval screen asks anybody.
 */
class HrLogLeaveTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String LEAVE_ID = "1LeAvE-LedGeR-Id-0123456789_abcdefghijklm";
    private static final String GENERAL_ID = "1GeNeRaL-ShEeT-Id-0123456789_abcdefghijk";

    private static HrLogLeaveTool tool() {
        return new HrLogLeaveTool("replay", FIXTURES, null);
    }

    private static ObjectNode record(String person, String start, String end, String type) {
        ObjectNode params = Json.object();
        params.put("spreadsheetId", LEAVE_ID);
        params.put("sheetName", "Sayfa1");
        params.put("person", person);
        params.put("startDate", start);
        params.put("endDate", end);
        params.put("type", type);
        return params;
    }

    private static Connection google(Map<String, String> extra) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("accessToken", "ya29.test-access-token");
        config.put("refreshToken", "1//test-refresh-token");
        config.put("scope", GoogleOAuth.SCOPES);
        config.putAll(extra);
        return Connection.of(GoogleOAuth.PROVIDER, config, Instant.parse("2026-08-01T09:00:00Z"));
    }

    // ---- one connection, no HR credential ----------------------------------

    @Test
    void the_leave_record_rides_the_google_connection_instead_of_inventing_an_hr_one() {
        assertThat(tool().provider()).isEqualTo("google");
        assertThat(tool().name()).isEqualTo("hr.logLeave");
    }

    // ---- the dates ----------------------------------------------------------

    @Test
    void an_end_before_the_start_is_refused_in_turkish_before_any_mode_runs() {
        Recording recording = new Recording();

        ToolResult result = recording.execute(
                record("Deniz Arslan", "2026-08-14", "2026-08-10", "yıllık"), google(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.mode()).isEqualTo("rejected");
        assertThat(result.error()).contains("başlangıçtan önce olamaz")
                .contains("2026-08-14").contains("2026-08-10");
        assertThat(recording.calls).isZero();

        // …and replay mode refuses the same record with the same sentence: the demo must
        // not accept a write the live product would refuse.
        ToolResult replayed = tool().execute(
                record("Deniz Arslan", "2026-08-14", "2026-08-10", "yıllık"), null);
        assertThat(replayed.ok()).isFalse();
        assertThat(replayed.error()).contains("başlangıçtan önce olamaz");
    }

    @Test
    void a_date_that_is_not_a_date_is_refused_in_turkish() {
        ToolResult result = tool().execute(
                record("Deniz Arslan", "önümüzdeki pazartesi", "2026-08-14", ""), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("YYYY-AA-GG");
    }

    @Test
    void a_one_day_leave_where_start_equals_end_is_a_sound_record() {
        assertThat(HrLogLeaveTool.dateProblem(
                record("Deniz Arslan", "2026-08-10", "2026-08-10", "rapor"))).isNull();
    }

    // ---- the shape and the single door --------------------------------------

    @Test
    void the_row_is_person_start_end_type_in_that_order_and_nothing_else() throws Exception {
        Recording recording = new Recording();

        JsonNode result = recording.call(
                record("Deniz Arslan", "2026-08-10", "2026-08-14", "yıllık"), google(Map.of()));

        assertThat(recording.url).contains(":append")
                .contains("valueInputOption=RAW")
                .contains("insertDataOption=INSERT_ROWS");
        assertThat(recording.url).doesNotContain(":update").doesNotContain(":clear")
                .doesNotContain(":batchUpdate").doesNotContain("values/Sayfa1?");
        assertThat(recording.calls).isEqualTo(1);

        JsonNode row = recording.body.path("values").get(0);
        assertThat(row).hasSize(4);
        assertThat(row.get(0).asText()).isEqualTo("Deniz Arslan");
        assertThat(row.get(1).asText()).isEqualTo("2026-08-10");
        assertThat(row.get(2).asText()).isEqualTo("2026-08-14");
        assertThat(row.get(3).asText()).isEqualTo("yıllık");

        // The projection is the record, never Google's envelope.
        assertThat(Json.toMap(result).keySet()).containsExactly(
                "person", "startsAt", "endsAt", "type", "updatedRange", "url");
        assertThat(result.path("person").asText()).isEqualTo("Deniz Arslan");
        assertThat(result.path("startsAt").asText()).isEqualTo("2026-08-10");
        assertThat(result.path("endsAt").asText()).isEqualTo("2026-08-14");
        assertThat(result.toString()).doesNotContain("majorDimension")
                .doesNotContain("tableRange").doesNotContain("updatedCells");
    }

    // ---- the destination -----------------------------------------------------

    @Test
    void the_leave_ledger_beats_the_general_default_sheet() {
        JsonNode resolved = tool().withDefaults(bare(), google(Map.of(
                "leaveSpreadsheetId", LEAVE_ID,
                "defaultSpreadsheetId", GENERAL_ID,
                "defaultSheetName", "Takip")));

        assertThat(resolved.path("spreadsheetId").asText()).isEqualTo(LEAVE_ID);
        // The tab follows the file: the general file's configured tab name must not be
        // aimed into a different file that almost certainly does not have it.
        assertThat(resolved.path("sheetName").asText()).isEqualTo("Sayfa1");
    }

    @Test
    void without_a_leave_ledger_the_general_default_carries_the_record() {
        JsonNode resolved = tool().withDefaults(bare(), google(Map.of(
                "defaultSpreadsheetId", GENERAL_ID,
                "defaultSheetName", "Takip")));

        assertThat(resolved.path("spreadsheetId").asText()).isEqualTo(GENERAL_ID);
        assertThat(resolved.path("sheetName").asText()).isEqualTo("Takip");
    }

    @Test
    void a_pasted_ledger_url_is_read_as_the_id_it_contains() {
        JsonNode resolved = tool().withDefaults(bare(), google(Map.of(
                "leaveSpreadsheetId",
                "https://docs.google.com/spreadsheets/d/" + LEAVE_ID + "/edit#gid=0")));

        assertThat(resolved.path("spreadsheetId").asText()).isEqualTo(LEAVE_ID);
    }

    private static ObjectNode bare() {
        ObjectNode params = Json.object();
        params.put("person", "Deniz Arslan");
        params.put("startDate", "2026-08-10");
        params.put("endDate", "2026-08-14");
        return params;
    }

    // ---- the permission --------------------------------------------------------

    @Test
    void a_grant_without_the_sheets_permission_is_refused_before_anything_leaves() {
        Recording recording = new Recording();
        Connection old = google(Map.of("scope",
                "https://www.googleapis.com/auth/gmail.readonly openid email"));

        assertThatThrownBy(() -> recording.call(
                record("Deniz Arslan", "2026-08-10", "2026-08-14", ""), old))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("izin kaydı işlenemedi")
                .hasMessageContaining("yeniden bağlan");
        assertThat(recording.calls).isZero();
    }

    // ---- plumbing ---------------------------------------------------------------

    /** An hr tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends HrLogLeaveTool {

        private String url;
        private JsonNode body;
        private int calls;

        Recording() {
            super("live", FIXTURES, null);
        }

        @Override
        protected boolean usable(Connection connection) {
            return true;
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
            ObjectNode appended = Json.object();
            appended.putObject("updates")
                    .put("updatedRange", "Sayfa1!A7:D7")
                    .put("updatedRows", 1)
                    .put("updatedCells", body.path("values").get(0).size());
            return appended;
        }
    }
}
