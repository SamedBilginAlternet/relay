package com.relay.application.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/ask}. The property under test is not "does it answer" — it is
 * "does it refuse to answer when it has nothing to answer from".
 */
class AskServiceTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String CARGO = "Kargolarım gelmiş mi?";

    private static final String GOOD_QUERY = """
            {"query":"(from:(trendyol OR aras) OR subject:(kargo OR teslimat)) newer_than:30d",
             "explanation":"Son 30 günde kargo maillerini aradım."}""";

    private AskService serviceWith(List<Tool> tools, LlmClient llm) {
        ToolRegistry registry = new ToolRegistryImpl(tools);
        return new AskService(registry, new TestDoubles.InMemoryConnectionRepository(),
                new MailQueryTranslator(llm), llm);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sources(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("sources");
    }

    /** A gmail.search that finds nothing — the case that used to invite invention. */
    private static class EmptySearch implements Tool {
        @Override
        public String name() {
            return "gmail.search";
        }

        @Override
        public String description() {
            return "finds nothing";
        }

        @Override
        public JsonNode schema() {
            var schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.READ;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            var data = Json.object();
            data.putArray("messages");
            data.put("total", 0);
            data.put("query", params.path("query").asText(""));
            return ToolResult.ok(data, 4, "live");
        }
    }

    /** Fails the test if the endpoint ever reaches for something that writes. */
    private static class ForbiddenWrite implements Tool {
        private final List<String> calls = new ArrayList<>();

        @Override
        public String name() {
            return "jira.createIssue";
        }

        @Override
        public String description() {
            return "must never be called from /api/ask";
        }

        @Override
        public JsonNode schema() {
            var schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls.add(Json.write(params));
            return ToolResult.ok(Json.object(), 1, "live");
        }
    }

    // ---- the rule that matters --------------------------------------------

    @Test
    void anEmptyResultSetIsReportedAsEmptyAndTheModelIsNeverAskedToAnswer() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.MAIL_QUERY, GOOD_QUERY,
                LlmPurpose.MAIL_ANSWER, "Evet, kargon yola çıkmış, yarın kapında olur."));

        Map<String, Object> answer = serviceWith(List.of(new EmptySearch()), llm).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("empty");
        assertThat(sources(answer)).isEmpty();
        assertThat(answer.get("resultCount")).isEqualTo(0);
        // No invented shipment, and the query that came up empty is named.
        assertThat(answer.get("answer")).asString()
                .contains("bulamadım")
                .contains("trendyol")
                .doesNotContain("yarın kapında");
        // The answer model was never even called.
        assertThat(llm.of(LlmPurpose.MAIL_ANSWER)).isEmpty();
        assertThat(answer.get("answerSource")).isEqualTo("none");
    }

    @Test
    void theAnswerIsBuiltFromTheFoundMailsAndCitesOnlyThem() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.MAIL_QUERY, GOOD_QUERY,
                LlmPurpose.MAIL_ANSWER,
                "Aras Kargo gönderin bugün dağıtıma çıkmış [2], Trendyol siparişin yolda [1]. "
                        + "Ayrıca bir de banka ekstren var [9]."));

        Map<String, Object> answer = serviceWith(
                List.of(new GmailTool.Search("replay", FIXTURES, null)), llm).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(answer.get("answerSource")).isEqualTo("llm");
        assertThat(answer.get("question")).isEqualTo(CARGO);
        assertThat(answer.get("query")).asString().contains("trendyol");
        assertThat(answer.get("queryExplanation")).asString().isNotBlank();

        // [9] points at a mail that was never sent to the model — the citation is dropped.
        assertThat(answer.get("answer")).asString().contains("[1]").contains("[2]").doesNotContain("[9]");

        assertThat(sources(answer)).isNotEmpty();
        assertThat(sources(answer)).allSatisfy(source ->
                assertThat(source.keySet()).containsExactly("id", "subject", "from", "at", "url"));
        assertThat(sources(answer).get(0).get("url")).asString().contains("mail.google.com");
        assertThat(answer.get("resultCount")).isEqualTo(sources(answer).size());

        // The model only ever saw the mails that came back from the search.
        String prompt = llm.of(LlmPurpose.MAIL_ANSWER).get(0).user();
        assertThat(prompt).contains("Aras Kargo").contains("[1]");
        assertThat((Long) answer.get("tokens")).isPositive();
    }

    @Test
    void nothingThatWritesIsEverTouched() {
        ForbiddenWrite write = new ForbiddenWrite();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.MAIL_QUERY, GOOD_QUERY,
                LlmPurpose.MAIL_ANSWER, "İki kargo maili var [1][2]."));

        serviceWith(List.of(new GmailTool.Search("replay", FIXTURES, null), write,
                new JiraTool.AddComment("replay", FIXTURES)), llm)
                .ask("Kargolarım gelmiş mi, gelmediyse Jira'da bir kayıt aç");

        assertThat(write.calls).isEmpty();
    }

    // ---- partial success --------------------------------------------------

    @Test
    void aDisconnectedGmailSaysSoInsteadOfAnsweringFromFixtures() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.MAIL_QUERY, GOOD_QUERY,
                LlmPurpose.MAIL_ANSWER, "Kargon geldi."));

        // Live mode, no Google connection: the tool falls back to the fixture, and those
        // recorded mails are not this user's mailbox.
        Map<String, Object> answer = serviceWith(
                List.of(new GmailTool.Search("live", FIXTURES, null)), llm).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("unavailable");
        assertThat(sources(answer)).isEmpty();
        assertThat(answer.get("answer")).asString()
                .contains("Gmail bağlı değil").contains("trendyol").doesNotContain("Kargon geldi");
        assertThat(llm.of(LlmPurpose.MAIL_ANSWER)).isEmpty();
    }

    @Test
    void aFailingProviderIsTranslatedAndItsBodyNeverReachesTheUser() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.MAIL_QUERY, GOOD_QUERY));

        Map<String, Object> answer = serviceWith(
                List.of(new TestDoubles.FailingTool("gmail.search")), llm).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("error");
        assertThat(answer.get("answer")).asString().contains("Gmail").doesNotContain("exploded");
        assertThat(sources(answer)).isEmpty();
        // The query is still reported, so the user knows what was attempted.
        assertThat(answer.get("query")).asString().isNotBlank();
    }

    @Test
    void withoutTheSearchToolTheEndpointSaysSoRatherThanGuessing() {
        Map<String, Object> answer = serviceWith(List.of(),
                new TestDoubles.ScriptedLlmClient(Map.of(LlmPurpose.MAIL_QUERY, GOOD_QUERY))).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("unavailable");
        assertThat(answer.get("answer")).asString().contains("Gmail");
        assertThat(sources(answer)).isEmpty();
    }

    /**
     * On the stub, the answer is a list of what was found — never the stub's own prose,
     * which is filler with the shape of an insight.
     */
    @Test
    void aDegradedModelListsTheHitsInsteadOfNarratingThem() {
        Map<String, Object> answer = serviceWith(
                List.of(new GmailTool.Search("replay", FIXTURES, null)),
                new StubLlmClient(null)).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(answer.get("answerSource")).isEqualTo("listing");
        assertThat(answer.get("answer")).asString()
                .contains("mail buldum")
                .contains("Siparişin kargoya verildi")
                .doesNotContain("Adımlar");
        assertThat(sources(answer)).isNotEmpty();
        assertThat(answer.get("tokens")).isEqualTo(0L);
    }

    // ---- input ------------------------------------------------------------

    @Test
    void blankAndOversizedQuestionsAreRejected() {
        AskService service = serviceWith(List.of(new GmailTool.Search("replay", FIXTURES, null)),
                new StubLlmClient(null));

        assertThatThrownBy(() -> service.ask("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ask(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ask("a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uzun");
    }

    @Test
    void aFabricatedCitationIsStrippedWhileTheSentenceSurvives() {
        assertThat(AskService.sanitizeAnswer("Kargon yolda [1] ama faturan yok [4].", 2))
                .isEqualTo("Kargon yolda [1] ama faturan yok .");
        assertThat(AskService.sanitizeAnswer("```\nKargon yolda [1].\n```", 1))
                .isEqualTo("Kargon yolda [1].");
        assertThat(AskService.sanitizeAnswer("   ", 3)).isNull();
        assertThat(AskService.sanitizeAnswer(null, 3)).isNull();
    }
}
