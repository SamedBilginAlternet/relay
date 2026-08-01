package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Appending to somebody's running log page is the write Notion is actually used for, and
 * three things make it acceptable.
 *
 * <p>ONE — it can only add to the end. {@code PATCH /v1/blocks/{page_id}/children} appends
 * blocks; the body this tool sends carries {@code children} and nothing else, so there is
 * no request in it that could retitle the page, edit a property or touch an existing block.
 * A month of decisions above the cursor stays exactly as it was written.
 *
 * <p>TWO — the destination is the user's, never the model's. Notion has no reading tool
 * here (a deliberate cost decision, recorded on {@code NotionTool}), so a model asked to
 * "add this to the log" has no honest way to produce a page id. It comes from the goal,
 * from the connection's {@code defaultPageId}, or from the human at the editable gate —
 * and a pasted URL is read as the id it carries rather than bounced.
 *
 * <p>THREE — the first-run failure stays fixed. {@code object_not_found} still means "the
 * page is not shared with the integration" far more often than "the id is wrong", and this
 * tool goes through the same {@code notion()} wrapper as {@code createPage}, so it stops
 * with the same three-clicks sentence instead of Notion's misleading one.
 */
class NotionAppendToPageTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String PAGE_ID = "2f0a1b9c4d5e4f60a1b2c3d4e5f60718";

    private static NotionTool.AppendToPage tool() {
        return new NotionTool.AppendToPage("replay", FIXTURES);
    }

    private static Connection connected(Map<String, String> config) {
        return Connection.of("notion", config, Instant.parse("2026-08-01T09:00:00Z"));
    }

    // ---- what leaves the machine ------------------------------------------

    @Test
    void the_patch_carries_blocks_to_append_and_nothing_that_could_edit_the_page() throws Exception {
        Recording tool = new Recording();
        ObjectNode params = Json.object();
        params.put("pageId", PAGE_ID);
        params.put("content", "## Karar\n- Fatura itirazı kabul edildi\nMüşteriye yazıldı.");

        JsonNode result = tool.call(params, connected(Map.of("token", "ntn_secretsecret")));

        assertThat(tool.url).isEqualTo("https://api.notion.com/v1/blocks/" + PAGE_ID + "/children");
        assertThat(tool.calls).isEqualTo(1);
        // children and only children: no properties, no parent, no title — nothing the
        // endpoint could use to change what is already on the page.
        assertThat(tool.body.size()).isEqualTo(1);
        assertThat(tool.body.path("children")).hasSize(3);
        assertThat(tool.body.path("children").get(0).path("type").asText()).isEqualTo("heading_2");
        assertThat(tool.body.path("children").get(1).path("type").asText())
                .isEqualTo("bulleted_list_item");
        assertThat(tool.body.path("children").get(2).path("type").asText()).isEqualTo("paragraph");

        assertThat(result.path("pageId").asText()).isEqualTo(PAGE_ID);
        assertThat(result.path("appendedBlocks").asInt()).isEqualTo(3);
        assertThat(result.path("appended").asBoolean(false)).isTrue();
        // Notion answers a PATCH with full block objects — created_by users, timestamps,
        // request ids. None of that is the timeline's business.
        assertThat(result.toString()).doesNotContain("created_by").doesNotContain("request_id");
    }

    /** Notion refuses a 2000+ character rich-text run; the cut happens here, not as a 400. */
    @Test
    void a_line_longer_than_notion_accepts_is_cut_instead_of_failing_the_step() throws Exception {
        Recording tool = new Recording();
        ObjectNode params = Json.object();
        params.put("pageId", PAGE_ID);
        params.put("content", "x".repeat(2500));

        tool.call(params, connected(Map.of("token", "ntn_secretsecret")));

        assertThat(tool.body.path("children").get(0).path("paragraph")
                .path("rich_text").get(0).path("text").path("content").asText())
                .hasSize(2000);
    }

    // ---- the destination --------------------------------------------------

    @Test
    void a_page_the_model_left_out_comes_from_the_connections_log_page() {
        ObjectNode params = Json.object();
        params.put("content", "Karar: itiraz kabul.");

        JsonNode resolved = tool().withDefaults(params,
                connected(Map.of("token", "ntn_secretsecret", "defaultPageId", PAGE_ID)));

        assertThat(resolved.path("pageId").asText()).isEqualTo(PAGE_ID);
    }

    /** A placeholder is not a destination — the same hole every container default closes. */
    @Test
    void a_placeholder_page_is_replaced_the_same_way_a_blank_one_is() {
        ObjectNode params = Json.object();
        params.put("pageId", "{{steps[2].pageId}}");
        params.put("content", "Metin");

        JsonNode resolved = tool().withDefaults(params,
                connected(Map.of("token", "ntn_secretsecret", "defaultPageId", PAGE_ID)));

        assertThat(resolved.path("pageId").asText()).isEqualTo(PAGE_ID);
    }

    @Test
    void a_pasted_notion_url_is_read_as_the_page_id_it_carries() {
        assertThat(NotionTool.AppendToPage.pageId(
                "https://www.notion.so/acme/Karar-k%C3%BCt%C3%BC%C4%9F%C3%BC-" + PAGE_ID + "?v=abc123"))
                .isEqualTo(PAGE_ID);
        assertThat(NotionTool.AppendToPage.pageId("2f0a1b9c-4d5e-4f60-a1b2-c3d4e5f60718"))
                .isEqualTo("2f0a1b9c-4d5e-4f60-a1b2-c3d4e5f60718");
        assertThat(NotionTool.AppendToPage.pageId(PAGE_ID)).isEqualTo(PAGE_ID);
        // Something that carries no id at all passes through for the provider to refuse —
        // blanking it would turn a wrong destination into no destination.
        assertThat(NotionTool.AppendToPage.pageId("not-an-id")).isEqualTo("not-an-id");
    }

    /** With no page anywhere, the step fails in front of the gate — nothing is invented. */
    @Test
    void without_a_configured_log_page_the_step_fails_with_the_setting_named() {
        NotionTool.AppendToPage tool = tool();

        ObjectNode params = Json.object();
        params.put("content", "Metin");
        JsonNode resolved = tool.withDefaults(params, connected(Map.of("token", "ntn_x")));

        assertThat(resolved.path("pageId").asText()).isEmpty();
        assertThat(SchemaValidator.validate(tool.schema(), resolved).valid()).isFalse();

        assertThatThrownBy(() -> tool.call(resolved, connected(Map.of("token", "ntn_x"))))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("defaultPageId");
    }

    // ---- nothing is invented ----------------------------------------------

    @Test
    void a_note_with_no_content_never_reaches_notion() {
        NotionTool.AppendToPage tool = tool();

        ObjectNode empty = Json.object();
        empty.put("pageId", PAGE_ID);
        assertThat(tool.execute(empty, null).error()).contains("content");

        ObjectNode blank = Json.object();
        blank.put("pageId", PAGE_ID);
        blank.put("content", "");
        assertThat(SchemaValidator.validate(tool.schema(), blank).valid()).isFalse();
    }

    /** WRITE, so the policy engine opens the approval gate without a rule being written. */
    @Test
    void appending_asks_before_it_writes() {
        assertThat(tool().risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(tool().risk().defaultMode().wire()).isEqualTo("ask");
    }

    // ---- the error a first-run user actually hits --------------------------

    /**
     * The append rides {@code notion()}, the same wrapper {@code createPage} sends through,
     * so an unshared log page stops with the identical share-the-page sentence. Asserted
     * here too, because "reuse" is a claim about behaviour, not about code layout.
     */
    @Test
    void an_unshared_log_page_reads_as_the_sharing_problem_it_is() {
        String message = NotionTool.explain(404, """
                {"object":"error","status":404,"code":"object_not_found",\
                "message":"Could not find block with ID 2f0a1b9c."}""");

        assertThat(message).contains("Connections").contains("•••").contains("izin");
        assertThat(message).doesNotContain("object_not_found");
    }

    // ---- plumbing ---------------------------------------------------------

    /** A Notion tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends NotionTool.AppendToPage {

        private String url;
        private JsonNode body;
        private int calls;

        Recording() {
            super("live", FIXTURES);
        }

        @Override
        JsonNode patch(String url, Map<String, String> headers, JsonNode body) {
            this.url = url;
            this.body = body;
            this.calls++;
            ObjectNode response = Json.object();
            response.put("object", "list");
            response.put("request_id", "req_123");
            var results = response.putArray("results");
            for (int i = 0; i < body.path("children").size(); i++) {
                results.addObject().put("object", "block")
                        .putObject("created_by").put("id", "user_abc");
            }
            return response;
        }
    }
}
