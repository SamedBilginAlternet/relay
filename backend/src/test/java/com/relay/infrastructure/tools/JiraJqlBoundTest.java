package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Jira Cloud answers HTTP 400 to any JQL without a restricting clause, and the planner is
 * free to emit exactly that. {@code bound()} is the guard, so it is pinned here: every
 * query leaves with a restriction, and an existing {@code ORDER BY} stays at the tail
 * where Jira expects it.
 */
class JiraJqlBoundTest {

    /** Small window into the protected helper. */
    private static String bound(String jql) {
        return Probe.expose(jql);
    }

    private static final class Probe extends JiraTool {
        private Probe() {
            super(ToolsMode.REPLAY, new FixtureStore());
        }

        static String expose(String jql) {
            return JiraTool.bound(jql);
        }

        @Override
        public String name() {
            return "jira.probe";
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public com.relay.domain.RiskLevel risk() {
            return com.relay.domain.RiskLevel.READ;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode schema() {
            return com.relay.application.json.Json.object();
        }

        @Override
        protected com.fasterxml.jackson.databind.JsonNode call(
                com.fasterxml.jackson.databind.JsonNode params, com.relay.domain.Connection connection) {
            return com.relay.application.json.Json.object();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "ORDER BY updated DESC",
            "order by updated desc",
            "labels = blocker",
            "status != Done ORDER BY priority DESC",
    })
    void every_query_leaves_bounded(String jql) {
        assertThat(bound(jql)).startsWith("project is not EMPTY");
    }

    @Test
    void a_bare_sort_keeps_its_tail_and_gains_a_restriction() {
        assertThat(bound("ORDER BY updated DESC"))
                .isEqualTo("project is not EMPTY ORDER BY updated DESC");
    }

    @Test
    void an_existing_filter_is_wrapped_not_replaced() {
        assertThat(bound("labels = blocker AND status != Done ORDER BY created ASC"))
                .isEqualTo("project is not EMPTY AND (labels = blocker AND status != Done) "
                        + "ORDER BY created ASC");
    }

    @Test
    void a_filter_without_sorting_needs_no_tail() {
        assertThat(bound("assignee = currentUser()"))
                .isEqualTo("project is not EMPTY AND (assignee = currentUser())");
    }

    /** An {@code order by} inside a subquery is not this query's tail — leave it alone. */
    @Test
    void a_subquery_sort_is_not_mistaken_for_the_tail() {
        assertThat(bound("issue in linkedIssues(\"KAN-1\") AND status in (Open, Blocked)"))
                .isEqualTo("project is not EMPTY AND (issue in linkedIssues(\"KAN-1\") "
                        + "AND status in (Open, Blocked))");
    }
}
