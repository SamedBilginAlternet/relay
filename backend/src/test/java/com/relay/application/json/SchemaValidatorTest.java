package com.relay.application.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.port.ToolResult;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    private final FixtureStore fixtures = new FixtureStore();

    @Test
    void acceptsParamsThatMatchTheToolSchema() {
        JsonNode schema = new JiraTool.SearchIssues("replay", fixtures).schema();
        JsonNode params = Json.parse("{\"jql\":\"project = RELAY\",\"maxResults\":5}");

        assertThat(SchemaValidator.validate(schema, params).valid()).isTrue();
    }

    @Test
    void rejectsMissingRequiredFields() {
        JsonNode schema = new SlackTool.PostMessage("replay", fixtures).schema();
        SchemaValidator.Result result = SchemaValidator.validate(schema, Json.parse("{\"channel\":\"#general\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("text is required");
    }

    @Test
    void rejectsBlankRequiredStrings() {
        JsonNode schema = new JiraTool.AddComment("replay", fixtures).schema();
        SchemaValidator.Result result = SchemaValidator.validate(schema,
                Json.parse("{\"issueKey\":\"RELAY-1\",\"body\":\"   \"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("body is required");
    }

    @Test
    void rejectsWrongTypes() {
        JsonNode schema = new SlackTool.PostMessage("replay", fixtures).schema();
        SchemaValidator.Result result = SchemaValidator.validate(schema,
                Json.parse("{\"channel\":{\"id\":\"C1\"},\"text\":\"merhaba\"}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("must be string");
    }

    @Test
    void enforcesNumericBounds() {
        JsonNode schema = new JiraTool.SearchIssues("replay", fixtures).schema();
        SchemaValidator.Result result = SchemaValidator.validate(schema,
                Json.parse("{\"jql\":\"project = RELAY\",\"maxResults\":500}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("must be <= 50");
    }

    @Test
    void theToolItselfRefusesInvalidParams() {
        SlackTool.PostMessage tool = new SlackTool.PostMessage("replay", fixtures);
        ToolResult result = tool.execute(Json.parse("{\"channel\":\"#general\"}"), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.mode()).isEqualTo("rejected");
        assertThat(result.error()).contains("invalid params");
    }

    @Test
    void replayModeAnswersFromFixturesAndEchoesTheParams() {
        SlackTool.PostMessage tool = new SlackTool.PostMessage("replay", fixtures);
        ToolResult result = tool.execute(
                Json.parse("{\"channel\":\"#sprint-room\",\"text\":\"3 blocker güncellendi\"}"), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.mode()).isEqualTo("replay");
        assertThat(result.data().path("channel").asText()).isEqualTo("#sprint-room");
        assertThat(result.data().path("message").path("text").asText()).isEqualTo("3 blocker güncellendi");
    }

    @Test
    void jiraSearchReplayReturnsRecordedIssues() {
        JiraTool.SearchIssues tool = new JiraTool.SearchIssues("replay", fixtures);
        ToolResult result = tool.execute(Json.parse("{\"jql\":\"sprint in openSprints()\"}"), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().path("issues")).hasSize(3);
        assertThat(result.data().path("issues").path(0).path("key").asText()).isEqualTo("RELAY-14");
    }

    @Test
    void liveModeWithoutAConnectionDegradesToReplayInsteadOfFailing() {
        JiraTool.SearchIssues tool = new JiraTool.SearchIssues("live", fixtures);
        ToolResult result = tool.execute(Json.parse("{\"jql\":\"project = RELAY\"}"), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.mode()).isEqualTo("replay (no connection)");
    }
}
