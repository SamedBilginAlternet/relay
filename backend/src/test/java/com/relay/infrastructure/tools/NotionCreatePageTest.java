package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Notion is the first provider on the shelf a lawyer or an operations manager recognises,
 * and the whole of it is one write. Three things have to hold for that write to be safe.
 *
 * <p>ONE — the destination is the user's, not the model's. A Notion database id is a
 * 32-character uuid; a model asked to "write it down" has no honest way to produce one, and
 * the one the user configured is sitting right there. It is resolved before the approval
 * screen is drawn, so the person approving reads where the page is going.
 *
 * <p>TWO — {@code object_not_found} reads as the permission problem it almost always is.
 * Notion answers it for a page that exists and is simply not shared with the integration,
 * and its own wording sends the reader off to double-check an id that is perfectly correct.
 * This is Slack's {@code /invite @relay} all over again and it is the first thing that goes
 * wrong on a live demo.
 *
 * <p>THREE — {@code ntn_…} never comes back out. It is pasted into a form and stored
 * encrypted, and an error body is quoted onto the timeline and into the log.
 */
class NotionCreatePageTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    private static NotionTool.CreatePage tool() {
        return new NotionTool.CreatePage("replay", FIXTURES);
    }

    private static Connection connected(Map<String, String> config) {
        return Connection.of("notion", config, Instant.parse("2026-08-01T09:00:00Z"));
    }

    // ---- the destination --------------------------------------------------

    @Test
    void a_database_the_model_left_out_comes_from_the_connection() {
        ObjectNode params = Json.object();
        params.put("title", "Aras Kargo — fatura itirazı");
        params.put("content", "Müşteri 14.07 tarihli faturaya itiraz etti.");

        JsonNode resolved = tool().withDefaults(params,
                connected(Map.of("token", "ntn_secretsecret", "parentDatabaseId", "b7f21c")));

        assertThat(resolved.path("parentDatabaseId").asText()).isEqualTo("b7f21c");
    }

    /** A placeholder is not a value: the same hole Slack's {{steps[3].channel}} fell into. */
    @Test
    void a_placeholder_database_is_replaced_the_same_way_a_blank_one_is() {
        ObjectNode params = Json.object();
        params.put("parentDatabaseId", "{{steps[2].databaseId}}");
        params.put("title", "Not");
        params.put("content", "Metin");

        JsonNode resolved = tool().withDefaults(params,
                connected(Map.of("token", "ntn_secretsecret", "parentDatabaseId", "b7f21c")));

        assertThat(resolved.path("parentDatabaseId").asText()).isEqualTo("b7f21c");
    }

    @Test
    void a_database_the_model_did_name_is_left_alone() {
        ObjectNode params = Json.object();
        params.put("parentDatabaseId", "aaa111");
        params.put("title", "Not");
        params.put("content", "Metin");

        JsonNode resolved = tool().withDefaults(params,
                connected(Map.of("token", "ntn_secretsecret", "parentDatabaseId", "b7f21c")));

        assertThat(resolved.path("parentDatabaseId").asText()).isEqualTo("aaa111");
    }

    // ---- nothing is invented ----------------------------------------------

    @Test
    void a_page_with_no_title_or_no_body_never_reaches_notion() {
        NotionTool.CreatePage tool = tool();

        ObjectNode noTitle = Json.object();
        noTitle.put("parentDatabaseId", "b7f21c");
        noTitle.put("content", "Metin");
        assertThat(tool.execute(noTitle, null).error()).contains("title");

        ObjectNode noContent = Json.object();
        noContent.put("parentDatabaseId", "b7f21c");
        noContent.put("title", "Not");
        assertThat(tool.execute(noContent, null).error()).contains("content");
    }

    /** WRITE, so the policy engine opens the approval gate without a rule being written. */
    @Test
    void creating_a_page_asks_before_it_writes() {
        assertThat(tool().risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(tool().risk().defaultMode().wire()).isEqualTo("ask");
    }

    // ---- the error a first-run user actually hits --------------------------

    @Test
    void object_not_found_tells_the_user_to_share_the_page_with_the_integration() {
        String message = NotionTool.explain(404, """
                {"object":"error","status":404,"code":"object_not_found",\
                "message":"Could not find database with ID b7f21c."}""");

        assertThat(message).contains("Connections").contains("•••");
        assertThat(message)
                .as("the user must be told this is a sharing problem, not an id problem")
                .contains("izin");
        assertThat(message).doesNotContain("{").doesNotContain("object_not_found");
    }

    @Test
    void a_rejected_token_names_the_field_the_user_has_to_go_and_fix() {
        assertThat(NotionTool.explain(401, "{\"code\":\"unauthorized\",\"message\":\"API token is invalid.\"}"))
                .contains("ntn_").contains("token");
    }

    @Test
    void an_unparseable_body_still_produces_a_sentence() {
        assertThat(NotionTool.explain(500, "<html>Bad gateway</html>"))
                .isEqualTo("Notion isteği reddetti (HTTP 500).");
        assertThat(NotionTool.explain(429, null)).contains("istek sınırına");
    }

    // ---- the token ---------------------------------------------------------

    @Test
    void an_integration_token_is_blanked_out_of_any_quoted_body() {
        assertThat(HttpJson.redact("token=ntn_1a2b3c4d5e6f7g8h rejected"))
                .isEqualTo("token=**** rejected");
        assertThat(NotionTool.explain(400, "{\"message\":\"ntn_1a2b3c4d5e6f7g8h is not valid\"}"))
                .doesNotContain("ntn_1a2b3c");
    }
}
