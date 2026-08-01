package com.relay.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An error message has to point at what is actually wrong.
 *
 * <p>{@code PUT /api/policies} with a single {@code {toolName, mode}} object answered
 * "İstek gövdesi okunamadı: geçerli bir JSON gövdesi gönderin." The JSON was valid; only
 * the shape was wrong. A reader who trusts that sentence goes hunting for a comma that
 * does not exist, and the one fact they needed — that the endpoint wants a list — was
 * nowhere in the response.
 */
class PolicyBodyShapeTest {

    private PolicyController controller() {
        ToolRegistry tools = new ToolRegistryImpl(
                List.of(new JiraTool.CreateIssue("replay", new FixtureStore())));
        return new PolicyController(
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools));
    }

    @Test
    void a_single_object_is_told_which_shape_the_endpoint_wants() {
        assertThatThrownBy(() -> controller()
                .update(Json.parse("{\"toolName\":\"jira.createIssue\",\"mode\":\"ask\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Politika listesi bekleniyor")
                // The shape it wants, spelled out — not a description of it.
                .hasMessageContaining("toolName");
    }

    @Test
    void a_list_of_updates_is_applied() {
        List<Map<String, Object>> after = controller()
                .update(Json.parse("[{\"toolName\":\"jira.createIssue\",\"mode\":\"forbidden\"}]"));

        assertThat(after).anySatisfy(policy -> {
            assertThat(policy.get("toolName")).isEqualTo("jira.createIssue");
            assertThat(policy.get("mode")).isEqualTo("forbidden");
            assertThat(policy.get("overridden")).isEqualTo(true);
        });
    }

    /** An entry missing half of itself is the same mistake, one level down. */
    @Test
    void an_entry_without_a_mode_is_refused_with_the_same_sentence() {
        assertThatThrownBy(() -> controller().update(Json.parse("[{\"toolName\":\"jira.createIssue\"}]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PolicyController.EXPECTED_SHAPE);
    }
}
