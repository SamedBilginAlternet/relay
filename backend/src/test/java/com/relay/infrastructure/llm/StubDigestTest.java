package com.relay.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.json.Json;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fallback writes the message body when Groq is rate limited, so what it puts there
 * lands in someone's Slack channel. Live it sent "Konular: Jira'da ilgili işleri bul ·
 * Slack kanallarını listele" — the plan read back to the team instead of its findings,
 * because the step wrapper carries a "title" of its own next to the provider payload.
 */
class StubDigestTest {

    private static final ToolRegistry TOOLS = new ToolRegistryImpl(List.of());

    /** Mirrors what {@code ToolAgent.previousResults} hands over. */
    private static final List<Map<String, Object>> PREVIOUS = List.of(
            Map.of("step", 1,
                    "title", "Jira'da ilgili işleri bul",
                    "tool", "jira.searchIssues",
                    "result", Map.of("data", Map.of("issues", List.of(
                            Map.of("key", "KAN-4", "fields",
                                    Map.of("summary", "Profil sayfası yeniden tasarımı")))))),
            Map.of("step", 2,
                    "title", "Slack kanallarını listele",
                    "tool", "slack.listChannels",
                    "result", Map.of("data", Map.of("channels", List.of(
                            Map.of("name", "all-samed"))))));

    private String message(Object previous) {
        var schema = Json.object();
        schema.put("type", "object");
        schema.putArray("required").add("text");
        schema.putObject("properties").putObject("text").put("type", "string");

        String content = new StubLlmClient(TOOLS).complete(LlmRequest.of(
                LlmPurpose.TOOL_PARAMS, "system", "user", schema,
                Map.of("goal", "Ekibe durumu bildir", "previous", previous))).content();
        return Json.parse(content).path("text").asText();
    }

    @Test
    void the_message_carries_findings_not_step_titles() {
        String text = message(PREVIOUS);

        assertThat(text).contains("KAN-4", "Profil sayfası yeniden tasarımı");
        assertThat(text)
                .as("the plan's own step names are not findings")
                .doesNotContain("Jira'da ilgili işleri bul")
                .doesNotContain("Slack kanallarını listele");
    }

    @Test
    void nothing_found_is_said_plainly() {
        String text = message(List.of());

        assertThat(text).startsWith("Sonuç bulunamadı:");
        assertThat(com.relay.application.text.Filler.looksLikeFiller(text))
                .as("and the write gate stops it, rather than posting it to a channel")
                .isTrue();
    }
}
