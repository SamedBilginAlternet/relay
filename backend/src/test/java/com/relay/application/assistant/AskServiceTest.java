package com.relay.application.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.application.json.Json;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.Connection;
import com.relay.domain.RiskLevel;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.CalendarUpcomingTool;
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
 * "does it refuse to answer when it has nothing to answer from", now that it has more
 * than one place to have nothing in.
 */
class AskServiceTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String CARGO = "Kargolarım gelmiş mi?";

    private static final String MAIL_ROUTE = """
            {"lookups":[{"tool":"gmail.search",
                         "query":"(from:(trendyol OR aras) OR subject:(kargo OR teslimat)) newer_than:30d",
                         "explanation":"Son 30 günde kargo maillerini aradım."}]}""";

    private AskService serviceWith(List<Tool> tools, LlmClient llm) {
        return serviceWith(tools, llm, new TestDoubles.InMemoryConnectionRepository());
    }

    private AskService serviceWith(List<Tool> tools, LlmClient llm, ConnectionRepository connections) {
        ToolRegistry registry = new ToolRegistryImpl(tools);
        return new AskService(registry, connections, new SourceRouter(llm, registry), llm);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sources(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("sources");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> breakdown(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("sourceBreakdown");
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
            schema.putArray("required").add("query");
            schema.putObject("properties").putObject("query").put("type", "string");
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

    /**
     * The mailbox from the live report: an English receipt that a Turkish {@code subject:}
     * filter cannot match. Records every query it was asked.
     */
    private static class EnglishInbox extends EmptySearch {
        private final List<String> queries = new ArrayList<>();

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            String query = params.path("query").asText("");
            queries.add(query);
            var data = Json.object();
            var messages = data.putArray("messages");
            if (!query.toLowerCase(java.util.Locale.ROOT).contains("subject:")) {
                messages.addObject()
                        .put("id", "19fb98c8445a5122")
                        .put("subject", "Your receipt from Anthropic, PBC #2293-9991-0125")
                        .put("from", "Anthropic <receipts@anthropic.com>")
                        .put("date", "2026-07-28T09:12:00Z");
            }
            data.put("total", messages.size());
            return ToolResult.ok(data, 4, "live");
        }
    }

    /** A gmail.search whose provider is down — the message must never reach the user. */
    private static class FailingSearch extends EmptySearch {
        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            return ToolResult.error("provider exploded", 3, "live");
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
                LlmPurpose.ASK_ROUTE, MAIL_ROUTE,
                LlmPurpose.ASK_ANSWER, "Evet, kargon yola çıkmış, yarın kapında olur."));

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
        assertThat(llm.of(LlmPurpose.ASK_ANSWER)).isEmpty();
        assertThat(answer.get("answerSource")).isEqualTo("none");
    }

    private static final String INVOICE = "Anthropic'ten fatura geldi mi?";
    private static final String NARROW_ROUTE = """
            {"lookups":[{"tool":"gmail.search",
                         "query":"(from:anthropic) subject:(fatura OR ödeme OR makbuz) newer_than:30d",
                         "explanation":"Son 30 günde Anthropic faturalarını aradım."}]}""";

    /**
     * The defect that made the same question answer "there is nothing" and then answer
     * correctly: the router writes the subject filter in the language of the question, and
     * the mailbox is in English. Asked five times it has to find the receipt five times.
     */
    @Test
    void a_turkish_question_finds_an_english_subject_mail() {
        EnglishInbox inbox = new EnglishInbox();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, NARROW_ROUTE,
                LlmPurpose.ASK_ANSWER, "Anthropic'ten 28 Temmuz'da bir makbuz gelmiş [1]."));

        Map<String, Object> answer = serviceWith(List.of(inbox), llm).ask(INVOICE);

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(sources(answer)).hasSize(1);
        assertThat(sources(answer).get(0).get("subject")).asString().contains("Your receipt from Anthropic");
        // Two calls, one question: the narrow query, then the same query without its guess.
        assertThat(inbox.queries).hasSize(2);
        assertThat(inbox.queries.get(0)).contains("subject:");
        assertThat(inbox.queries.get(1)).isEqualTo("(from:anthropic) newer_than:30d");
        // The query on the wire is the one that produced the answer, and the first is kept.
        assertThat(answer.get("query")).isEqualTo("(from:anthropic) newer_than:30d");
        assertThat(breakdown(answer).get(0).get("alsoTried")).asString().contains("subject:");
    }

    /** Widening is one extra call, not a search that keeps loosening until something matches. */
    @Test
    void a_widened_query_that_finds_nothing_is_reported_with_both_queries() {
        EmptySearch inbox = new EmptySearch();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, NARROW_ROUTE,
                LlmPurpose.ASK_ANSWER, "cevap"));

        Map<String, Object> answer = serviceWith(List.of(inbox), llm).ask(INVOICE);

        assertThat(answer.get("status")).isEqualTo("empty");
        assertThat(answer.get("answer")).asString()
                .contains("subject:")
                .contains("daraltmayı kaldırıp da denedim")
                .contains("(from:anthropic) newer_than:30d");
    }

    /**
     * Dropping the subject filter out of {@code (from:(x) OR subject:(y))} would search for
     * less, not more, and a query with no sender left in it widens to somebody else's mail.
     */
    @Test
    void a_query_that_cannot_be_widened_safely_is_run_once() {
        EnglishInbox inbox = new EnglishInbox();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, MAIL_ROUTE,
                LlmPurpose.ASK_ANSWER, "cevap"));

        serviceWith(List.of(inbox), llm).ask(CARGO);

        assertThat(inbox.queries).hasSize(1);
    }

    @Test
    void theAnswerIsBuiltFromTheFoundMailsAndCitesOnlyThem() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, MAIL_ROUTE,
                LlmPurpose.ASK_ANSWER,
                "Aras Kargo gönderin bugün dağıtıma çıkmış [2], Trendyol siparişin yolda [1]. "
                        + "Ayrıca bir de banka ekstren var [9]."));

        Map<String, Object> answer = serviceWith(
                List.of(new GmailTool.Search("replay", FIXTURES, null)), llm).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(answer.get("answerSource")).isEqualTo("llm");
        assertThat(answer.get("question")).isEqualTo(CARGO);
        assertThat(answer.get("query")).asString().contains("trendyol");
        assertThat(answer.get("queryExplanation")).asString().isNotBlank();
        assertThat(answer.get("querySource")).isEqualTo("llm");

        // [9] points at a mail that was never sent to the model — the citation is dropped.
        assertThat(answer.get("answer")).asString().contains("[1]").contains("[2]").doesNotContain("[9]");

        assertThat(sources(answer)).isNotEmpty();
        assertThat(sources(answer)).allSatisfy(source ->
                assertThat(source.keySet()).containsExactly("id", "subject", "from", "at", "url", "provider"));
        assertThat(sources(answer).get(0).get("url")).asString().contains("mail.google.com");
        assertThat(sources(answer).get(0).get("provider")).isEqualTo("Gmail");
        assertThat(answer.get("resultCount")).isEqualTo(sources(answer).size());

        // The model only ever saw the mails that came back from the search.
        String prompt = llm.of(LlmPurpose.ASK_ANSWER).get(0).user();
        assertThat(prompt).contains("Aras Kargo").contains("[1]");
        assertThat((Long) answer.get("tokens")).isPositive();
    }

    @Test
    void nothingThatWritesIsEverTouched() {
        ForbiddenWrite write = new ForbiddenWrite();
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, """
                        {"lookups":[{"tool":"jira.createIssue","query":"kargo","explanation":"…"},
                                    {"tool":"gmail.search","query":"subject:kargo newer_than:30d",
                                     "explanation":"Kargo maillerini aradım."}]}""",
                LlmPurpose.ASK_ANSWER, "İki kargo maili var [1][2]."));

        serviceWith(List.of(new GmailTool.Search("replay", FIXTURES, null), write,
                new JiraTool.AddComment("replay", FIXTURES)), llm)
                .ask("Kargolarım gelmiş mi, gelmediyse Jira'da bir kayıt aç");

        assertThat(write.calls).isEmpty();
    }

    // ---- more than one source ---------------------------------------------

    /**
     * The reason this endpoint stopped being mailbox-only: "KAN-4 ne durumda" has an answer,
     * and it is not in anybody's inbox.
     */
    @Test
    void aTrackerQuestionIsAnsweredFromTheTrackerWithItsOwnLink() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, """
                        {"lookups":[{"tool":"jira.searchIssues","query":"key = RELAY-14",
                                     "explanation":"Jira'da RELAY-14 kaydını aradım."}]}""",
                LlmPurpose.ASK_ANSWER, "RELAY-14 şu an Blocked durumda [1]."));

        Map<String, Object> answer = serviceWith(
                List.of(new JiraTool.SearchIssues("replay", FIXTURES)), llm, jiraConnected())
                .ask("RELAY-14 ne durumda?");

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(answer.get("query")).isEqualTo("key = RELAY-14");
        assertThat(sources(answer).get(0).get("provider")).isEqualTo("Jira");
        assertThat(sources(answer).get(0).get("id")).isEqualTo("RELAY-14");
        assertThat(sources(answer).get(0).get("subject")).asString().contains("502");
        assertThat(sources(answer).get(0).get("url")).isEqualTo("https://acme.atlassian.net/browse/RELAY-14");
        // The status the board reports is what the model reads — not a guess from the summary.
        assertThat(llm.of(LlmPurpose.ASK_ANSWER).get(0).user()).contains("Blocked");
    }

    /** Two sources answer, and every record is numbered once across both of them. */
    @Test
    void resultsFromTwoSourcesAreNumberedTogetherAndBothAreNamed() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, """
                        {"lookups":[{"tool":"jira.searchIssues","query":"status != Done",
                                     "explanation":"Açık kayıtlara baktım."},
                                    {"tool":"gmail.search","query":"subject:502 newer_than:7d",
                                     "explanation":"502 maillerini aradım."}]}""",
                LlmPurpose.ASK_ANSWER, "Jira'da üç kayıt [1], mailde de bir uyarı var [4]."));

        Map<String, Object> answer = serviceWith(List.of(
                new JiraTool.SearchIssues("replay", FIXTURES),
                new GmailTool.Search("replay", FIXTURES, null)), llm).ask("502 hatası ne durumda?");

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(sources(answer)).extracting(source -> source.get("provider"))
                .containsSubsequence("Jira", "Gmail");
        assertThat(answer.get("resultCount")).isEqualTo(sources(answer).size());

        // Both lookups are reported, each with the query that ran and what it returned.
        assertThat(breakdown(answer)).hasSize(2);
        assertThat(breakdown(answer)).extracting(row -> row.get("tool"))
                .containsExactly("jira.searchIssues", "gmail.search");
        assertThat(breakdown(answer)).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("ok");
            assertThat((Integer) row.get("resultCount")).isPositive();
        });

        // One numbering for the whole answer: [4] is the first mail, not the first anything.
        String prompt = llm.of(LlmPurpose.ASK_ANSWER).get(0).user();
        assertThat(prompt).contains("[4] kaynak=Gmail");
        assertThat(answer.get("answer")).asString().contains("[1]").contains("[4]");
    }

    /**
     * A provider nobody connected is skipped rather than answered from demo fixtures — and
     * it is named, because a silently missing source looks exactly like an empty one.
     */
    @Test
    void anUnconnectedProviderIsSkippedButSaidOutLoudNextToTheAnswer() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, """
                        {"lookups":[{"tool":"gmail.search","query":"subject:toplantı newer_than:7d",
                                     "explanation":"Toplantı maillerini aradım."},
                                    {"tool":"calendar.listUpcoming","query":"",
                                     "explanation":"Yaklaşan takvim kayıtlarına baktım."}]}""",
                LlmPurpose.ASK_ANSWER, "Mailde bir davet var [1]."));

        // gmail replays, the calendar runs live with no Google connection.
        Map<String, Object> answer = serviceWith(List.of(
                new GmailTool.Search("replay", FIXTURES, null),
                new CalendarUpcomingTool("live", FIXTURES, null, "Europe/Istanbul")), llm)
                .ask("Yarın toplantım var mı?");

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(sources(answer)).allSatisfy(source ->
                assertThat(source.get("provider")).isEqualTo("Gmail"));
        // Named in the answer, not buried in a field the screen may not render.
        assertThat(answer.get("answer")).asString().contains("Takvim bağlı değil");
        assertThat(breakdown(answer)).anySatisfy(row -> {
            assertThat(row.get("tool")).isEqualTo("calendar.listUpcoming");
            assertThat(row.get("status")).isEqualTo("unavailable");
            assertThat(row.get("resultCount")).isEqualTo(0);
        });
        // The fixture events never became sources for a calendar this user has not connected.
        assertThat(llm.of(LlmPurpose.ASK_ANSWER).get(0).user()).doesNotContain("Sprint retro");
    }

    /**
     * Live, "jira'da bende ne var ve PR'larım ne durumda" listed one pull request and said
     * nothing at all about Jira, which had come back empty. Silence about a source that was
     * asked is where the reader puts their own assumption.
     */
    @Test
    void aSourceThatFoundNothingIsNamedNextToTheOneThatFoundSomething() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, """
                        {"lookups":[{"tool":"jira.searchIssues","query":"status != Done",
                                     "explanation":"Açık kayıtlara baktım."},
                                    {"tool":"gmail.search","query":"subject:502 newer_than:7d",
                                     "explanation":"502 maillerini aradım."}]}""",
                LlmPurpose.ASK_ANSWER, "Jira'da üç kayıt var [1]."));

        Map<String, Object> answer = serviceWith(List.of(
                new JiraTool.SearchIssues("replay", FIXTURES), new EmptySearch()), llm)
                .ask("502 hatası ne durumda?");

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(answer.get("answer")).asString()
                .contains("Jira'da üç kayıt var [1].")
                .contains("eşleşen bir şey yok").contains("Gmail");
    }

    // ---- partial success --------------------------------------------------

    @Test
    void aDisconnectedGmailSaysSoInsteadOfAnsweringFromFixtures() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, MAIL_ROUTE,
                LlmPurpose.ASK_ANSWER, "Kargon geldi."));

        // Live mode, no Google connection: the tool falls back to the fixture, and those
        // recorded mails are not this user's mailbox.
        Map<String, Object> answer = serviceWith(
                List.of(new GmailTool.Search("live", FIXTURES, null)), llm).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("unavailable");
        assertThat(sources(answer)).isEmpty();
        assertThat(answer.get("answer")).asString()
                .contains("Gmail bağlı değil").contains("trendyol").doesNotContain("Kargon geldi");
        assertThat(llm.of(LlmPurpose.ASK_ANSWER)).isEmpty();
    }

    @Test
    void aFailingProviderIsTranslatedAndItsBodyNeverReachesTheUser() {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(Map.of(
                LlmPurpose.ASK_ROUTE, MAIL_ROUTE));

        Map<String, Object> answer = serviceWith(List.of(new FailingSearch()), llm).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("error");
        assertThat(answer.get("answer")).asString().contains("Gmail").doesNotContain("exploded");
        assertThat(sources(answer)).isEmpty();
        // The query is still reported, so the user knows what was attempted.
        assertThat(answer.get("query")).asString().isNotBlank();
    }

    @Test
    void withoutASingleReadToolTheEndpointSaysSoRatherThanGuessing() {
        Map<String, Object> answer = serviceWith(List.of(),
                new TestDoubles.ScriptedLlmClient(Map.of(LlmPurpose.ASK_ROUTE, MAIL_ROUTE))).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("unavailable");
        assertThat(answer.get("answer")).asString().contains("kaynak kayıtlı değil");
        assertThat(sources(answer)).isEmpty();
        assertThat(breakdown(answer)).isEmpty();
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
                .contains("kayıt buldum")
                .contains("Siparişin kargoya verildi")
                .doesNotContain("Adımlar");
        assertThat(sources(answer)).isNotEmpty();
        assertThat(answer.get("tokens")).isEqualTo(0L);
    }

    /**
     * The client said it was healthy and then answered from the stub anyway — the keys ran
     * out between the check and the call. Live, that put the stub's
     * "Sonuç bulunamadı: SORU: … BULUNAN KAYITLAR: … Bu kayıtlara dayanarak soruyu yanıtla."
     * on screen as the answer: Relay's own prompt, read back to the user.
     */
    @Test
    void aStubAnswerFromAnUndeclaredFallbackIsNotPassedOffAsAnAnswer() {
        LlmClient sneaky = new LlmClient() {
            @Override
            public com.relay.application.port.LlmResponse complete(
                    com.relay.application.port.LlmRequest request) {
                if (LlmPurpose.ASK_ROUTE.equals(request.purpose())) {
                    return new com.relay.application.port.LlmResponse(MAIL_ROUTE, 10, 5, 0.0, "scripted", false);
                }
                return new com.relay.application.port.LlmResponse(
                        "Sonuç bulunamadı: SORU: " + CARGO + "\nBULUNAN KAYITLAR: [1] …\n"
                                + "Bu kayıtlara dayanarak soruyu yanıtla.",
                        10, 5, 0.0, "stub", true);
            }

            @Override
            public String name() {
                return "sneaky";
            }

            @Override
            public boolean degraded() {
                return false;
            }
        };

        Map<String, Object> answer = serviceWith(
                List.of(new GmailTool.Search("replay", FIXTURES, null)), sneaky).ask(CARGO);

        assertThat(answer.get("status")).isEqualTo("ok");
        assertThat(answer.get("answerSource")).isEqualTo("listing");
        assertThat(answer.get("answer")).asString()
                .contains("kayıt buldum")
                .doesNotContain("Bu kayıtlara dayanarak")
                .doesNotContain("Sonuç bulunamadı");
        assertThat(sources(answer)).isNotEmpty();
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

    private static TestDoubles.InMemoryConnectionRepository jiraConnected() {
        TestDoubles.InMemoryConnectionRepository connections =
                new TestDoubles.InMemoryConnectionRepository();
        connections.save(Connection.of("jira",
                Map.of("baseUrl", "https://acme.atlassian.net/", "email", "qa@relay.dev",
                        "apiToken", "token"),
                java.time.Instant.parse("2026-07-31T09:00:00Z")));
        return connections;
    }
}
