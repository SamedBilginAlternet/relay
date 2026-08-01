package com.relay.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.json.Json;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An issue nobody owns never reaches "üstümdeki işler" — Jira answers
 * {@code assignee = currentUser()} with nothing. So a record Relay opens on the user's
 * behalf and leaves unassigned is invisible to the very screen that asked for it.
 */
class JiraAssigneeTest {

    /** Exposes the protected resolver without reaching the network for the plain cases. */
    private static final class Probe extends JiraTool {
        private Probe() {
            super(ToolsMode.REPLAY, new FixtureStore());
        }

        String resolve(Connection connection, String assignee) throws Exception {
            return accountIdFor(connection, assignee);
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
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public JsonNode schema() {
            return Json.object();
        }

        @Override
        protected JsonNode call(JsonNode params, Connection connection) {
            return Json.object();
        }
    }

    private static Connection connection() {
        return Connection.of("jira", Map.of("baseUrl", "https://x.atlassian.net",
                "email", "a@b.c", "apiToken", "t"), Instant.parse("2026-08-01T09:00:00Z"));
    }

    @Test
    void asking_for_nobody_changes_nothing() throws Exception {
        assertThat(new Probe().resolve(connection(), "")).isNull();
        assertThat(new Probe().resolve(connection(), null)).isNull();
    }

    /** An accountId is passed through: the caller already knows who they mean. */
    @Test
    void an_account_id_is_used_as_is() throws Exception {
        assertThat(new Probe().resolve(connection(), "5b10a2844c20165700ede21g"))
                .isEqualTo("5b10a2844c20165700ede21g");
    }

    /** Both tools offer it, so a card can open a record and own it in one step. */
    @Test
    void both_write_tools_accept_an_assignee() {
        FixtureStore fixtures = new FixtureStore();
        assertThat(new JiraTool.CreateIssue("replay", fixtures).schema()
                .path("properties").has("assignee")).isTrue();
        assertThat(new JiraTool.UpdateIssue("replay", fixtures).schema()
                .path("properties").has("assignee")).isTrue();
    }
}
