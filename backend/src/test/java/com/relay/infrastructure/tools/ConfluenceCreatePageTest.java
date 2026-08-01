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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Confluence is a second Atlassian product on a connection Relay already holds, and that is
 * the whole design: the moment this tool asks for a credential of its own, the user is
 * typing the same API token into a second form. Four claims have to stay true.
 *
 * <p>ONE — it rides the {@code jira} connection. {@code provider()} says so, and the
 * Confluence root is derived from the Jira {@code baseUrl} rather than asked for: a second
 * URL field would be a second thing to get wrong about the same site.
 *
 * <p>TWO — the destination space is the user's, never the model's. {@code defaultSpaceKey}
 * resolves before the approval screen, a placeholder resolves the same way, and a key
 * Confluence does not answer for fails with the sentence naming the setting.
 *
 * <p>THREE — it can only create. Two endpoints, watched by a test: one GET that turns a
 * space key into the id the v2 API wants, one POST that makes the page.
 *
 * <p>FOUR — the body is wrapped, not "converted". Storage format is XHTML; every line lands
 * as an escaped paragraph, so model-written markup arrives as text and markdown arrives
 * verbatim instead of half-rendered.
 */
class ConfluenceCreatePageTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    private static ConfluenceTool.CreatePage tool() {
        return new ConfluenceTool.CreatePage("replay", FIXTURES);
    }

    private static Connection jira(Map<String, String> extra) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("baseUrl", "https://sirket.atlassian.net");
        config.put("email", "ad.soyad@sirket.com");
        config.put("apiToken", "ATATT3xFfGF0secret");
        config.putAll(extra);
        return Connection.of("jira", config, Instant.parse("2026-08-01T09:00:00Z"));
    }

    // ---- one connection, two products --------------------------------------

    /** The google precedent: gmail/calendar/sheets on one connection, confluence on jira's. */
    @Test
    void the_page_rides_the_jira_connection_instead_of_asking_for_a_second_one() {
        assertThat(tool().provider()).isEqualTo("jira");
        assertThat(tool().name()).isEqualTo("confluence.createPage");
    }

    @Test
    void the_wiki_root_is_derived_from_the_jira_base_url_never_asked_for() {
        assertThat(ConfluenceTool.wikiBase(jira(Map.of())))
                .isEqualTo("https://sirket.atlassian.net/wiki");
        assertThat(ConfluenceTool.wikiBase(jira(Map.of("baseUrl", "https://sirket.atlassian.net/"))))
                .isEqualTo("https://sirket.atlassian.net/wiki");
        // A base someone already stored with /wiki on it must not become /wiki/wiki.
        assertThat(ConfluenceTool.wikiBase(jira(Map.of("baseUrl", "https://sirket.atlassian.net/wiki"))))
                .isEqualTo("https://sirket.atlassian.net/wiki");
    }

    // ---- the destination ----------------------------------------------------

    @Test
    void a_space_the_model_left_out_comes_from_the_connection() {
        ObjectNode params = Json.object();
        params.put("title", "Sprint 14 kararları");
        params.put("content", "Ödeme servisi staging'e alındı.");

        JsonNode resolved = tool().withDefaults(params, jira(Map.of("defaultSpaceKey", "DOC")));

        assertThat(resolved.path("spaceKey").asText()).isEqualTo("DOC");
    }

    @Test
    void a_placeholder_space_is_replaced_the_same_way_a_blank_one_is() {
        ObjectNode params = Json.object();
        params.put("spaceKey", "{{steps[2].spaceKey}}");
        params.put("title", "Not");
        params.put("content", "Metin");

        JsonNode resolved = tool().withDefaults(params, jira(Map.of("defaultSpaceKey", "DOC")));

        assertThat(resolved.path("spaceKey").asText()).isEqualTo("DOC");
    }

    @Test
    void a_space_the_model_did_name_is_left_alone() {
        ObjectNode params = Json.object();
        params.put("spaceKey", "ENG");
        params.put("title", "Not");
        params.put("content", "Metin");

        assertThat(tool().withDefaults(params, jira(Map.of("defaultSpaceKey", "DOC")))
                .path("spaceKey").asText()).isEqualTo("ENG");
    }

    @Test
    void without_a_space_anywhere_the_step_fails_before_anything_leaves() {
        Recording tool = new Recording();

        ObjectNode params = Json.object();
        params.put("spaceKey", "");
        params.put("title", "Not");
        params.put("content", "Metin");

        assertThatThrownBy(() -> tool.call(params, jira(Map.of())))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("defaultSpaceKey");
        assertThat(tool.urls).isEmpty();
    }

    // ---- nothing is invented, WRITE stops on a human ------------------------

    @Test
    void a_page_with_no_title_or_no_body_never_reaches_confluence() {
        ConfluenceTool.CreatePage tool = tool();

        ObjectNode noTitle = Json.object();
        noTitle.put("spaceKey", "DOC");
        noTitle.put("content", "Metin");
        assertThat(tool.execute(noTitle, null).error()).contains("title");

        ObjectNode noContent = Json.object();
        noContent.put("spaceKey", "DOC");
        noContent.put("title", "Not");
        assertThat(SchemaValidator.validate(tool.schema(), noContent).valid()).isFalse();
    }

    @Test
    void creating_a_page_asks_before_it_writes() {
        assertThat(tool().risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(tool().risk().defaultMode().wire()).isEqualTo("ask");
    }

    // ---- the two endpoints, and only the two --------------------------------

    @Test
    void one_get_resolves_the_space_and_one_post_creates_the_page_nothing_else() throws Exception {
        Recording tool = new Recording();

        JsonNode result = tool.call(page("DOC", "Sprint 14 kararları",
                "Ödeme servisi staging'e alındı.\n\nİkinci karar yarın."), jira(Map.of()));

        assertThat(tool.urls).hasSize(2);
        assertThat(tool.urls.get(0))
                .startsWith("https://sirket.atlassian.net/wiki/api/v2/spaces?keys=DOC");
        assertThat(tool.urls.get(1)).isEqualTo("https://sirket.atlassian.net/wiki/api/v2/pages");
        // The id the POST carries is the one the GET answered — not the key, not a guess.
        assertThat(tool.body.path("spaceId").asText()).isEqualTo("786433");
        assertThat(tool.body.path("title").asText()).isEqualTo("Sprint 14 kararları");
        assertThat(result.path("created").asBoolean()).isTrue();
        assertThat(result.path("spaceKey").asText()).isEqualTo("DOC");
        assertThat(result.path("url").asText()).contains("/wiki");
    }

    /** Storage format is XHTML: model text is escaped into it, never interpreted as it. */
    @Test
    void the_body_is_wrapped_as_escaped_paragraphs_not_pretend_markdown() throws Exception {
        Recording tool = new Recording();

        tool.call(page("DOC", "Not", "birinci satır\n<script>alert(1)</script>\n**kalın** & co"),
                jira(Map.of()));

        String value = tool.body.path("body").path("value").asText();
        assertThat(tool.body.path("body").path("representation").asText()).isEqualTo("storage");
        assertThat(value).isEqualTo("<p>birinci satır</p>"
                + "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"
                + "<p>**kalın** &amp; co</p>");
    }

    // ---- the failures a first run actually hits ------------------------------

    /** An unknown key answers as an empty list, not a 404 — the sentence has to name the fix. */
    @Test
    void a_space_confluence_does_not_answer_for_names_the_setting_to_check() {
        Recording tool = new Recording();
        tool.emptySpaces = true;

        assertThatThrownBy(() -> tool.call(page("YOK", "Not", "Metin"), jira(Map.of())))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("YOK")
                .hasMessageContaining("defaultSpaceKey");
        assertThat(tool.urls).hasSize(1);
    }

    /**
     * The same token Jira accepted a step earlier: a Confluence 403 is almost never the
     * credential, it is the product not being enabled or the space not being reachable —
     * and Jira's "check your token" sentence would send the reader to fix the wrong thing.
     */
    @Test
    void a_403_says_confluence_may_not_be_enabled_instead_of_blaming_the_token() {
        String message = ConfluenceTool.explain(403,
                "{\"errors\":[{\"status\":403,\"title\":\"Forbidden\"}]}");

        assertThat(message).contains("Confluence").contains("açık olmayabilir");
        assertThat(message).doesNotContain("Forbidden").doesNotContain("{");
    }

    @Test
    void a_404_points_at_the_wiki_not_at_jira() {
        assertThat(ConfluenceTool.explain(404, "")).contains("/wiki");
    }

    @Test
    void a_401_names_the_shared_atlassian_credential() {
        assertThat(ConfluenceTool.explain(401, "")).contains("API token").contains("Jira");
    }

    @Test
    void an_unparseable_body_still_produces_a_sentence() {
        assertThat(ConfluenceTool.explain(500, "<html>Bad gateway</html>"))
                .isEqualTo("Confluence isteği reddetti (HTTP 500).");
        assertThat(ConfluenceTool.explain(429, null)).contains("istek sınırına");
    }

    // ---- plumbing ------------------------------------------------------------

    /** A confluence tool that answers itself and remembers every URL it was sent to. */
    private static class Recording extends ConfluenceTool.CreatePage {

        private final List<String> urls = new ArrayList<>();
        private JsonNode body;
        private boolean emptySpaces;

        Recording() {
            super("live", FIXTURES);
        }

        @Override
        JsonNode get(String url, Map<String, String> headers) {
            urls.add(url);
            ObjectNode out = Json.object();
            var results = out.putArray("results");
            if (!emptySpaces) {
                results.addObject().put("id", "786433").put("key", "DOC").put("name", "Dokümanlar");
            }
            return out;
        }

        @Override
        JsonNode post(String url, Map<String, String> headers, JsonNode body) {
            urls.add(url);
            this.body = body;
            ObjectNode created = Json.object();
            created.put("id", "425990147");
            created.put("title", body.path("title").asText());
            ObjectNode links = created.putObject("_links");
            links.put("webui", "/spaces/DOC/pages/425990147");
            links.put("base", "https://sirket.atlassian.net/wiki");
            return created;
        }
    }

    private static ObjectNode page(String spaceKey, String title, String content) {
        ObjectNode params = Json.object();
        params.put("spaceKey", spaceKey);
        params.put("title", title);
        params.put("content", content);
        return params;
    }
}
