package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "Yarın toplantım var mı?" — the events between now and the end of the next few days.
 *
 * <p>{@code calendar.listToday} cannot answer that question and never could: its window ends
 * at midnight, so every question about tomorrow came back empty from a calendar that had the
 * meeting in it. This tool is that tool with the window opened, and it is a separate class
 * because "today" is the brief's section and must keep meaning exactly today.
 *
 * <p>The window is generous on purpose (a week by default). Narrowing it to the one day the
 * question names would need the model to do date arithmetic in a query string, and a
 * mis-parsed "yarın" would look identical to an empty calendar.
 */
@Component
public class CalendarUpcomingTool extends CalendarTool {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 30;

    private final String defaultZone;

    public CalendarUpcomingTool(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                                GoogleOAuth oauth,
                                @Value("${app.brief.timezone:Europe/Istanbul}") String defaultZone) {
        super(ToolsMode.parse(mode), fixtures, oauth);
        this.defaultZone = defaultZone;
    }

    @Override
    public String name() {
        return "calendar.listUpcoming";
    }

    @Override
    public String description() {
        return "List the upcoming Google Calendar events (today and the next days) with date, "
                + "start time, title, location and attendees. Use it for questions about tomorrow, "
                + "this week or the next meeting.";
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
        ObjectNode days = props.putObject("days");
        days.put("type", "integer");
        days.put("minimum", 1);
        days.put("maximum", MAX_DAYS);
        days.put("description", "How many days ahead to look, starting today (default 7)");
        ObjectNode zone = props.putObject("timeZone");
        zone.put("type", "string");
        zone.put("description", "IANA time zone the days are counted in, e.g. Europe/Istanbul");
        ObjectNode max = props.putObject("maxResults");
        max.put("type", "integer");
        max.put("minimum", 1);
        max.put("maximum", 50);
        max.put("description", "How many events to return (default 20)");
        return schema;
    }

    @Override
    protected JsonNode call(JsonNode params, Connection connection) throws Exception {
        ZoneId zone = zoneOf(params.path("timeZone").asText(defaultZone));
        int days = Math.min(MAX_DAYS, Math.max(1, params.path("days").asInt(DEFAULT_DAYS)));
        int max = Math.min(50, Math.max(1, params.path("maxResults").asInt(20)));
        LocalDate today = LocalDate.now(zone);
        ZonedDateTime from = today.atStartOfDay(zone);
        ZonedDateTime to = today.plusDays(days).atStartOfDay(zone);

        String url = API + "/calendars/primary/events"
                + "?singleEvents=true&orderBy=startTime&maxResults=" + max
                + "&timeMin=" + HttpJson.encode(from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                + "&timeMax=" + HttpJson.encode(to.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        JsonNode response = HttpJson.send("GET", url, headers(connection), null);

        ObjectNode out = Json.object();
        ArrayNode events = out.putArray("events");
        for (JsonNode event : response.path("items")) {
            if ("cancelled".equals(event.path("status").asText(""))) {
                continue;
            }
            ObjectNode item = events.addObject();
            item.put("id", event.path("id").asText(""));
            item.put("title", event.path("summary").asText("(başlıksız)"));
            item.put("start", when(event.path("start")));
            item.put("end", when(event.path("end")));
            item.put("allDay", event.path("start").hasNonNull("date"));
            item.put("location", event.path("location").asText(""));
            item.put("url", event.path("htmlLink").asText(""));
            item.put("meetingUrl", event.path("hangoutLink").asText(""));
            ArrayNode attendees = item.putArray("attendees");
            for (JsonNode attendee : event.path("attendees")) {
                attendees.add(attendee.path("displayName").asText(attendee.path("email").asText("")));
            }
        }
        out.put("total", events.size());
        out.put("from", today.toString());
        out.put("to", today.plusDays(days).toString());
        out.put("timeZone", zone.getId());
        return out;
    }

    private static String when(JsonNode node) {
        String dateTime = node.path("dateTime").asText("");
        return dateTime.isBlank() ? node.path("date").asText("") : dateTime;
    }

    private static ZoneId zoneOf(String raw) {
        try {
            return ZoneId.of(raw == null || raw.isBlank() ? "Europe/Istanbul" : raw);
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Istanbul");
        }
    }
}
