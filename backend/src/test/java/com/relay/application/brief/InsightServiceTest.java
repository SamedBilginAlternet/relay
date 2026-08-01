package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.ToolRegistry;
import com.relay.application.text.Filler;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GitHubTool;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The insight layer's contract: one batched call, Turkish copy, and no invented tools. */
class InsightServiceTest {

    private ToolRegistry tools;

    private static final BriefItem MAIL = new BriefItem("gmail:1", "gmail", "mail", "",
            "Ödeme servisi staging'de patlıyor", "Ayşe Yıldız", "2sa önce", "Ayşe Yıldız",
            "https://mail.example/1", "2026-07-31T05:41:00Z", BriefItem.WARN,
            Map.of("messageId", "1"));

    private static final BriefItem PR = new BriefItem("github-pr:acme/pay#12", "github", "pr",
            "acme/pay#12", "Retry politikası", "review bekliyor · ayse", "1sa önce", "ayse",
            "https://github.com/acme/pay/pull/12", "2026-07-31T06:00:00Z", BriefItem.WARN,
            Map.of("repo", "acme/pay", "number", 12));

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        tools = new ToolRegistryImpl(List.of(
                new JiraTool.CreateIssue("replay", fixtures),
                new JiraTool.AddComment("replay", fixtures),
                new JiraTool.ListMyIssues("replay", fixtures),
                new GitHubTool.AddComment("replay", fixtures),
                new GitHubTool.ListMyPullRequests("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));
    }

    @Test
    void suggestionsNamingAnUnknownToolAreDropped() {
        String answer = """
                {"insights":[
                  {"id":"gmail:1","kind":"bug_report","urgency":"high","summary":"Staging'de ödeme hatası var.",
                   "suggestedActions":[
                     {"tool":"jira.createIssue","label":"Jira ticket aç","params":{"projectKey":"KAN","summary":"502"}},
                     {"tool":"gmail.sendReply","label":"Yanıtla","params":{"to":"ayse@example.com"}},
                     {"tool":"pagerduty.page","label":"Nöbetçiyi ara","params":{}}
                   ]}
                ]}""";
        InsightService service = new InsightService(new TestDoubles.StaticLlmClient(answer), tools);

        InsightService.Result result = service.analyze(List.of(MAIL), "KAN");

        assertThat(result.insights()).hasSize(1);
        InsightService.Insight insight = result.insights().get(0);
        assertThat(insight.kind()).isEqualTo("bug_report");
        assertThat(insight.urgency()).isEqualTo("high");
        // gmail.sendReply and pagerduty.page are not registered — they never reach the UI.
        assertThat(insight.actions()).extracting(InsightService.Action::tool)
                .containsExactly("jira.createIssue");
    }

    @Test
    void unknownItemIdsFromTheModelAreIgnoredAndTheItemStillGetsACard() {
        String answer = """
                {"insights":[
                  {"id":"jira:MADE-UP","kind":"fyi","urgency":"low","summary":"Uydurma.","suggestedActions":[]}
                ]}""";
        InsightService service = new InsightService(new TestDoubles.StaticLlmClient(answer), tools);

        InsightService.Result result = service.analyze(List.of(MAIL), "KAN");

        assertThat(result.insights()).hasSize(1);
        assertThat(result.insights().get(0).itemId()).isEqualTo("gmail:1");
        assertThat(result.insights().get(0).summary()).doesNotContain("Uydurma");
    }

    @Test
    void everyItemIsBatchedIntoASingleModelCall() {
        TestDoubles.StaticLlmClient llm = new TestDoubles.StaticLlmClient("{\"insights\":[]}");
        new InsightService(llm, tools).analyze(List.of(MAIL, PR), "KAN");

        assertThat(llm.requests).hasSize(1);
        assertThat(llm.requests.get(0).user()).contains("gmail:1").contains("github-pr:acme/pay#12");
        assertThat(llm.requests.get(0).schema()).isNotNull();
    }

    @Test
    void unparseableAnswerFallsBackToTurkishHeuristics() {
        InsightService service = new InsightService(
                new TestDoubles.StaticLlmClient("sorry, I cannot do that"), tools);

        InsightService.Result result = service.analyze(List.of(MAIL, PR), "KAN");

        assertThat(result.source()).isEqualTo("heuristic");
        assertThat(result.insights()).hasSize(2);
        InsightService.Insight mail = result.insights().get(0);
        assertThat(mail.kind()).isEqualTo("bug_report");
        assertThat(mail.urgency()).isEqualTo("high");
        assertThat(mail.summary()).contains("hata bildirimi");
        assertThat(mail.actions()).isNotEmpty();
        assertThat(mail.actions()).allSatisfy(action ->
                assertThat(tools.find(action.tool())).isPresent());
    }

    @Test
    void heuristicActionsNeverNameAToolThatIsNotRegistered() {
        // A registry without jira.createIssue must not produce a jira.createIssue suggestion.
        ToolRegistry slim = new ToolRegistryImpl(List.of(
                new SlackTool.PostMessage("replay", new FixtureStore())));
        InsightService service = new InsightService(new StubLlmClient(slim), slim);

        InsightService.Result result = service.analyze(List.of(MAIL, PR), "KAN");

        assertThat(result.insights()).allSatisfy(insight ->
                assertThat(insight.actions()).allSatisfy(action ->
                        assertThat(slim.find(action.tool())).isPresent()));
    }

    /**
     * The suggestion that puts Relay in everyone's day rather than the engineering team's:
     * a mail waiting for an answer offers to write the answer. It has to arrive with the
     * thread it belongs to and with a body that carries something — an action whose text
     * looks like filler is refused at the write gate, so a suggestion that produces one
     * would only ever fail after the user pressed it.
     */
    @Test
    void a_mail_waiting_for_an_answer_offers_a_reply_draft_on_its_own_thread() {
        ToolRegistry withDraft = new ToolRegistryImpl(List.of(
                new GmailTool.CreateDraft("replay", new FixtureStore(), null)));
        BriefItem asking = new BriefItem("gmail:9", "gmail", "mail", "",
                "Sözleşme onayını rica ediyorum", "Ayşe Yıldız", "2sa önce", "Ayşe Yıldız",
                "https://mail.google.com/mail/u/0/#inbox/9", "2026-07-31T05:41:00Z", BriefItem.WARN,
                Map.of("messageId", "9", "threadId", "18f2c9a10b3d4e01",
                        "from", "Ayşe Yıldız <ayse@alterteam.dev>"));

        InsightService.Insight insight = new InsightService(
                new TestDoubles.StaticLlmClient("no json here"), withDraft)
                .analyze(List.of(asking), "KAN").insights().get(0);

        InsightService.Action draft = insight.actions().stream()
                .filter(action -> "gmail.createDraft".equals(action.tool()))
                .findFirst().orElseThrow();
        assertThat(draft.label()).isEqualTo("Taslak cevap yaz");
        assertThat(draft.params())
                .containsEntry("to", "Ayşe Yıldız <ayse@alterteam.dev>")
                .containsEntry("threadId", "18f2c9a10b3d4e01");
        assertThat(String.valueOf(draft.params().get("subject"))).startsWith("Re: ");
        assertThat(Filler.looksLikeFiller(String.valueOf(draft.params().get("body")))).isFalse();
    }

    @Test
    void emptyBriefCostsNoTokens() {
        TestDoubles.StaticLlmClient llm = new TestDoubles.StaticLlmClient("{\"insights\":[]}");
        InsightService.Result result = new InsightService(llm, tools).analyze(List.of(), "KAN");

        assertThat(result.insights()).isEmpty();
        assertThat(result.tokens()).isZero();
        assertThat(llm.requests).isEmpty();
    }

    /**
     * A newsletter that happens to contain the word "bugs" must not become a bug report:
     * live, one offered to open a Jira ticket for a DEV Community digest.
     */
    @Test
    void bulk_mail_never_becomes_work() {
        BriefItem newsletter = new BriefItem("gmail:1", "gmail", "mail", "",
                "Good eats and rockstar bugs for your weekend", "DEV Community", "1sa önce",
                "DEV Community", "https://mail.google.com", "2026-07-31T09:00:00Z",
                BriefItem.DEFAULT, Map.of("bulk", true, "from", "noreply@dev.to"));

        InsightService service = new InsightService(
                new TestDoubles.StaticLlmClient("""
                        {"insights":[{"id":"gmail:1","kind":"bug_report","urgency":"high",
                          "summary":"Hata bildirimi","actions":[{"tool":"jira.createIssue",
                          "label":"Jira ticket aç","params":{"projectKey":"KAN"}}]}]}
                        """),
                new ToolRegistryImpl(List.of()));

        InsightService.Insight insight = service.analyze(List.of(newsletter), "KAN")
                .insights().get(0);

        assertThat(insight.kind()).isEqualTo("fyi");
        assertThat(insight.urgency()).isEqualTo("low");
        assertThat(insight.actions()).isEmpty();
    }
}
