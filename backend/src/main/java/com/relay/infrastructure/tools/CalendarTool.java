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
 * Google Calendar v3 — today's events on the primary calendar, using the same
 * {@code google} connection as Gmail.
 */
public abstract class CalendarTool extends GoogleTool {

    /**
     * Every calendar tool answers in Relay's own shape: each {@code call} turns the event
     * resources into what the day looks like, so Google's etags, ical uids, htmlLinks and
     * organizer objects never reach the result.
     */
    @Override
    protected JsonNode project(JsonNode raw) {
        return raw;
    }

    protected static final String API = "https://www.googleapis.com/calendar/v3";

    protected CalendarTool(ToolsMode mode, FixtureStore fixtures, GoogleOAuth oauth) {
        super(mode, fixtures, oauth);
    }

    // ----------------------------------------------------------- listToday

    @Component
    public static class ListToday extends CalendarTool {

        private final String defaultZone;

        public ListToday(@Value("${app.tools.mode:replay}") String mode, FixtureStore fixtures,
                         GoogleOAuth oauth,
                         @Value("${app.brief.timezone:Europe/Istanbul}") String defaultZone) {
            super(ToolsMode.parse(mode), fixtures, oauth);
            this.defaultZone = defaultZone;
        }

        @Override
        public String name() {
            return "calendar.listToday";
        }

        @Override
        public String description() {
            return "List today's Google Calendar events with start time, title, location and attendees. "
                    + "The calendar section of the daily brief.";
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
            ObjectNode zone = props.putObject("timeZone");
            zone.put("type", "string");
            zone.put("description", "IANA time zone for \"today\", e.g. Europe/Istanbul");
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
            int max = params.path("maxResults").asInt(20);
            LocalDate today = LocalDate.now(zone);
            ZonedDateTime from = today.atStartOfDay(zone);
            ZonedDateTime to = today.plusDays(1).atStartOfDay(zone);

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
            out.put("date", today.toString());
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
}
