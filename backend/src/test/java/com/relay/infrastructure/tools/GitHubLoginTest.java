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
 * Live, the GitHub connection carried {@code login = samed.bilgin@alternet.com.tr}. Search
 * qualifiers take a username, so every query answered HTTP 422 and the whole KOD section of
 * the brief showed an error — because a form asked for something the user could not be
 * expected to distinguish.
 */
class GitHubLoginTest {

    /** Exposes the protected helper. */
    private static final class Probe extends GitHubTool {
        private Probe() {
            super(ToolsMode.REPLAY, new FixtureStore());
        }

        String login(Connection connection) {
            return me(connection);
        }

        @Override
        public String name() {
            return "github.probe";
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

    private static Connection with(Map<String, String> config) {
        return Connection.of("github", config, Instant.parse("2026-07-31T09:00:00Z"));
    }

    @Test
    void a_real_username_is_used_as_is() {
        assertThat(new Probe().login(with(Map.of("token", "t", "login", "SamedBilginAlternet"))))
                .isEqualTo("SamedBilginAlternet");
    }

    @Test
    void an_email_address_is_not_a_username() {
        // No network in tests, so resolution falls through — the point is that the address
        // never reaches a search qualifier.
        assertThat(new Probe().login(with(Map.of("token", "t", "login", "samed.bilgin@alternet.com.tr"))))
                .isNotEqualTo("samed.bilgin@alternet.com.tr");
    }

    @Test
    void hyphens_are_fine_but_dots_and_spaces_are_not() {
        assertThat(new Probe().login(with(Map.of("token", "t", "login", "acme-dev"))))
                .isEqualTo("acme-dev");
        assertThat(new Probe().login(with(Map.of("token", "t2", "login", "first.last"))))
                .isNotEqualTo("first.last");
        assertThat(new Probe().login(with(Map.of("token", "t3", "login", "iki kelime"))))
                .isNotEqualTo("iki kelime");
    }
}
