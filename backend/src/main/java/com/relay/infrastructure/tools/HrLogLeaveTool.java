package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.ToolResult;
import com.relay.application.text.Placeholder;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code hr.logLeave} — one leave record into the leave ledger, and the reason İK exists
 * as a member of the crew at all (#171).
 *
 * <p>The Ekip screen's law is that a member exists only if a registered tool produced it,
 * so "İK" could not be a persona bolted onto the interface — it had to enter as a real
 * tool over a real system. The system exists: the leave-tracking sheet the
 * {@code izin-talepleri} playbook writes into (#169). What that playbook used until now
 * was {@code sheets.appendRow} with a free {@code values[]}, which meant the <em>shape</em>
 * of a leave record was whatever the model felt like that morning: person and dates in a
 * different column order per run is a ledger nobody can read back. This tool is the same
 * single Sheets values-append endpoint with a purpose: a fixed, meaningful row —
 * {@code person · startDate · endDate · type} — and nothing else.
 *
 * <p><b>Which spreadsheet.</b> Its own optional connection key {@code leaveSpreadsheetId},
 * falling back to the general {@code defaultSpreadsheetId}. The leave ledger is usually a
 * different file from the general tracking sheet — the blocker scan's rows and people's
 * absence records do not belong in one tab — but a small workspace that keeps everything
 * in one file should not have to configure the same id twice. Both keys are editable on
 * Bağlantılar → Google. The tab follows the file: a dedicated leave file gets the sheet
 * every Turkish spreadsheet starts with ({@code Sayfa1}), while the fallback file keeps
 * its own configured {@code defaultSheetName} — borrowing the general file's tab name
 * into a different file would aim at a tab that likely does not exist there.
 *
 * <p><b>Scope.</b> {@code spreadsheets}, the grant {@code sheets.appendRow} already asked
 * for — zero new consent, zero new setup (docs/INTEGRATIONS.md §5.5).
 *
 * <p><b>person is a name, not prose and not plumbing.</b> It is deliberately NOT in
 * {@code ToolAgent.HUMAN_TEXT_FIELDS}: the filler gate exists to stop template
 * <em>sentences</em>, and a short proper name would trip a prose heuristic it was never
 * meant for. But an invented name must still fail, so {@code ToolAgent.isIdentifier}
 * treats {@code person} as an identifier: a name that appears in no mail, no earlier
 * result and no goal never reaches the ledger.
 */
@Component
public class HrLogLeaveTool extends SheetsTool {

    /** The leave kinds the interface suggests; free text is accepted on purpose. */
    static final String TYPES = "yıllık | rapor | ücretsiz";

    /** Same missing grant as the other Sheets writes, worded for the leave ledger. */
    static final String NEEDS_CONSENT =
            "Google izni tabloya yazmayı kapsamıyor; izin kaydı işlenemedi. Bağlantılar'dan "
            + "Google'a yeniden bağlan (yeni izin: e-tablo). Mevcut bağlantın okuma işlerini "
            + "yapmaya devam ediyor.";

    public HrLogLeaveTool(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                          GoogleOAuth oauth) {
        super(ToolsMode.parse(mode), fixtures, oauth);
    }

    @Override
    public String name() {
        return "hr.logLeave";
    }

    @Override
    public String description() {
        return "Append one leave record to the configured leave-tracking sheet: person, "
                + "startDate, endDate and an optional type (" + TYPES + " or free text). "
                + "The person must come from the request that was read — never invented. "
                + "Dates are ISO (YYYY-MM-DD) and endDate must not be before startDate. "
                + "It only ever adds a row. Requires approval.";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.WRITE;
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.putArray("required").add("spreadsheetId").add("person")
                .add("startDate").add("endDate");
        ObjectNode props = schema.putObject("properties");
        ObjectNode id = props.putObject("spreadsheetId");
        id.put("type", "string");
        id.put("minLength", 8);
        id.put("description", "Leave sheet id — comes from the connection's "
                + "leaveSpreadsheetId (or defaultSpreadsheetId); leave blank to use it");
        ObjectNode person = props.putObject("person");
        person.put("type", "string");
        person.put("minLength", 2);
        person.put("description", "Who is on leave, exactly as named in the request that "
                + "was read — never invented");
        ObjectNode start = props.putObject("startDate");
        start.put("type", "string");
        start.put("minLength", 10);
        start.put("description", "First day of the leave, ISO date: YYYY-MM-DD");
        ObjectNode end = props.putObject("endDate");
        end.put("type", "string");
        end.put("minLength", 10);
        end.put("description", "Last day of the leave, ISO date: YYYY-MM-DD — not before startDate");
        ObjectNode type = props.putObject("type");
        type.put("type", "string");
        type.put("description", "Kind of leave: " + TYPES + " — or the requester's own words; "
                + "omit when the mail does not say");
        ObjectNode sheet = props.putObject("sheetName");
        sheet.put("type", "string");
        sheet.put("description", "Tab name — omit to use the leave ledger's default");
        return schema;
    }

    /**
     * Resolves the ledger before anybody approves the record — the same reason
     * {@code sheets.appendRow} resolves its destination in {@code withDefaults}: the
     * approval screen has to name the real file, not a blank or a template marker.
     */
    @Override
    public JsonNode withDefaults(JsonNode params, Connection connection) {
        if (!params.isObject()) {
            return params;
        }
        ObjectNode out = ((ObjectNode) params).deepCopy();

        String leave = setting(connection, "leaveSpreadsheetId");
        String id = params.path("spreadsheetId").asText("").trim();
        if (id.isEmpty() || Placeholder.unresolved(id)) {
            String fallback = leave != null ? leave : setting(connection, "defaultSpreadsheetId");
            if (fallback != null) {
                out.put("spreadsheetId", spreadsheetId(fallback));
            }
        } else {
            out.put("spreadsheetId", spreadsheetId(id));
        }

        String sheet = params.path("sheetName").asText("").trim();
        if (sheet.isEmpty() || Placeholder.unresolved(sheet)) {
            // The tab follows the file — see the class javadoc.
            String fallback = leave != null ? null : setting(connection, "defaultSheetName");
            out.put("sheetName", fallback == null ? AppendRow.DEFAULT_SHEET : fallback);
        }

        // An absent type is an honest fact (the mail did not say), not a hole for the
        // model to fill: normalised to empty so the ledger's fourth column stays a column.
        if (out.path("type").asText("").isBlank() || Placeholder.unresolved(out.path("type").asText(""))) {
            out.put("type", "");
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

    /**
     * The date check runs in front of both modes. {@code call} never happens in replay,
     * and a leave record whose end precedes its start is wrong in the demo exactly as it
     * is wrong live — the gate must not depend on which mode caught it.
     */
    @Override
    public ToolResult execute(JsonNode params, Connection connection) {
        String problem = dateProblem(params);
        if (problem != null) {
            return ToolResult.error(problem, 0, "rejected");
        }
        return super.execute(params, connection);
    }

    /** The Turkish sentence refusing the dates, or {@code null} when they are sound. */
    static String dateProblem(JsonNode params) {
        String start = params.path("startDate").asText("").trim();
        String end = params.path("endDate").asText("").trim();
        if (start.isEmpty() || end.isEmpty()) {
            return null; // the schema gate names the missing field better than we can
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(start);
            to = LocalDate.parse(end);
        } catch (DateTimeParseException e) {
            return "İzin tarihleri okunamadı: başlangıç ve bitiş YYYY-AA-GG biçiminde olmalı "
                    + "(gelen: " + start + " → " + end + "). Kayıt işlenmedi.";
        }
        if (to.isBefore(from)) {
            return "İzin bitişi başlangıçtan önce olamaz: " + start + " → " + end
                    + ". Kayıt işlenmedi — tarihleri maildeki talebe göre düzelt.";
        }
        return null;
    }

    @Override
    protected JsonNode call(JsonNode params, Connection connection) throws Exception {
        if (!GoogleOAuth.granted(connection, GoogleOAuth.SPREADSHEETS_SCOPE)) {
            throw new HttpJson.ToolCallException(NEEDS_CONSENT);
        }
        String id = spreadsheetId(params.path("spreadsheetId").asText(""));
        String sheet = params.path("sheetName").asText("").trim();
        if (sheet.isEmpty()) {
            sheet = AppendRow.DEFAULT_SHEET;
        }

        // The fixed shape is the whole tool: one record, four columns, always this order.
        ArrayNode row = Json.mapper().createArrayNode();
        row.add(AppendRow.cell(params.path("person").asText("")));
        row.add(AppendRow.cell(params.path("startDate").asText("")));
        row.add(AppendRow.cell(params.path("endDate").asText("")));
        row.add(AppendRow.cell(params.path("type").asText("")));

        ObjectNode body = Json.object();
        body.putArray("values").add(row);

        // The same narrow door as sheets.appendRow: RAW (a cell is text, never a live
        // formula) and INSERT_ROWS (the record lands under the last one, nothing is
        // overwritten). A test watches that no other Sheets endpoint is ever reached.
        String url = API + "/" + HttpJson.encode(id) + "/values/" + HttpJson.encode(sheet)
                + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS";

        JsonNode appended;
        try {
            appended = post(url, headers(connection), body);
        } catch (HttpJson.ToolCallException e) {
            throw SheetsTool.explain(e, NEEDS_CONSENT,
                    "İzin tablosunda \"" + sheet + "\" adlı bir sayfa yok. Bağlantılar'daki "
                            + "Google ayarlarında izin tablosunu ve sayfa adını kontrol et "
                            + "(Türkçe Sheets'te ilk sayfa Sayfa1, İngilizce'de Sheet1).");
        }

        // The projection is the record, not Google's envelope: person and the two dates
        // as they were approved, where the row landed, and the file a reader can open.
        ObjectNode out = Json.object();
        out.put("person", params.path("person").asText(""));
        out.put("startsAt", params.path("startDate").asText(""));
        out.put("endsAt", params.path("endDate").asText(""));
        out.put("type", params.path("type").asText(""));
        out.put("updatedRange", appended.path("updates").path("updatedRange").asText(""));
        out.put("url", "https://docs.google.com/spreadsheets/d/" + id + "/edit");
        return out;
    }

    /**
     * The single network call, isolated so a test can watch it — the same promise
     * {@code sheets.appendRow} makes: anything that would let this tool read a cell or
     * overwrite one would have to be added here, and a test asserts nothing but a
     * {@code :append} POST ever is.
     */
    JsonNode post(String url, Map<String, String> headers, JsonNode body) throws Exception {
        return HttpJson.send("POST", url, headers, body);
    }
}
