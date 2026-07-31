package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import org.junit.jupiter.api.Test;

/**
 * The model says "Done"; a Turkish board offers "Bitti". Live, this mismatch failed a run
 * that had otherwise done everything right, so the matching is pinned here — including the
 * cases where refusing is the correct answer.
 */
class JiraTransitionMatchTest {

    private static JsonNode transitions(String json) {
        return Json.parse(json);
    }

    private static final JsonNode TURKISH_BOARD = transitions("""
            [{"id":"11","name":"Yapılacak","to":{"name":"Yapılacak"}},
             {"id":"21","name":"Devam Ediyor","to":{"name":"Devam Ediyor"}},
             {"id":"31","name":"Bitti","to":{"name":"Bitti"}}]
            """);

    @Test
    void an_english_target_finds_the_turkish_column() {
        assertThat(JiraTool.matchTransition(TURKISH_BOARD, "Done")).isEqualTo("31");
        assertThat(JiraTool.matchTransition(TURKISH_BOARD, "In Progress")).isEqualTo("21");
        assertThat(JiraTool.matchTransition(TURKISH_BOARD, "To Do")).isEqualTo("11");
    }

    @Test
    void turkish_dotless_i_does_not_break_the_match() {
        assertThat(JiraTool.matchTransition(TURKISH_BOARD, "yapılacak")).isEqualTo("11");
        assertThat(JiraTool.matchTransition(TURKISH_BOARD, "BİTTİ")).isEqualTo("31");
    }

    @Test
    void an_exact_name_wins_over_a_synonym() {
        JsonNode board = transitions("""
                [{"id":"1","name":"Kapat","to":{"name":"Kapalı"}},
                 {"id":"2","name":"Done","to":{"name":"Done"}}]
                """);
        assertThat(JiraTool.matchTransition(board, "Done")).isEqualTo("2");
    }

    /** Nothing sensible to pick means refuse — a wrong column is worse than an error. */
    @Test
    void an_unknown_target_matches_nothing() {
        assertThat(JiraTool.matchTransition(TURKISH_BOARD, "Beklemede")).isNull();
        assertThat(JiraTool.matchTransition(transitions("[]"), "Done")).isNull();
    }
}
