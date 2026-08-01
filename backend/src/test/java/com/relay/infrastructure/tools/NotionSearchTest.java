package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Notion's missing READ, hit live twice from two directions with one sentence between them:
 * "Bağlantıyı Test Et" on the notion connection threw <em>"notion için kayıtlı bir okuma
 * aracı yok."</em> ({@code ConnectionService.test} probes a provider's cheapest READ), and
 * {@code Coordinator.insertLookupBefore} could not repair an ungrounded Notion write because
 * {@code ToolAgent.lookupToolFor} had nothing to find. {@code notion.search} is that READ,
 * and three things about it are load-bearing.
 *
 * <p>ONE — it can always run. Nothing is required: an empty query asks Notion "what can I
 * see?", which is exactly what the connection test and the inserted lookup step both need.
 *
 * <p>TWO — the projection speaks this codebase's language. Ids come out dashless (the form
 * every other Notion value here is written in), an untitled page projects an empty title
 * rather than an invented one, and none of Notion's envelope — properties, created_by users,
 * covers, cursors — reaches the timeline or the next prompt.
 *
 * <p>THREE — the empty answer diagnoses itself. An integration nobody shared a page with
 * gets an EMPTY list from Notion, not an error: the number-one setup failure arrives dressed
 * as a search that found nothing. Zero results therefore carry the same ••• → Connections
 * sentence the write tools' {@code object_not_found} rewrite carries.
 */
class NotionSearchTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    private static NotionTool.Search tool() {
        return new NotionTool.Search("replay", FIXTURES);
    }

    private static Connection connected() {
        return Connection.of("notion", Map.of("token", "ntn_secretsecret"),
                Instant.parse("2026-08-01T09:00:00Z"));
    }

    // ---- it can always run --------------------------------------------------

    @Test
    void searching_is_a_read_and_opens_no_gate() {
        assertThat(tool().risk()).isEqualTo(RiskLevel.READ);
        assertThat(tool().risk().defaultMode().wire()).isEqualTo("auto");
    }

    @Test
    void an_empty_query_is_a_valid_question_about_everything_shared() {
        assertThat(SchemaValidator.validate(tool().schema(), Json.object()).valid()).isTrue();

        var result = tool().execute(Json.object(), null);
        assertThat(result.ok()).isTrue();
        assertThat(result.data().path("results")).isNotEmpty();
        assertThat(result.data().path("resultCount").asInt()).isEqualTo(3);
    }

    // ---- what reaches Notion ------------------------------------------------

    @Test
    void the_request_carries_query_object_filter_and_page_size_and_nothing_else() throws Exception {
        Recording tool = new Recording();
        ObjectNode params = Json.object();
        params.put("query", "karar kütüğü");
        params.put("objectType", "page");
        params.put("maxResults", 5);

        tool.call(params, connected());

        assertThat(tool.url).isEqualTo("https://api.notion.com/v1/search");
        assertThat(tool.body.size()).isEqualTo(3);
        assertThat(tool.body.path("query").asText()).isEqualTo("karar kütüğü");
        // objectType is this schema's word; Notion's is filter.value under property=object.
        assertThat(tool.body.path("filter").path("property").asText()).isEqualTo("object");
        assertThat(tool.body.path("filter").path("value").asText()).isEqualTo("page");
        assertThat(tool.body.path("page_size").asInt()).isEqualTo(5);
    }

    @Test
    void no_query_and_no_type_sends_neither_field() throws Exception {
        Recording tool = new Recording();

        tool.call(Json.object(), connected());

        assertThat(tool.body.has("query")).isFalse();
        assertThat(tool.body.has("filter")).isFalse();
        assertThat(tool.body.path("page_size").asInt()).isEqualTo(10);
    }

    @Test
    void the_page_size_stays_inside_notions_window() {
        assertThat(NotionTool.Search.pageSize(Json.object())).isEqualTo(10);
        ObjectNode high = Json.object();
        high.put("maxResults", 500);
        assertThat(NotionTool.Search.pageSize(high)).isEqualTo(20);
        ObjectNode low = Json.object();
        low.put("maxResults", 0);
        assertThat(NotionTool.Search.pageSize(low)).isEqualTo(1);
    }

    // ---- what leaves the tool ----------------------------------------------

    /** Trimmed from a live answer of {@code POST /v1/search}: the whole page object, per hit. */
    private static final String RAW = """
            {
              "object": "list",
              "results": [
                {
                  "object": "page",
                  "id": "2f0a1b9c-4d5e-4f60-a1b2-c3d4e5f60718",
                  "created_by": {"object": "user", "id": "5b10a2-user"},
                  "cover": null,
                  "icon": {"type": "emoji", "emoji": "📓"},
                  "url": "https://www.notion.so/Karar-kutugu-2f0a1b9c4d5e4f60a1b2c3d4e5f60718",
                  "properties": {
                    "Ad": {
                      "id": "title",
                      "type": "title",
                      "title": [{"type": "text", "plain_text": "Karar "}, {"type": "text", "plain_text": "kütüğü"}]
                    }
                  }
                },
                {
                  "object": "database",
                  "id": "b7f21c9d-8e3a-4b5c-9d0e-1f2a3b4c5d6f",
                  "title": [{"type": "text", "plain_text": "Operasyon kayıtları"}],
                  "url": "https://www.notion.so/b7f21c9d8e3a4b5c9d0e1f2a3b4c5d6f",
                  "last_edited_by": {"object": "user", "id": "5b10a2-user"}
                }
              ],
              "next_cursor": "eyJ0ZW5hbnQiOiJzZWNyZXQifQ==",
              "has_more": true,
              "request_id": "req_abc123"
            }
            """;

    @Test
    void a_result_is_four_fields_and_none_of_notions_envelope() {
        JsonNode projected = tool().project(Json.parse(RAW));
        String wire = projected.toString();

        assertThat(wire).doesNotContain("created_by").doesNotContain("last_edited_by")
                .doesNotContain("next_cursor").doesNotContain("request_id")
                .doesNotContain("properties").doesNotContain("icon");

        JsonNode page = projected.path("results").get(0);
        // Dashless: the spelling the rest of the codebase writes Notion ids in.
        assertThat(page.path("id").asText()).isEqualTo("2f0a1b9c4d5e4f60a1b2c3d4e5f60718");
        assertThat(page.path("type").asText()).isEqualTo("page");
        assertThat(page.path("title").asText()).isEqualTo("Karar kütüğü");
        assertThat(page.path("url").asText()).contains("notion.so");

        // A database's title lives on the object, not in properties — both spellings land.
        JsonNode database = projected.path("results").get(1);
        assertThat(database.path("type").asText()).isEqualTo("database");
        assertThat(database.path("title").asText()).isEqualTo("Operasyon kayıtları");

        assertThat(projected.path("resultCount").asInt()).isEqualTo(2);
        assertThat(projected.path("truncated").asBoolean(false)).isTrue();
        assertThat(projected.has("note")).as("a list with results needs no diagnosis").isFalse();
    }

    @Test
    void an_untitled_page_projects_an_empty_title_not_an_invented_one() {
        JsonNode projected = tool().project(Json.parse("""
                {"object": "list", "results": [
                  {"object": "page", "id": "8d3c5e71-a9b2-4f0c-8e6d-1f2a3b4c5d6e",
                   "url": "https://www.notion.so/8d3c5e71a9b24f0c8e6d1f2a3b4c5d6e",
                   "properties": {"Ad": {"id": "title", "type": "title", "title": []}}}
                ], "has_more": false}
                """));

        assertThat(projected.path("results").get(0).path("title").asText()).isEmpty();
    }

    // ---- the empty answer ---------------------------------------------------

    /**
     * Token doğru, kimlik doğru, yetkiler doğru — paylaşım yok: Notion bu duruma hata değil
     * boş liste döndürür. Kurulumun bir numaralı hatası, "arama bir şey bulamadı" kılığında
     * gelir; not, yazma araçlarının {@code object_not_found} cümlesindeki üç tıklamayı söyler.
     */
    @Test
    void zero_results_carry_the_share_the_page_note() {
        JsonNode projected = tool().project(
                Json.parse("{\"object\":\"list\",\"results\":[],\"has_more\":false}"));

        assertThat(projected.path("resultCount").asInt()).isZero();
        String note = projected.path("note").asText();
        assertThat(note).contains("•••").contains("Connections").contains("paylaş");
        assertThat(note).isEqualTo(NotionTool.Search.EMPTY_NOTE);
    }

    // ---- plumbing -----------------------------------------------------------

    /** A search that answers itself and remembers exactly what it was asked to send. */
    private static class Recording extends NotionTool.Search {

        private String url;
        private JsonNode body;

        Recording() {
            super("live", FIXTURES);
        }

        @Override
        JsonNode post(String url, Map<String, String> headers, JsonNode body) {
            this.url = url;
            this.body = body;
            return Json.parse("{\"object\":\"list\",\"results\":[],\"has_more\":false}");
        }
    }
}
