package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.Clock;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Why this file exists.
 *
 * <p>Live, 2026-08-01, run {@code f51067e7} (#174). The Günü kapat flow read ten mails —
 * about 2.9k characters of result — then opened KAN-31. The Slack step's PREVIOUS RESULTS
 * block was cut by one overall 2000-character preview, which ended in the middle of the
 * mails: the Jira result, carrying the one fact the step existed to announce
 * ({@code issueKey: KAN-31}), never reached the model. It answered honestly on what it
 * could see — "Jira kaydı anahtarı bulunamadı" — and skipped. Sheets followed. Two steps
 * a human expected to approve never asked, on a run that closed green.
 *
 * <p>The truncation now budgets per item, so an early step's bulk cannot push the latest
 * step's result off the prompt — and the latest result is precisely what a chain needs.
 */
class LatestResultReachesThePromptTest {

    /** Captures the prompt the parameter turn was given, answers with valid params. */
    private static class Capturing implements LlmClient {
        final AtomicReference<String> lastUser = new AtomicReference<>();

        @Override
        public LlmResponse complete(LlmRequest request) {
            lastUser.set(request.user());
            return new LlmResponse("{\"channel\":\"#eng\",\"text\":\"KAN-31 açıldı\"}",
                    200, 40, 0.000_02, "test:model", false);
        }

        @Override
        public String name() {
            return "capturing";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    private static class SlackLike implements Tool {
        @Override
        public String name() {
            return "slack.postMessage";
        }

        @Override
        public String description() {
            return "mesaj gönderir";
        }

        @Override
        public JsonNode schema() {
            var schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("text");
            schema.putObject("properties").putObject("text").put("type", "string");
            return schema;
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            return ToolResult.ok(Json.object(), 1, "replay");
        }
    }

    @Test
    void a_bulky_early_read_cannot_push_the_created_records_key_off_the_prompt() {
        Capturing llm = new Capturing();
        Clock clock = new TestDoubles.FixedClock();
        ToolAgent agent = new ToolAgent(new ToolRegistryImpl(List.of(new SlackLike())), llm,
                new TestDoubles.InMemoryConnectionRepository(),
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock), clock);

        Run run = Run.create("Kaydı aç ve kanala anahtarıyla bildir", clock.now(), 1.0);
        // Step 1: the bulky read — live it was ten mails, ~2.9k chars. Noise, but real noise:
        // the kind of prose a mailbox actually returns.
        Step read = Step.create(run.id(), 1, "Mailleri oku",
                AgentRole.toolAgent("gmail.search"), "gmail.search", Map.of());
        read.markRunning(clock.now());
        read.markDone(Map.of("messages",
                "Merhaba, ödeme ekranında hata alıyoruz. ".repeat(80)), clock.now());
        // Step 2: the created record — small, and the only thing step 3 is about.
        Step created = Step.create(run.id(), 2, "Kaydı aç",
                AgentRole.toolAgent("jira.createIssue"), "jira.createIssue", Map.of());
        created.markRunning(clock.now());
        created.markDone(Map.of("issueKey", "KAN-31",
                "url", "https://ornek.atlassian.net/browse/KAN-31"), clock.now());
        Step announce = Step.create(run.id(), 3, "Kanala bildir",
                AgentRole.toolAgent("slack.postMessage"), "slack.postMessage", Map.of());
        run.replaceSteps(List.of(read, created, announce));

        agent.refreshParams(run, announce);

        String prompt = llm.lastUser.get();
        assertThat(prompt).as("the parameter turn ran").isNotNull();
        // The claim from the incident: however big the read was, the created record's key
        // is in what the model was shown.
        assertThat(prompt).contains("KAN-31");
    }
}
