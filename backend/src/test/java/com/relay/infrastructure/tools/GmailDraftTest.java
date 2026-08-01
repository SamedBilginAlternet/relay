package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rules that make a draft an acceptable write into someone's mailbox.
 *
 * <p>Four of them, and each one is here because the alternative is a bad day:
 *
 * <ul>
 *   <li><b>It never sends.</b> The product promise is "Relay will not mail on your behalf",
 *       and this test is the only thing holding it. The scope Relay asks for,
 *       {@code gmail.compose}, is restricted and permits {@code messages.send} too — Gmail
 *       has no draft-only scope — so the guarantee is ours to keep in code: the only URL
 *       this tool may reach is {@code /drafts}, and an edit that adds {@code messages/send}
 *       has to break a test to get there.</li>
 *   <li><b>A reply belongs to its conversation.</b> A draft that starts a new thread next to
 *       the mail it answers is worse than no draft — the user has to notice before sending.</li>
 *   <li><b>Turkish survives.</b> A mail header is US-ASCII. "Ödeme servisi patlıyor" written
 *       into one as itself arrives as mojibake in the recipient's client, where nobody can
 *       fix it any more.</li>
 *   <li><b>A missing permission is a sentence, not a stack trace.</b> Asking for
 *       {@code gmail.compose} means every existing connection is short one scope until its
 *       owner reconnects, so this is the common case on the day the feature ships.</li>
 * </ul>
 */
class GmailDraftTest {

    private static final String READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly "
            + "https://www.googleapis.com/auth/calendar.readonly openid email";

    // ---- the conversation -------------------------------------------------

    @Test
    void a_reply_is_hung_under_the_conversation_it_answers() throws Exception {
        Recording tool = new Recording();

        JsonNode result = tool.call(params(p -> {
            p.put("to", "Ayşe Yıldız <ayse@alterteam.dev>");
            p.put("subject", "Re: Ödeme servisi staging'de patlıyor");
            p.put("body", "Bakıyorum, 14:00'ten önce dönerim.");
            p.put("threadId", "18f2c9a10b3d4e01");
            p.put("inReplyTo", "CAB1x9@mail.gmail.com");
        }), google(GoogleOAuth.SCOPES));

        // Gmail threads on threadId…
        assertThat(tool.body.path("message").path("threadId").asText()).isEqualTo("18f2c9a10b3d4e01");
        // …every other mail client threads on these two, once the user presses send.
        String mime = mime(tool.body);
        assertThat(mime).contains("In-Reply-To: <CAB1x9@mail.gmail.com>");
        assertThat(mime).contains("References: <CAB1x9@mail.gmail.com>");
        assertThat(result.path("threadId").asText()).isEqualTo("18f2c9a10b3d4e01");
    }

    /**
     * {@code In-Reply-To} names the mail being answered by its RFC 2822 Message-ID. Gmail's
     * own handle for that mail — {@code 19fbb2c9e643f6f6} — is a different thing, and it is
     * what the reply step had to hand: {@code gmail.getMessage} returned the Gmail id and
     * never the header. It went out as {@code In-Reply-To: <19fbb2c9e643f6f6>}.
     *
     * <p>Gmail itself hid the mistake, because the draft also carries {@code threadId} and
     * Gmail threads on that. Opened in Outlook, Apple Mail or a list archive, the reply
     * falls out of the conversation it answers. A header that is wrong is worse than one
     * that is absent, so an id that is not a Message-ID is left out and {@code threadId}
     * carries the reply on its own.
     */
    @Test
    void a_gmail_id_is_not_a_message_id_and_never_goes_in_the_header() throws Exception {
        Recording tool = new Recording();

        tool.call(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Re: 502");
            p.put("body", "Bakıyorum.");
            p.put("threadId", "18f2c9a10b3d4e01");
            p.put("inReplyTo", "19fbb2c9e643f6f6");
        }), google(GoogleOAuth.SCOPES));

        String mime = mime(tool.body);
        assertThat(mime).doesNotContain("In-Reply-To").doesNotContain("References");
        assertThat(mime).doesNotContain("19fbb2c9e643f6f6");
        // The conversation still holds in Gmail, which is where the draft is read.
        assertThat(tool.body.path("message").path("threadId").asText()).isEqualTo("18f2c9a10b3d4e01");
    }

    /** The read step now carries the header, so the reply step has something true to use. */
    @Test
    void the_read_step_hands_over_the_rfc_message_id() {
        ObjectNode message = Json.object();
        message.put("id", "19fbb2c9e643f6f6");
        message.put("threadId", "19fbb2c9e643f6f6");
        message.putArray("headers");
        ObjectNode payload = message.putObject("payload");
        payload.putArray("headers")
                .addObject().put("name", "Message-ID").put("value", "<CAB1x9@mail.gmail.com>");

        assertThat(GmailTool.header(message, "Message-ID")).isEqualTo("<CAB1x9@mail.gmail.com>");
        // Case is not guaranteed on the wire; Gmail sends Message-ID, others send Message-Id.
        assertThat(GmailTool.header(message, "message-id")).isEqualTo("<CAB1x9@mail.gmail.com>");
    }

    /** A brand-new mail has no conversation, and must not invent one. */
    @Test
    void a_mail_that_answers_nothing_carries_no_thread() throws Exception {
        Recording tool = new Recording();

        tool.call(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Toplantı notları");
            p.put("body", "Ekte.");
        }), google(GoogleOAuth.SCOPES));

        assertThat(tool.body.path("message").has("threadId")).isFalse();
        assertThat(mime(tool.body)).doesNotContain("In-Reply-To").doesNotContain("References");
    }

    /** The Re: is added before approval, so the screen shows the subject Gmail will store. */
    @Test
    void the_reply_prefix_is_visible_on_the_approval_screen_not_added_behind_it() {
        GmailTool.CreateDraft tool = new GmailTool.CreateDraft("replay", new FixtureStore(), null);

        JsonNode reply = tool.withDefaults(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Ödeme servisi patlıyor");
            p.put("body", "Bakıyorum.");
            p.put("threadId", "18f2c9a10b3d4e01");
        }), null);
        assertThat(reply.path("subject").asText()).isEqualTo("Re: Ödeme servisi patlıyor");

        // Already prefixed, or not a reply at all: left exactly as written.
        JsonNode again = tool.withDefaults(reply, null);
        assertThat(again.path("subject").asText()).isEqualTo("Re: Ödeme servisi patlıyor");
        JsonNode fresh = tool.withDefaults(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Toplantı notları");
            p.put("body", "Ekte.");
        }), null);
        assertThat(fresh.path("subject").asText()).isEqualTo("Toplantı notları");
    }

    // ---- Turkish ----------------------------------------------------------

    @Test
    void a_turkish_subject_reaches_gmail_as_the_words_that_were_written() throws Exception {
        Recording tool = new Recording();
        String subject = "Re: Ödeme servisi staging'de patlıyor — çağrılar 502 dönüyor";

        tool.call(params(p -> {
            p.put("to", "Ayşe Yıldız <ayse@alterteam.dev>");
            p.put("subject", subject);
            p.put("body", "Bakıyorum.");
        }), google(GoogleOAuth.SCOPES));

        String mime = mime(tool.body);
        String header = headerOf(mime, "Subject");
        // RFC 2047, not raw UTF-8 bytes on a header line.
        assertThat(header).startsWith("=?UTF-8?B?").doesNotContain("Ödeme");
        assertThat(decodeWords(header)).isEqualTo(subject);
        // Every encoded word stays inside the 75-character limit, folded onto its own line.
        for (String word : header.split("\r\n ")) {
            assertThat(word.length()).isLessThanOrEqualTo(75);
        }
        // The display name is encoded; the address it belongs to is not.
        assertThat(headerOf(mime, "To")).contains("<ayse@alterteam.dev>").doesNotContain("Ayşe");
        assertThat(decodeWords(headerOf(mime, "To"))).isEqualTo("Ayşe Yıldız <ayse@alterteam.dev>");
    }

    @Test
    void the_body_keeps_its_turkish_letters() throws Exception {
        Recording tool = new Recording();
        String body = "Merhaba Ayşe,\n\nÖdeme servisine bakıyorum; öğleden sonra dönüş yapacağım.\n\nİyi çalışmalar";

        tool.call(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Re: 502");
            p.put("body", body);
        }), google(GoogleOAuth.SCOPES));

        String mime = mime(tool.body);
        assertThat(mime).contains("Content-Type: text/plain; charset=\"UTF-8\"");
        assertThat(bodyOf(mime)).isEqualTo(body);
    }

    // ---- it never sends ---------------------------------------------------

    @Test
    void nothing_but_the_drafts_endpoint_is_ever_called() throws Exception {
        Recording tool = new Recording();

        JsonNode result = tool.call(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Re: 502");
            p.put("body", "Bakıyorum.");
            p.put("threadId", "18f2c9a10b3d4e01");
        }), google(GoogleOAuth.SCOPES));

        assertThat(tool.calls).isEqualTo(1);
        assertThat(tool.url).isEqualTo("https://gmail.googleapis.com/gmail/v1/users/me/drafts");
        assertThat(tool.url).doesNotContain("send");
        // And the timeline says so in as many words.
        assertThat(result.path("sent").asBoolean(true)).isFalse();
        assertThat(result.path("status").asText()).isEqualTo("draft");
        assertThat(result.path("draftId").asText()).isNotBlank();
        // A write, so the approval gate opens by default (ARCHITECTURE §6).
        assertThat(tool.risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(tool.risk().defaultMode().wire()).isEqualTo("ask");
    }

    /**
     * A header value is one line. Both of these arrive from a language model, and a newline
     * inside one would end its header and start whichever the next characters spell.
     */
    @Test
    void a_forged_header_cannot_ride_in_on_a_parameter() throws Exception {
        Recording tool = new Recording();

        tool.call(params(p -> {
            p.put("to", "ayse@alterteam.dev\r\nBcc: herkes@alterteam.dev");
            p.put("subject", "Merhaba\nX-Relay-Injected: yes");
            p.put("body", "Bakıyorum.");
        }), google(GoogleOAuth.SCOPES));

        // The text survives as text — what it must never become is a header of its own.
        assertThat(mime(tool.body).split("\r\n\r\n", 2)[0].split("\r\n"))
                .noneMatch(line -> line.startsWith("Bcc:"))
                .noneMatch(line -> line.startsWith("X-Relay-Injected:"))
                .containsExactly("To: ayse@alterteam.dev Bcc: herkes@alterteam.dev",
                        "Subject: Merhaba X-Relay-Injected: yes",
                        "MIME-Version: 1.0",
                        "Content-Type: text/plain; charset=\"UTF-8\"",
                        "Content-Transfer-Encoding: base64");
    }

    // ---- the missing permission -------------------------------------------

    @Test
    void a_read_only_grant_is_refused_before_anything_leaves_the_machine() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Re: 502");
            p.put("body", "Bakıyorum.");
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
        tool.failure = HttpJson.failure(403, "gmail.googleapis.com",
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Request had "
                        + "insufficient authentication scopes\",\"token\":\"ya29.leakedaccesstoken\"}}");

        assertThatThrownBy(() -> tool.call(params(p -> {
            p.put("to", "ayse@alterteam.dev");
            p.put("subject", "Re: 502");
            p.put("body", "Bakıyorum.");
        }), google("")))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("yalnız okuma")
                .hasMessageNotContaining("PERMISSION_DENIED")
                .hasMessageNotContaining("ya29.");
    }

    /**
     * The connection that already exists must not break the day the scope widens: Google
     * does not revoke a token because the app started asking for more, and the brief has to
     * keep reading mail until its owner gets around to reconnecting.
     */
    @Test
    void a_grant_from_before_the_new_permission_still_reads_mail() {
        Connection old = google(READ_ONLY);

        assertThat(GoogleOAuth.granted(old, GoogleOAuth.COMPOSE_SCOPE)).isFalse();
        assertThat(GoogleOAuth.granted(old, "https://www.googleapis.com/auth/gmail.readonly")).isTrue();
        assertThat(GoogleOAuth.granted(google(GoogleOAuth.SCOPES), GoogleOAuth.COMPOSE_SCOPE)).isTrue();
        // No recorded scope is unknown, not absent — those tokens predate the field.
        assertThat(GoogleOAuth.granted(google(""), GoogleOAuth.COMPOSE_SCOPE)).isTrue();
        assertThat(GoogleOAuth.granted(null, GoogleOAuth.COMPOSE_SCOPE)).isFalse();
        // The new permission is asked for, and the old ones are still asked for with it.
        assertThat(GoogleOAuth.SCOPES).contains(GoogleOAuth.COMPOSE_SCOPE)
                .contains("gmail.readonly").contains("calendar.readonly");
        // gmail.compose is the narrowest scope that can create a draft — it already carries
        // sending, so the two broader ones buy nothing and cost the user's whole mailbox.
        assertThat(GoogleOAuth.SCOPES)
                .doesNotContain("gmail.modify")
                .doesNotContain("https://mail.google.com/");
    }

    // ---- plumbing ---------------------------------------------------------

    /** A draft tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends GmailTool.CreateDraft {

        private String url;
        private JsonNode body;
        private int calls;
        private RuntimeException failure;

        Recording() {
            super("live", new FixtureStore(), null);
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
            created.put("id", "r-4419028837551204416");
            created.putObject("message")
                    .put("id", "18f2c9a1a7f10c33")
                    .put("threadId", body.path("message").path("threadId").asText(""));
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

    private static String mime(JsonNode payload) {
        return new String(Base64.getUrlDecoder().decode(payload.path("message").path("raw").asText()),
                StandardCharsets.UTF_8);
    }

    /** The header's value, continuation lines and all. */
    private static String headerOf(String mime, String name) {
        String headers = mime.split("\r\n\r\n", 2)[0];
        int at = headers.indexOf(name + ": ");
        if (at < 0) {
            return "";
        }
        int from = at + name.length() + 2;
        int end = from;
        while (true) {
            int newline = headers.indexOf("\r\n", end);
            if (newline < 0) {
                return headers.substring(from);
            }
            // A line starting with whitespace continues the header above it.
            if (newline + 2 >= headers.length() || headers.charAt(newline + 2) != ' ') {
                return headers.substring(from, newline);
            }
            end = newline + 2;
        }
    }

    private static String bodyOf(String mime) {
        String encoded = mime.split("\r\n\r\n", 2)[1];
        return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /** Reverses RFC 2047 the way a mail client would, folding included. */
    private static String decodeWords(String header) {
        StringBuilder out = new StringBuilder();
        for (String line : header.split("\r\n ")) {
            for (String part : line.split(" ")) {
                if (part.startsWith("=?UTF-8?B?") && part.endsWith("?=")) {
                    out.append(new String(Base64.getDecoder().decode(
                            part.substring(10, part.length() - 2)), StandardCharsets.UTF_8));
                } else {
                    out.append(out.isEmpty() ? "" : " ").append(part);
                }
            }
        }
        return out.toString();
    }
}
