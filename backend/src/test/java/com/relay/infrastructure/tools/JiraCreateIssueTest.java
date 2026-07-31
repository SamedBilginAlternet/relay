package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.domain.Connection;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Live, every "Jira ticket aç" card died on the same answer:
 * {@code {"errors":{"issuetype":"Geçerli bir konu türü belirtin"}}}. The board is Turkish,
 * its types are "Görev"/"Hata", and Relay was posting the literal string "Bug".
 *
 * <p>So the choice of type is pinned here — including the case where the project offers
 * nothing resembling the request and creating the ticket anyway is the better answer.
 */
class JiraCreateIssueTest {

    /** A Turkish team-managed project, as {@code createmeta} returns it. */
    private static final JsonNode TURKISH_PROJECT = Json.parse("""
            [{"id":"10001","name":"Görev","subtask":false},
             {"id":"10002","name":"Hata","subtask":false},
             {"id":"10003","name":"Epik","subtask":false},
             {"id":"10004","name":"Alt görev","subtask":true}]
            """);

    private static final JsonNode ENGLISH_PROJECT = Json.parse("""
            [{"id":"1","name":"Task","subtask":false},
             {"id":"2","name":"Bug","subtask":false},
             {"id":"3","name":"Story","subtask":false},
             {"id":"4","name":"Subtask","subtask":true}]
            """);

    private static String pick(JsonNode types, String wanted) {
        JsonNode chosen = JiraTool.matchIssueType(types, wanted);
        return chosen == null ? null : chosen.path("id").asText();
    }

    @Test
    void an_english_type_finds_the_turkish_one() {
        assertThat(pick(TURKISH_PROJECT, "Bug")).isEqualTo("10002");
        assertThat(pick(TURKISH_PROJECT, "Task")).isEqualTo("10001");
        assertThat(pick(TURKISH_PROJECT, "Epic")).isEqualTo("10003");
    }

    @Test
    void turkish_spelling_survives_the_dotless_i_and_the_space() {
        assertThat(pick(TURKISH_PROJECT, "hata")).isEqualTo("10002");
        assertThat(pick(TURKISH_PROJECT, "GÖREV")).isEqualTo("10001");
        assertThat(pick(TURKISH_PROJECT, "Alt Görev")).isEqualTo("10004");
    }

    /** A sub-task without a parent is a second, more confusing 400. */
    @Test
    void a_plain_request_never_lands_on_a_subtask() {
        JsonNode onlySubtasks = Json.parse("[{\"id\":\"9\",\"name\":\"Alt görev\",\"subtask\":true}]");
        assertThat(JiraTool.matchIssueType(onlySubtasks, "Task")).isNull();
        assertThat(JiraTool.defaultIssueType(onlySubtasks)).isNull();
    }

    @Test
    void an_exact_name_wins_over_a_synonym() {
        JsonNode mixed = Json.parse("""
                [{"id":"1","name":"Hata","subtask":false},
                 {"id":"2","name":"Bug","subtask":false}]
                """);
        assertThat(pick(mixed, "Bug")).isEqualTo("2");
        assertThat(pick(mixed, "Hata")).isEqualTo("1");
    }

    @Test
    void an_unknown_type_matches_nothing_and_falls_back_to_the_task_flavour() {
        assertThat(JiraTool.matchIssueType(TURKISH_PROJECT, "Incident")).isNull();
        assertThat(JiraTool.defaultIssueType(TURKISH_PROJECT).path("id").asText()).isEqualTo("10001");
        assertThat(JiraTool.defaultIssueType(ENGLISH_PROJECT).path("id").asText()).isEqualTo("1");
    }

    /** Nothing asked for is not a failure: the project's own task type is the answer. */
    @Test
    void an_empty_request_leaves_the_choice_to_the_project() {
        assertThat(JiraTool.matchIssueType(TURKISH_PROJECT, "")).isNull();
        assertThat(JiraTool.defaultIssueType(TURKISH_PROJECT).path("name").asText()).isEqualTo("Görev");
    }

    @Test
    void the_offered_types_are_listed_without_the_subtasks() {
        assertThat(JiraTool.typeNames(TURKISH_PROJECT)).isEqualTo("Görev, Hata, Epik");
        assertThat(JiraTool.typeNames(Json.parse("[]"))).isEqualTo("(hiçbiri)");
    }

    // ---- project key ------------------------------------------------------

    private static Connection jira(Map<String, String> config) {
        return Connection.of("jira", config, Instant.parse("2026-07-31T20:00:00Z"));
    }

    private static JsonNode params(String projectKey) {
        ObjectNode params = Json.object();
        if (projectKey != null) {
            params.put("projectKey", projectKey);
        }
        return params;
    }

    @Test
    void the_configured_project_fills_in_for_a_missing_one() {
        Connection connection = jira(Map.of("baseUrl", "https://x.atlassian.net",
                "email", "a@b.c", "apiToken", "t", "projectKey", "kan"));

        assertThat(JiraTool.projectKey(params(null), connection)).isEqualTo("KAN");
        assertThat(JiraTool.projectKey(params("  "), connection)).isEqualTo("KAN");
        // What the caller asked for still wins — the fallback is for silence, not for override.
        assertThat(JiraTool.projectKey(params("relay"), connection)).isEqualTo("RELAY");
        assertThat(JiraTool.projectKey(params(null), null)).isEmpty();
    }

    // ---- the one-shot retry -----------------------------------------------

    /** A team-managed project has no priority field; losing the ticket over it is absurd. */
    @Test
    void a_rejection_that_names_only_fields_relay_volunteered_is_retried_without_them() {
        String body = """
                {"errorMessages":[],"errors":{"priority":"Field 'priority' cannot be set."}}""";
        assertThat(JiraTool.droppable(400, body)).containsExactly("priority");
    }

    @Test
    void a_rejection_that_touches_anything_else_is_not_retried() {
        assertThat(JiraTool.droppable(400, """
                {"errorMessages":[],"errors":{"priority":"nope","summary":"required"}}""")).isEmpty();
        assertThat(JiraTool.droppable(400, """
                {"errorMessages":["Şu anda bu işlemi yapamazsınız."],"errors":{"priority":"nope"}}""")).isEmpty();
        assertThat(JiraTool.droppable(400, "{\"errors\":{}}")).isEmpty();
        assertThat(JiraTool.droppable(401, "{\"errors\":{\"priority\":\"nope\"}}")).isEmpty();
        assertThat(JiraTool.droppable(400, "not json at all")).isEmpty();
        assertThat(JiraTool.droppable(400, null)).isEmpty();
    }
}
