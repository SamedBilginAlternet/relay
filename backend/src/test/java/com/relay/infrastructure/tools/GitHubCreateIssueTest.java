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
 * Opening an issue in somebody's tracker is {@code jira.createIssue} wearing a different
 * provider, and the same three promises have to hold on this side.
 *
 * <p>ONE — the content is written or the step fails. A title and body are the work itself;
 * an issue titled after the button that created it would be worse than no issue, because
 * somebody has to triage it. Nothing here defaults them, and the schema refuses their
 * absence before a model round is spent on the gate.
 *
 * <p>TWO — what was approved is what is opened. The one network call is a seam a test
 * watches: the exact title, the exact body, labels only when the step actually chose some.
 *
 * <p>THREE — a refusal is a sentence about the fix. GitHub answers the same 403 for a
 * read-only token, an out-of-scope repository and an unapproved organisation, and 410 for a
 * repository whose Issues tab is simply switched off — four different screens to go and
 * click, none of them named by the raw status.
 */
class GitHubCreateIssueTest {

    private static final FixtureStore FIXTURES = new FixtureStore();

    private static Connection github() {
        return Connection.of("github", Map.of("token", "github_pat_test"),
                Instant.parse("2026-08-01T09:00:00Z"));
    }

    // ---- what leaves the machine ------------------------------------------

    @Test
    void the_issue_that_was_approved_is_the_issue_that_is_opened() throws Exception {
        Recording tool = new Recording();
        ObjectNode params = issue("Ödeme servisi staging'de 502 dönüyor",
                "Ayşe'nin 'staging patlıyor' mailinden açıldı; gateway loglarında 502.");
        params.putArray("labels").add("bug").add("staging");

        JsonNode result = tool.call(params, github());

        assertThat(tool.url).isEqualTo("https://api.github.com/repos/acme/payments/issues");
        assertThat(tool.calls).isEqualTo(1);
        assertThat(tool.body.path("title").asText()).contains("502");
        assertThat(tool.body.path("body").asText()).contains("Ayşe");
        assertThat(tool.body.path("labels")).hasSize(2);

        assertThat(result.path("repo").asText()).isEqualTo("acme/payments");
        assertThat(result.path("number").asInt()).isEqualTo(47);
        assertThat(result.path("url").asText()).contains("/issues/47");
        assertThat(result.path("created").asBoolean(false)).isTrue();
        // GitHub's answer carries node ids and a dozen of its own REST urls; none of it
        // is the trail's business.
        assertThat(result.toString()).doesNotContain("node_id").doesNotContain("api.github.com");
    }

    /** An empty labels array is a statement about labels, and this step is not making one. */
    @Test
    void labels_the_step_did_not_choose_are_not_sent_at_all() throws Exception {
        Recording tool = new Recording();

        tool.call(issue("Başlık", "Gövde"), github());

        assertThat(tool.body.has("labels")).isFalse();
    }

    // ---- nothing is invented ----------------------------------------------

    @Test
    void an_issue_with_no_title_or_no_body_never_reaches_github() {
        GitHubTool.CreateIssue tool = new GitHubTool.CreateIssue("replay", FIXTURES);

        ObjectNode noTitle = Json.object();
        noTitle.put("repo", "acme/payments");
        noTitle.put("body", "Gövde");
        assertThat(SchemaValidator.validate(tool.schema(), noTitle).valid()).isFalse();

        ObjectNode noBody = Json.object();
        noBody.put("repo", "acme/payments");
        noBody.put("title", "Başlık");
        assertThat(SchemaValidator.validate(tool.schema(), noBody).valid()).isFalse();

        // Blank is absent: "" satisfies nothing.
        ObjectNode blank = issue("", "Gövde");
        assertThat(SchemaValidator.validate(tool.schema(), blank).valid()).isFalse();

        assertThat(tool.execute(noTitle, null).mode()).isEqualTo("rejected");
    }

    /** WRITE, so the policy engine opens the approval gate without a rule being written. */
    @Test
    void opening_an_issue_asks_before_it_writes() {
        GitHubTool.CreateIssue tool = new GitHubTool.CreateIssue("replay", FIXTURES);
        assertThat(tool.risk()).isEqualTo(RiskLevel.WRITE);
        assertThat(tool.risk().defaultMode().wire()).isEqualTo("ask");
    }

    // ---- refusals a first run actually hits --------------------------------

    @Test
    void a_read_only_token_is_told_which_permission_to_widen() {
        Recording tool = new Recording();
        tool.failure = HttpJson.failure(403, "api.github.com",
                "{\"message\":\"Resource not accessible by personal access token\"}");

        assertThatThrownBy(() -> tool.call(issue("Başlık", "Gövde"), github()))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("Read and write")
                .hasMessageContaining("acme/payments");
    }

    /** Issues being switched off is a repo setting, not a permission — say which screen. */
    @Test
    void a_repo_with_issues_disabled_is_a_sentence_not_a_status_code() {
        Recording tool = new Recording();
        tool.failure = HttpJson.failure(410, "api.github.com",
                "{\"message\":\"Issues are disabled for this repo\"}");

        assertThatThrownBy(() -> tool.call(issue("Başlık", "Gövde"), github()))
                .isInstanceOf(HttpJson.ToolCallException.class)
                .hasMessageContaining("Issues kapalı")
                .hasMessageContaining("Settings");
    }

    // ---- replay -------------------------------------------------------------

    /** The demo opens the same issue with no account at all, echoing what it was asked. */
    @Test
    void the_recorded_answer_echoes_the_issue_it_was_asked_to_open() {
        ObjectNode params = issue("Ödeme servisi staging'de 502 dönüyor", "Mailden açıldı.");

        var result = new GitHubTool.CreateIssue("replay", FIXTURES).execute(params, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.mode()).isEqualTo("replay");
        assertThat(result.data().path("repo").asText()).isEqualTo("acme/payments");
        assertThat(result.data().path("title").asText()).contains("502");
        assertThat(result.data().path("created").asBoolean(false)).isTrue();
    }

    // ---- plumbing ---------------------------------------------------------

    private static ObjectNode issue(String title, String body) {
        ObjectNode params = Json.object();
        params.put("repo", "acme/payments");
        params.put("title", title);
        params.put("body", body);
        return params;
    }

    /** A GitHub tool that answers itself and remembers exactly what it was asked to do. */
    private static class Recording extends GitHubTool.CreateIssue {

        private String url;
        private JsonNode body;
        private int calls;
        private RuntimeException failure;

        Recording() {
            super("live", FIXTURES);
        }

        @Override
        JsonNode post(String url, Map<String, String> headers, JsonNode body) {
            this.url = url;
            this.body = body;
            this.calls++;
            if (failure != null) {
                throw failure;
            }
            ObjectNode response = Json.object();
            response.put("number", 47);
            response.put("title", body.path("title").asText());
            response.put("html_url", "https://github.com/acme/payments/issues/47");
            response.put("state", "open");
            response.put("node_id", "I_kwDOJj2Qxc5aBcDe");
            var labels = response.putArray("labels");
            for (JsonNode label : body.path("labels")) {
                labels.addObject().put("name", label.asText())
                        .put("url", "https://api.github.com/repos/acme/payments/labels/x");
            }
            return response;
        }
    }
}
