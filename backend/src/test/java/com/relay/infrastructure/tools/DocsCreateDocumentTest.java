package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.google.GoogleOAuth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A Google Doc is born in two API calls — create, then insert — and everything this test
 * file defends sits in the seam between them.
 *
 * <p>ONE — the failure mode that multiplies files. A step that fails is retried (the
 * verifier resends up to twice), and a step that failed <em>after</em> the create succeeded
 * would open a twin document on every retry. So a failed insert is a successful step with
 * {@code contentInserted: false} and a sentence carrying the link — the document the human
 * approved exists, and the result refuses to pretend either more or less than that.
 *
 * <p>TWO — the widened grant must not break the connection that predates it. An old token
 * keeps reading, drafting, scheduling and appending exactly as before; only the document
 * step stops, with the sentence naming the screen to press, never Google's own 403.
 *
 * <p>THREE — two endpoints and only the two, both under the document just created. A tool
 * asked to open a new document must not be able to touch an existing one.
 */
class DocsCreateDocumentTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly "
            + "https://www.googleapis.com/auth/calendar.readonly "
            + "https://www.googleapis.com/auth/spreadsheets openid email";

    // ---- the two calls, and only the two ------------------------------------

    @Test
    void a_document_is_created_once_and_its_text_inserted_into_that_same_document() throws Exception {
        Recording tool = new Recording();

        JsonNode result = tool.call(doc("Toplantı notu — 4 Ağustos",
                "Katılımcılar: Deniz, Ayşe.\nKarar: staging pazartesi."), google(GoogleOAuth.SCOPES));

        assertThat(tool.urls).hasSize(2);
        assertThat(tool.urls.get(0)).isEqualTo("https://docs.googleapis.com/v1/documents");
        assertThat(tool.urls.get(1))
                .isEqualTo("https://docs.googleapis.com/v1/documents/doc-1:batchUpdate");
        assertThat(tool.creates).isEqualTo(1);
        // The text goes in verbatim, at the head of the empty document.
        assertThat(tool.insertBody.path("requests").get(0).path("insertText").path("text").asText())
                .isEqualTo("Katılımcılar: Deniz, Ayşe.\nKarar: staging pazartesi.");
        assertThat(result.path("docId").asText()).isEqualTo("doc-1");
        assertThat(result.path("contentInserted").asBoolean()).isTrue();
        assertThat(result.path("url").asText()).contains("doc-1");
    }

    /**
     * The seam. Create succeeded, insert failed: failing the step here would hand it to the
     * retry loop, and every retry re-runs the create — three identical empty documents was
     * the measured worst case of that shape. The honest report is a success that says the
     * document exists and is empty, with the link the text has to be pasted into.
     */
    @Test
    void a_failed_insert_reports_an_empty_document_instead_of_retrying_into_twins() throws Exception {
        Recording tool = new Recording();
        tool.insertFailure = HttpJson.failure(500, "docs.googleapis.com",
                "{\"error\":{\"status\":\"INTERNAL\"}}");

        JsonNode result = tool.call(doc("Not", "Metin"), google(GoogleOAuth.SCOPES));

        assertThat(tool.creates).isEqualTo(1);
        assertThat(result.path("contentInserted").asBoolean(true)).isFalse();
        assertThat(result.path("docId").asText()).isEqualTo("doc-1");
        assertThat(result.path("note").asText())
                .contains("boş")
                .contains("https://docs.google.com/document/d/doc-1/edit");
    }

    @Test
    void a_title_with_a_newline_in_it_becomes_one_line() throws Exception {
        Recording tool = new Recording();

        tool.call(doc("Toplantı\nnotu", "Metin"), google(GoogleOAuth.SCOPES));

        assertThat(tool.createBody.path("title").asText()).isEqualTo("Toplantı notu");
    }

    // ---- what is refused before anything leaves ------------------------------

    @Test
    void a_document_with_no_title_or_no_body_never_reaches_google() {
        DocsCreateDocumentTool tool = new DocsCreateDocumentTool("replay", FIXTURES, null);

        ObjectNode noTitle = Json.object();
        noTitle.put("content", "Metin");
        assertThat(SchemaValidator.validate(tool.schema(), noTitle).valid()).isFalse();

        ObjectNode noContent = Json.object();
        noContent.put("title", "Not");
        assertThat(SchemaValidator.validate(tool.schema(), noContent).valid()).isFalse();

        assertThat(tool.execute(noTitle, null).mode()).isEqualTo("rejected");
    }

    @Test
    void creating_a_document_asks_before_it_writes() {
        DocsCreateDocumentTool tool = new DocsCreateDocumentTool("replay", FIXTURES, null);
        assertThat(tool.risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(tool.risk().defaultMode().wire()).isEqualTo("ask");
    }

    // ---- the permission -------------------------------------------------------

    @Test
    void a_grant_without_the_documents_permission_is_refused_before_anything_leaves() {
        Recording tool = new Recording();

        assertThatThrownBy(() -> tool.call(doc("Not", "Metin"), google(READ_ONLY)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("Bağlantılar")
                .hasMessageContaining("yeniden bağlan")
                .hasMessageContaining("doküman");

        assertThat(tool.creates).isZero();
    }

    /** The same problem reached the other way: a grant we could not read, revoked by hand. */
    @Test
    void googles_own_scope_rejection_is_told_in_the_same_words_and_quotes_nothing() {
        Recording tool = new Recording();
        tool.createFailure = HttpJson.failure(403, "docs.googleapis.com",
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Request had "
                        + "insufficient authentication scopes\",\"token\":\"ya29.leakedtoken\"}}");

        assertThatThrownBy(() -> tool.call(doc("Not", "Metin"), google("")))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("yeniden bağlan")
                .hasMessageNotContaining("PERMISSION_DENIED")
                .hasMessageNotContaining("ya29.");
    }

    /** The other 403 is nearly always the Docs API switched off in the console. */
    @Test
    void a_403_that_is_not_about_scope_points_at_the_docs_api_switch() {
        Recording tool = new Recording();
        tool.createFailure = HttpJson.failure(403, "docs.googleapis.com",
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Google Docs API "
                        + "has not been used in project 42 before or it is disabled.\"}}");

        assertThatThrownBy(() -> tool.call(doc("Not", "Metin"), google(GoogleOAuth.SCOPES)))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("Docs API");
    }

    /** Old tokens keep every job they had; only the document path stops until reconsent. */
    @Test
    void a_grant_from_before_the_new_permission_still_does_everything_it_did() {
        Connection old = google(READ_ONLY);

        assertThat(GoogleOAuth.granted(old, GoogleOAuth.DOCUMENTS_SCOPE)).isFalse();
        assertThat(GoogleOAuth.granted(old, GoogleOAuth.SPREADSHEETS_SCOPE)).isTrue();
        assertThat(GoogleOAuth.granted(old, "https://www.googleapis.com/auth/gmail.readonly")).isTrue();
        // No recorded scope is unknown, not absent — those tokens predate the field.
        assertThat(GoogleOAuth.granted(google(""), GoogleOAuth.DOCUMENTS_SCOPE)).isTrue();

        // The new permission is asked for, and every old one is still asked for with it.
        assertThat(GoogleOAuth.SCOPES).contains(GoogleOAuth.DOCUMENTS_SCOPE)
                .contains(GoogleOAuth.SPREADSHEETS_SCOPE)
                .contains(GoogleOAuth.CALENDAR_EVENTS_SCOPE)
                .contains(GoogleOAuth.COMPOSE_SCOPE);
        // documents is the whole Docs grant; Drive is not asked for on top of it.
        assertThat(GoogleOAuth.SCOPES).doesNotContain("auth/drive");
    }

    // ---- plumbing ---------------------------------------------------------------

    /** A docs tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends DocsCreateDocumentTool {

        private final List<String> urls = new ArrayList<>();
        private JsonNode createBody;
        private JsonNode insertBody;
        private int creates;
        private RuntimeException createFailure;
        private RuntimeException insertFailure;

        Recording() {
            super("live", FIXTURES, null);
        }

        @Override
        protected Map<String, String> headers(Connection connection) {
            return Map.of("Authorization", "Bearer test-token");
        }

        @Override
        JsonNode create(String url, Map<String, String> headers, JsonNode body) {
            urls.add(url);
            this.createBody = body;
            this.creates++;
            if (createFailure != null) {
                throw createFailure;
            }
            ObjectNode created = Json.object();
            created.put("documentId", "doc-1");
            created.put("title", body.path("title").asText());
            return created;
        }

        @Override
        JsonNode insert(String url, Map<String, String> headers, JsonNode body) {
            urls.add(url);
            this.insertBody = body;
            if (insertFailure != null) {
                throw insertFailure;
            }
            return Json.object();
        }
    }

    private static ObjectNode doc(String title, String content) {
        ObjectNode params = Json.object();
        params.put("title", title);
        params.put("content", content);
        return params;
    }

    private static Connection google(String scope) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("accessToken", "ya29.test-access-token");
        config.put("refreshToken", "1//test-refresh-token");
        config.put("scope", scope);
        return Connection.of(GoogleOAuth.PROVIDER, config, Instant.parse("2026-08-01T09:00:00Z"));
    }
}
