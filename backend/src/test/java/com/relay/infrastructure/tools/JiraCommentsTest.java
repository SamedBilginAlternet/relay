package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.port.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * "Jira ticket yorumlarını getir" was a request with no tool behind it, and the reason it
 * was awkward to add is Atlassian Document Format: a one-sentence comment arrives as four
 * levels of nested JSON. Handing that to the user — or to the summarising model — buries
 * the sentence under its own markup, so these tests pin the way back down to plain text,
 * and pin what an issue with nothing on it answers, because that is the case where an
 * agent is most tempted to invent a discussion.
 */
class JiraCommentsTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    @Test
    void an_adf_body_is_read_back_as_the_sentence_someone_wrote() {
        JsonNode body = Json.parse("""
                {"type":"doc","version":1,"content":[
                  {"type":"paragraph","content":[
                    {"type":"text","text":"Gateway ekibine "},
                    {"type":"mention","attrs":{"id":"5b1","text":"@Elif Kaya"}},
                    {"type":"text","text":" baktı."}]},
                  {"type":"bulletList","content":[
                    {"type":"listItem","content":[
                      {"type":"paragraph","content":[{"type":"text","text":"timeout 30sn"}]}]},
                    {"type":"listItem","content":[
                      {"type":"paragraph","content":[{"type":"text","text":"retry 3"}]}]}]},
                  {"type":"paragraph","content":[
                    {"type":"text","text":"Log: "},
                    {"type":"inlineCard","attrs":{"url":"https://sentry.io/relay/4471"}}]}]}""");

        assertThat(JiraTool.plain(body)).isEqualTo("""
                Gateway ekibine @Elif Kaya baktı.

                - timeout 30sn
                - retry 3

                Log: https://sentry.io/relay/4471""");
    }

    /** A line break inside a paragraph is a line break, not a lost space. */
    @Test
    void a_hard_break_survives_the_trip_to_plain_text() {
        JsonNode body = Json.parse("""
                {"type":"doc","version":1,"content":[{"type":"paragraph","content":[
                  {"type":"text","text":"Birinci satır"},
                  {"type":"hardBreak"},
                  {"type":"text","text":"ikinci satır"}]}]}""");

        assertThat(JiraTool.plain(body)).isEqualTo("Birinci satır\nikinci satır");
    }

    /** REST v2 answers with a bare string. It is already what we wanted. */
    @Test
    void a_body_that_is_already_plain_text_is_left_alone() {
        assertThat(JiraTool.plain(Json.parse("\"Gateway ekibine ticket açıldı.\"")))
                .isEqualTo("Gateway ekibine ticket açıldı.");
    }

    @Test
    void a_page_of_comments_becomes_who_said_what_and_when() {
        JsonNode page = Json.parse("""
                {"total":2,"comments":[
                  {"id":"10318",
                   "author":{"displayName":"Elif Kaya"},
                   "created":"2026-07-31T08:42:00.000+0300",
                   "updated":"2026-07-31T08:42:00.000+0300",
                   "body":{"type":"doc","version":1,"content":[
                     {"type":"paragraph","content":[{"type":"text","text":"Gateway ekibine ticket açıldı."}]}]}},
                  {"id":"10302",
                   "author":{"emailAddress":"deniz@alterteam.dev"},
                   "created":"2026-07-30T17:05:00.000+0300",
                   "updated":"2026-07-30T17:11:00.000+0300",
                   "body":{"type":"doc","version":1,"content":[
                     {"type":"paragraph","content":[{"type":"text","text":"Staging'de tekrar ettim."}]}]}}]}""");

        ObjectNode out = JiraTool.comments(page, "KAN-4", "https://alterteam.atlassian.net/browse/KAN-4");

        assertThat(out.path("issueKey").asText()).isEqualTo("KAN-4");
        assertThat(out.path("total").asInt()).isEqualTo(2);
        assertThat(out.path("returned").asInt()).isEqualTo(2);
        assertThat(out.path("comments").get(0).path("author").asText()).isEqualTo("Elif Kaya");
        assertThat(out.path("comments").get(0).path("text").asText())
                .isEqualTo("Gateway ekibine ticket açıldı.");
        // Untouched since it was written — saying "düzenlendi" would be a small lie.
        assertThat(out.path("comments").get(0).has("updated")).isFalse();
        assertThat(out.path("comments").get(1).path("updated").asText()).endsWith("17:11:00.000+0300");
        // A deleted or restricted account still has to be attributable to somebody.
        assertThat(out.path("comments").get(1).path("author").asText()).isEqualTo("deniz@alterteam.dev");
        assertThat(out.has("note")).isFalse();
    }

    /** Silence is a finding. It is said out loud so nothing downstream fills it in. */
    @Test
    void an_issue_with_no_comments_says_so_instead_of_answering_with_nothing() {
        ObjectNode out = JiraTool.comments(Json.parse("{\"total\":0,\"comments\":[]}"), "KAN-9", "");

        assertThat(out.path("comments")).isEmpty();
        assertThat(out.path("total").asInt()).isZero();
        assertThat(out.path("note").asText()).contains("KAN-9").contains("yorum yok");
    }

    @Test
    void the_tool_refuses_a_call_without_an_issue_and_replays_one_with_it() {
        JiraTool.GetComments tool = new JiraTool.GetComments("replay", FIXTURES);

        assertThat(tool.execute(Json.object(), null).ok()).isFalse();

        ObjectNode params = Json.object();
        params.put("issueKey", "KAN-4");
        ToolResult result = tool.execute(params, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().path("issueKey").asText()).isEqualTo("KAN-4");
        assertThat(result.data().path("comments")).isNotEmpty();
        assertThat(result.data().path("comments").get(0).path("author").asText()).isNotBlank();
        assertThat(result.data().path("comments").get(0).path("text").asText()).isNotBlank();
    }
}
