package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Puts the meeting that was agreed to onto the user's own calendar.
 *
 * <p>Relay could read a day and could write into Jira, Slack, GitHub and the Drafts folder,
 * and the one thing every desk does after a conversation — "let's talk again Thursday" —
 * had nowhere to land. That is the gap this closes, and it costs nothing new to reach: the
 * {@code google} connection, the OAuth seam and the {@code calendar} namespace are already
 * here, only the grant is one scope short.
 *
 * <p>It is a WRITE, so {@link RiskLevel#WRITE} hands it to the approval gate with no extra
 * code, and the gate is where a person reads the title, the hour and the guest list before
 * anybody is invited. That matters more here than for a draft: a draft sits in a folder,
 * an invitation lands in somebody else's day.
 *
 * <p>Deliberately <em>not</em> in the brief's {@code SECTIONS}. A reading tool in the brief
 * costs two model turns on every single refresh; this one costs about a hundred tokens on
 * the runs that actually use it and nothing at all on the ones that do not.
 */
@Component
public class CalendarCreateEventTool extends CalendarTool {

    /** The one endpoint this tool talks to. It inserts; it cannot edit or delete. */
    static final String EVENTS = API + "/calendars/primary/events";

    /**
     * What a token issued before {@code calendar.events} is told. It names the screen and
     * the reason, because Google's "insufficient authentication scopes" names neither.
     */
    static final String NEEDS_CONSENT =
            "Google izni yalnız okuma; takvime kayıt açmak için Bağlantılar'dan Google'a "
            + "yeniden bağlan (yeni izin: takvim etkinliği oluşturma). Mevcut bağlantın "
            + "okuma işlerini yapmaya devam ediyor.";

    private final String defaultZone;

    public CalendarCreateEventTool(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                                   GoogleOAuth oauth,
                                   @Value("${app.brief.timezone:Europe/Istanbul}") String defaultZone) {
        super(ToolsMode.parse(mode), fixtures, oauth);
        this.defaultZone = defaultZone;
    }

    @Override
    public String name() {
        return "calendar.createEvent";
    }

    @Override
    public String description() {
        return "Create an event on the user's primary Google Calendar and invite the "
                + "attendees. Use it when a follow-up meeting was agreed. startsAt/endsAt "
                + "are full ISO-8601 timestamps (2026-08-04T15:00:00+03:00); attendees are "
                + "e-mail addresses, never display names. Requires approval.";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.WRITE;
    }

    @Override
    public JsonNode schema() {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.putArray("required").add("summary").add("startsAt").add("endsAt");
        ObjectNode props = schema.putObject("properties");
        ObjectNode summary = props.putObject("summary");
        summary.put("type", "string");
        summary.put("minLength", 3);
        summary.put("description", "Event title, in the language of the conversation it came from");
        ObjectNode starts = props.putObject("startsAt");
        starts.put("type", "string");
        starts.put("description", "Start, ISO-8601 with offset, e.g. 2026-08-04T15:00:00+03:00");
        ObjectNode ends = props.putObject("endsAt");
        ends.put("type", "string");
        ends.put("description", "End, ISO-8601 with offset — must be after startsAt");
        ObjectNode attendees = props.putObject("attendees");
        attendees.put("type", "array");
        attendees.put("description", "Guest e-mail addresses. Display names are refused — "
                + "a calendar invitation goes to an address, not to a name");
        attendees.putObject("items").put("type", "string");
        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "Agenda or context, plain text");
        return schema;
    }

    /**
     * Google's own default for {@code events.insert} is to invite nobody: the guests are
     * written onto the event and never hear about it. That is the worst of the two answers —
     * the user approved a screen listing three people and three people were not told — so
     * the invitations go out, and the result says out loud that they did.
     */
    private static final String SEND_UPDATES = "?sendUpdates=all";

    @Override
    protected JsonNode call(JsonNode params, Connection connection) throws Exception {
        if (!GoogleOAuth.granted(connection, GoogleOAuth.CALENDAR_EVENTS_SCOPE)) {
            throw new HttpJson.ToolCallException(NEEDS_CONSENT);
        }
        ZoneId zone = zoneOf(defaultZone);
        ZonedDateTime start = instant(params.path("startsAt").asText(""), "startsAt", zone);
        ZonedDateTime end = instant(params.path("endsAt").asText(""), "endsAt", zone);
        if (!end.isAfter(start)) {
            throw new HttpJson.ToolCallException("Takvim kaydı açılmadı: bitiş saati (" + params
                    .path("endsAt").asText("") + ") başlangıçtan (" + params.path("startsAt").asText("")
                    + ") sonra değil.");
        }

        ObjectNode body = Json.object();
        body.put("summary", oneLine(params.path("summary").asText("")));
        String description = params.path("description").asText("");
        if (!description.isBlank()) {
            body.put("description", description);
        }
        put(body.putObject("start"), start, zone);
        put(body.putObject("end"), end, zone);

        ArrayNode invited = Json.mapper().createArrayNode();
        if (params.path("attendees").isArray() && !params.path("attendees").isEmpty()) {
            ArrayNode attendees = body.putArray("attendees");
            for (JsonNode attendee : params.path("attendees")) {
                String email = address(attendee.asText(""));
                attendees.addObject().put("email", email);
                invited.add(email);
            }
        }

        JsonNode created;
        try {
            created = post(EVENTS + SEND_UPDATES, headers(connection), body);
        } catch (HttpJson.ToolCallException e) {
            throw explain(e);
        }

        ObjectNode out = Json.object();
        out.put("eventId", created.path("id").asText(""));
        out.put("title", created.path("summary").asText(body.path("summary").asText("")));
        out.put("start", created.path("start").path("dateTime")
                .asText(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        out.put("end", created.path("end").path("dateTime")
                .asText(end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        out.put("timeZone", zone.getId());
        out.set("attendees", invited);
        out.put("url", created.path("htmlLink").asText(""));
        out.put("meetingUrl", created.path("hangoutLink").asText(""));
        // Said out loud in the result, because the timeline is where a reader decides what
        // Relay just did to other people's days.
        out.put("invitesSent", !invited.isEmpty());
        out.put("status", created.path("status").asText("confirmed"));
        return out;
    }

    /**
     * The single network call, isolated so a test can watch it. Everything that would make
     * this tool change or cancel somebody's existing meeting would have to be added here —
     * and a test asserts nothing but a POST to {@link #EVENTS} ever is.
     */
    JsonNode post(String url, java.util.Map<String, String> headers, JsonNode body) throws Exception {
        return HttpJson.send("POST", url, headers, body);
    }

    /**
     * A guest is an address.
     *
     * <p>This is not pedantry, it is the shape of the data one step upstream:
     * {@code calendar.listToday} projects each attendee as {@code displayName}, so the
     * meeting-prep flow hands the model "Deniz Arslan" and the model passes it straight on.
     * Google answers 400 {@code Invalid attendee}, which is the lucky version. The unlucky
     * one is inventing {@code deniz.arslan@…} from a name — a guess wearing the clothes of
     * a default, and quite possibly a stranger's inbox. So this refuses, and the approval
     * gate is already open in front of it: the person looking at the screen can type the
     * address they know.
     */
    static String address(String attendee) {
        String value = attendee == null ? "" : attendee.trim();
        int open = value.lastIndexOf('<');
        int close = value.lastIndexOf('>');
        if (open >= 0 && close > open) {
            value = value.substring(open + 1, close).trim();
        }
        if (!value.matches("[^\\s@]+@[^\\s@.]+\\.[^\\s@]+")) {
            throw new HttpJson.ToolCallException("Takvim kaydı açılmadı: katılımcı e-posta "
                    + "adresi olmalı, isim değil — \"" + attendee + "\". Davet adrese gider; "
                    + "adresi bilmiyorsan bu adımı katılımcısız onayla ve kişiyi Takvim'den ekle.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * ISO-8601, with or without the offset the model forgot.
     *
     * <p>A bare {@code 2026-08-04T15:00} is read in the workspace's own zone rather than
     * refused: the zone is configured (`app.brief.timezone`), so reading it there is using
     * a setting, not inventing one. Anything that is not a timestamp at all fails the step —
     * a meeting at a guessed hour is worse than no meeting.
     */
    static ZonedDateTime instant(String raw, String field, ZoneId zone) {
        String value = raw == null ? "" : raw.trim();
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(zone);
        } catch (DateTimeParseException ignored) {
            // Falls through to the offset-less form below.
        }
        try {
            return LocalDateTime.parse(value).atZone(zone);
        } catch (DateTimeParseException e) {
            throw new HttpJson.ToolCallException("Takvim kaydı açılmadı: " + field + " ISO-8601 "
                    + "bir zaman damgası değil (\"" + value + "\"). Beklenen biçim: "
                    + "2026-08-04T15:00:00+03:00.");
        }
    }

    private static void put(ObjectNode node, ZonedDateTime when, ZoneId zone) {
        node.put("dateTime", when.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        node.put("timeZone", zone.getId());
    }

    /**
     * Google answers a token that predates {@code calendar.events} with 401/403 and
     * "Request had insufficient authentication scopes". That is the same problem the
     * pre-flight check catches, reached by a different road — a connection whose recorded
     * scope we could not read, or a grant revoked from Google's own settings — so it gets
     * the same sentence. The provider's body is never repeated.
     */
    private static RuntimeException explain(HttpJson.ToolCallException failure) {
        int status = failure.status();
        String body = failure.body() == null ? "" : failure.body().toLowerCase(Locale.ROOT);
        if ((status == 401 || status == 403)
                && (body.contains("insufficient") || body.contains("scope"))) {
            return new HttpJson.ToolCallException(NEEDS_CONSENT, status, failure.body());
        }
        if (status == 401 || status == 403) {
            return new HttpJson.ToolCallException("Google takvim kaydını reddetti (HTTP " + status
                    + "). Bağlantılar'dan Google'a yeniden bağlanmayı dene.", status, failure.body());
        }
        return failure;
    }

    /**
     * A summary is one line. Every one of these strings arrives from a language model, and
     * a newline in a calendar title turns the event list into something nobody can scan.
     */
    static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static ZoneId zoneOf(String raw) {
        try {
            return ZoneId.of(raw == null || raw.isBlank() ? "Europe/Istanbul" : raw);
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Istanbul");
        }
    }
}
