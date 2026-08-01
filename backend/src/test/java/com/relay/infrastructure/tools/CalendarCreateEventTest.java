package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rules that make an event an acceptable write into other people's days.
 *
 * <p>A draft sits in a folder until its owner presses send. An invitation does not: it lands
 * on three other calendars and mails three other people, and there is no version of "undo"
 * that unrings that. So the four things this test holds are the four things that make the
 * difference between a meeting and an apology:
 *
 * <ul>
 *   <li><b>It only inserts.</b> The scope Relay asks for, {@code calendar.events}, can also
 *       move and cancel every meeting in the account. The only URL this tool may reach is
 *       {@code /calendars/primary/events} with POST, and an edit that adds a {@code DELETE}
 *       or a {@code PATCH} has to break a test to get there.</li>
 *   <li><b>A guest is an address, never a name.</b> One step upstream,
 *       {@code calendar.listToday} projects attendees as display names, so "Deniz Arslan" is
 *       exactly what the model has to hand — and deriving an address from it would be a
 *       guess sent to a stranger.</li>
 *   <li><b>An hour is read, never guessed.</b> A meeting at a made-up time is worse than no
 *       meeting, because somebody will show up for it.</li>
 *   <li><b>A missing permission is a sentence, not a stack trace.</b> Widening the grant
 *       leaves every existing connection one scope short until its owner reconnects, so this
 *       is the common case on the day the feature ships — and reading mail must keep
 *       working throughout.</li>
 * </ul>
 */
class CalendarCreateEventTest {

    private static final String READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly "
            + "https://www.googleapis.com/auth/calendar.readonly openid email";

    // ---- what leaves the machine ------------------------------------------

    @Test
    void the_only_thing_this_tool_can_do_to_a_calendar_is_add_to_it() throws Exception {
        Recording tool = new Recording();

        tool.call(params(p -> {
            p.put("summary", "Ödeme servisi — takip toplantısı");
            p.put("startsAt", "2026-08-04T15:00:00+03:00");
            p.put("endsAt", "2026-08-04T15:30:00+03:00");
        }), google(GoogleOAuth.SCOPES));

        assertThat(tool.url).startsWith(CalendarCreateEventTool.EVENTS);
        assertThat(tool.url).doesNotContain("/events/");
        assertThat(tool.calls).isEqualTo(1);
        // Google's own default invites nobody; a guest list nobody hears about is the one
        // outcome the approval screen cannot have meant.
        assertThat(tool.url).contains("sendUpdates=all");
    }

    @Test
    void the_event_carries_the_title_the_hour_and_the_guests_that_were_approved() throws Exception {
        Recording tool = new Recording();

        JsonNode result = tool.call(params(p -> {
            p.put("summary", "Ödeme servisi — takip toplantısı");
            p.put("startsAt", "2026-08-04T15:00:00+03:00");
            p.put("endsAt", "2026-08-04T15:30:00+03:00");
            p.put("description", "502'lerin kök nedeni ve kimin bakacağı.");
            p.putArray("attendees").add("ayse@alterteam.dev").add("Deniz <deniz@alterteam.dev>");
        }), google(GoogleOAuth.SCOPES));

        assertThat(tool.body.path("summary").asText()).isEqualTo("Ödeme servisi — takip toplantısı");
        assertThat(tool.body.path("start").path("dateTime").asText()).startsWith("2026-08-04T15:00");
        assertThat(tool.body.path("end").path("dateTime").asText()).startsWith("2026-08-04T15:30");
        assertThat(tool.body.path("attendees").get(0).path("email").asText())
                .isEqualTo("ayse@alterteam.dev");
        // "Deniz <deniz@…>" is an address wearing a label — the address is what is sent.
        assertThat(tool.body.path("attendees").get(1).path("email").asText())
                .isEqualTo("deniz@alterteam.dev");
        assertThat(result.path("invitesSent").asBoolean()).isTrue();
        assertThat(result.path("attendees")).hasSize(2);
        assertThat(result.path("url").asText()).isNotBlank();
    }

    /** No guests, no invitations — and the result says so rather than leaving it to be read. */
    @Test
    void an_event_with_nobody_on_it_claims_no_invitations() throws Exception {
        Recording tool = new Recording();

        JsonNode result = tool.call(params(p -> {
            p.put("summary", "Hazırlık");
            p.put("startsAt", "2026-08-04T09:00:00+03:00");
            p.put("endsAt", "2026-08-04T09:30:00+03:00");
        }), google(GoogleOAuth.SCOPES));

        assertThat(tool.body.has("attendees")).isFalse();
        assertThat(result.path("invitesSent").asBoolean()).isFalse();
    }

    // ---- what is refused --------------------------------------------------

    /**
     * The exact shape the meeting-prep flow produces: {@code calendar.listToday} answers with
     * {@code "attendees": ["Deniz Arslan", …]}, and the next step hands them straight back.
     * Turning that into {@code deniz.arslan@…} would be an invitation to somebody nobody
     * named, so the step fails and the approval gate — already open — lets a person type the
     * address they actually know.
     */
    @Test
    void a_display_name_is_never_turned_into_an_address() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(params(p -> {
            p.put("summary", "Takip");
            p.put("startsAt", "2026-08-04T15:00:00+03:00");
            p.put("endsAt", "2026-08-04T15:30:00+03:00");
            p.putArray("attendees").add("Deniz Arslan");
        }), google(GoogleOAuth.SCOPES)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("e-posta")
                .hasMessageContaining("Deniz Arslan");

        assertThat(tool.calls).isZero();
    }

    @Test
    void an_hour_that_cannot_be_read_is_never_guessed() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(params(p -> {
            p.put("summary", "Takip");
            p.put("startsAt", "perşembe öğleden sonra");
            p.put("endsAt", "2026-08-04T15:30:00+03:00");
        }), google(GoogleOAuth.SCOPES)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("startsAt")
                .hasMessageContaining("ISO-8601");

        assertThat(tool.calls).isZero();
    }

    /** An offset-less timestamp is read in the workspace's configured zone, not invented. */
    @Test
    void a_timestamp_without_an_offset_is_read_in_the_workspaces_own_zone() throws Exception {
        Recording tool = new Recording();

        tool.call(params(p -> {
            p.put("summary", "Takip");
            p.put("startsAt", "2026-08-04T15:00:00");
            p.put("endsAt", "2026-08-04T15:30:00");
        }), google(GoogleOAuth.SCOPES));

        assertThat(tool.body.path("start").path("dateTime").asText()).isEqualTo("2026-08-04T15:00:00+03:00");
        assertThat(tool.body.path("start").path("timeZone").asText()).isEqualTo("Europe/Istanbul");
    }

    @Test
    void a_meeting_that_ends_before_it_starts_never_reaches_google() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(params(p -> {
            p.put("summary", "Takip");
            p.put("startsAt", "2026-08-04T16:00:00+03:00");
            p.put("endsAt", "2026-08-04T15:30:00+03:00");
        }), google(GoogleOAuth.SCOPES)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("bitiş");

        assertThat(tool.calls).isZero();
    }

    // ---- the permission ---------------------------------------------------

    @Test
    void a_read_only_grant_is_refused_before_anything_leaves_the_machine() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(params(p -> {
            p.put("summary", "Takip");
            p.put("startsAt", "2026-08-04T15:00:00+03:00");
            p.put("endsAt", "2026-08-04T15:30:00+03:00");
        }), google(READ_ONLY)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("yalnız okuma")
                .hasMessageContaining("Bağlantılar")
                .hasMessageContaining("yeniden bağlan");

        assertThat(tool.calls).isZero();
    }

    /** The same problem reached the other way: a grant we could not read, revoked by hand. */
    @Test
    void googles_own_scope_rejection_is_told_in_the_same_words_and_quotes_nothing() {
        Recording tool = new Recording();
        tool.failure = HttpJson.failure(403, "www.googleapis.com",
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Request had "
                        + "insufficient authentication scopes\",\"token\":\"ya29.leakedaccesstoken\"}}");

        assertThatThrownBy(() -> tool.call(params(p -> {
            p.put("summary", "Takip");
            p.put("startsAt", "2026-08-04T15:00:00+03:00");
            p.put("endsAt", "2026-08-04T15:30:00+03:00");
        }), google("")))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("yalnız okuma")
                .hasMessageNotContaining("PERMISSION_DENIED")
                .hasMessageNotContaining("ya29.");
    }

    /**
     * The connection that already exists must not break the day the scope widens: Google does
     * not revoke a token because the app started asking for more, and the brief has to keep
     * reading the calendar until its owner gets around to reconnecting.
     */
    @Test
    void a_grant_from_before_the_new_permission_still_reads_the_calendar() {
        Connection old = google(READ_ONLY);

        assertThat(GoogleOAuth.granted(old, GoogleOAuth.CALENDAR_EVENTS_SCOPE)).isFalse();
        assertThat(GoogleOAuth.granted(old, "https://www.googleapis.com/auth/calendar.readonly")).isTrue();
        assertThat(GoogleOAuth.granted(old, "https://www.googleapis.com/auth/gmail.readonly")).isTrue();
        assertThat(GoogleOAuth.granted(google(GoogleOAuth.SCOPES), GoogleOAuth.CALENDAR_EVENTS_SCOPE))
                .isTrue();
        // No recorded scope is unknown, not absent — those tokens predate the field.
        assertThat(GoogleOAuth.granted(google(""), GoogleOAuth.CALENDAR_EVENTS_SCOPE)).isTrue();

        // The new permission is asked for, and every old one is still asked for with it.
        assertThat(GoogleOAuth.SCOPES).contains(GoogleOAuth.CALENDAR_EVENTS_SCOPE)
                .contains(GoogleOAuth.COMPOSE_SCOPE)
                .contains("gmail.readonly")
                .contains("calendar.readonly");
        // calendar.events reaches events only. The bare calendar scope would also hand over
        // the calendar list and its sharing rules, and nothing here reads either.
        assertThat(GoogleOAuth.SCOPES).doesNotContain("auth/calendar ")
                .doesNotEndWith("auth/calendar");
    }

    // ---- plumbing ---------------------------------------------------------

    /** An event tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends CalendarCreateEventTool {

        private String url;
        private JsonNode body;
        private int calls;
        private RuntimeException failure;

        Recording() {
            super("live", new FixtureStore(), null, "Europe/Istanbul");
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
            ObjectNode created = Json.object();
            created.put("id", "evt_takip_toplantisi");
            created.put("summary", body.path("summary").asText(""));
            created.put("status", "confirmed");
            created.put("htmlLink", "https://calendar.google.com/event?eid=evt_takip_toplantisi");
            created.set("start", body.path("start"));
            created.set("end", body.path("end"));
            return created;
        }
    }

    private static Connection google(String scope) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("accessToken", "ya29.test-access-token");
        config.put("refreshToken", "1//test-refresh-token");
        config.put("scope", scope);
        return Connection.of(GoogleOAuth.PROVIDER, config, Instant.parse("2026-08-01T09:00:00Z"));
    }

    private static ObjectNode params(java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode params = Json.object();
        fill.accept(params);
        return params;
    }
}
