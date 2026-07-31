package com.relay.application.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.CalendarUpcomingTool;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GitHubTool;
import com.relay.infrastructure.tools.GmailTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The question → source seam. The model picks the tool and writes the query; this test
 * holds the gate that decides whether either may run.
 *
 * <p>It exists because both halves have gone wrong in production. The query half:
 * everything {@link SourceRouter#sanitize} rejects has come back from a model at least
 * once. The routing half: a mailbox-only endpoint answered "KAN-4 ne durumda" by searching
 * Gmail for the words in the question and reporting, truthfully and uselessly, that it
 * found nothing.
 */
class SourceRouterTest {

    private static final FixtureStore FIXTURES = new FixtureStore();
    private static final String CARGO = "Kargolarım gelmiş mi?";

    private static ToolRegistry registry() {
        return new ToolRegistryImpl(List.<Tool>of(
                new GmailTool.Search("replay", FIXTURES, null),
                new GmailTool.GetMessage("replay", FIXTURES, null),
                new JiraTool.SearchIssues("replay", FIXTURES),
                new JiraTool.ListMyIssues("replay", FIXTURES),
                new JiraTool.GetIssue("replay", FIXTURES),
                new JiraTool.AddComment("replay", FIXTURES),
                new GitHubTool.ListMyPullRequests("replay", FIXTURES),
                new CalendarUpcomingTool("replay", FIXTURES, null, "Europe/Istanbul")));
    }

    private static SourceRouter router(com.relay.application.port.LlmClient llm) {
        return new SourceRouter(llm, registry());
    }

    private static SourceRouter offline() {
        return router(new StubLlmClient(null));
    }

    // ---- routing ----------------------------------------------------------

    @Test
    void theModelPicksTheToolAndItsQueryIsUsedAsWritten() {
        TestDoubles.StaticLlmClient llm = new TestDoubles.StaticLlmClient("""
                {"lookups":[{"tool":"jira.searchIssues",
                             "query":"project = KAN AND status != Done ORDER BY updated DESC",
                             "explanation":"KAN projesindeki açık kayıtlara baktım."}]}""");

        SourceRouter.Plan plan = router(llm).route("KAN projesinde ne kaldı?");

        assertThat(plan.source()).isEqualTo(SourceRouter.SOURCE_LLM);
        assertThat(plan.lookups()).singleElement().satisfies(lookup -> {
            assertThat(lookup.tool()).isEqualTo("jira.searchIssues");
            assertThat(lookup.query()).contains("project = KAN");
            assertThat(lookup.explanation()).contains("KAN");
        });
        assertThat(plan.tokens()).isPositive();
        // The question itself reaches the model, together with the tools it may choose from.
        assertThat(llm.requests.get(0).user()).contains("KAN projesinde ne kaldı?")
                .contains("jira.searchIssues").contains("gmail.search");
        assertThat(llm.requests.get(0).schema()).isNotNull();
    }

    /**
     * Models invent plausible tool names — {@code gmail.searchMessages}, {@code jira.query}.
     * {@code InsightService} drops an invented tool out of a suggestion; a question must not
     * be more trusting than a suggestion is.
     */
    @Test
    void anInventedToolNameNeverBecomesALookup() {
        SourceRouter.Plan plan = router(new TestDoubles.StaticLlmClient("""
                {"lookups":[{"tool":"jira.magicSearch","query":"KAN-4","explanation":"…"},
                            {"tool":"jira.getIssue","query":"KAN-4","explanation":"KAN-4'e baktım."}]}"""))
                .route("KAN-4 ne durumda?");

        assertThat(plan.lookups()).singleElement()
                .satisfies(lookup -> assertThat(lookup.tool()).isEqualTo("jira.getIssue"));
    }

    /**
     * A question has no approval gate — there is nothing to approve — so nothing reachable
     * from here may write. A WRITE tool named by the model is dropped, not asked about.
     */
    @Test
    void aWriteToolIsDroppedEvenWhenTheModelAsksForIt() {
        SourceRouter.Plan plan = router(new TestDoubles.StaticLlmClient("""
                {"lookups":[{"tool":"jira.addComment","query":"KAN-4","explanation":"…"}]}"""))
                .route("KAN-4'e yorum yaz ve durumunu söyle");

        // Nothing that writes survived, so the deterministic rules answered instead.
        assertThat(plan.source()).isEqualTo(SourceRouter.SOURCE_HEURISTIC);
        assertThat(plan.lookups()).noneMatch(lookup -> lookup.tool().equals("jira.addComment"));
    }

    @Test
    void anIssueKeyRoutesToJiraWithoutAnyModel() {
        SourceRouter.Plan plan = offline().route("KAN-4 ne durumda?");

        assertThat(plan.source()).isEqualTo(SourceRouter.SOURCE_HEURISTIC);
        assertThat(plan.lookups()).singleElement().satisfies(lookup -> {
            assertThat(lookup.tool()).isEqualTo("jira.getIssue");
            assertThat(lookup.query()).isEqualTo("KAN-4");
        });
    }

    @Test
    void aPullRequestQuestionRoutesToGitHubAndACalendarQuestionToTheCalendar() {
        assertThat(offline().route("bu hafta hangi PR'larım bekliyor").lookups())
                .singleElement()
                .satisfies(lookup -> assertThat(lookup.tool()).isEqualTo("github.listMyPullRequests"));

        assertThat(offline().route("yarın toplantım var mı?").lookups())
                .singleElement()
                .satisfies(lookup -> assertThat(lookup.tool()).isEqualTo("calendar.listUpcoming"));
    }

    /** The behaviour this endpoint shipped with: an ordinary question is a mailbox question. */
    @Test
    void aQuestionThatNamesNoSystemStillGoesToTheMailbox() {
        SourceRouter.Plan plan = offline().route(CARGO);

        assertThat(plan.lookups()).singleElement().satisfies(lookup -> {
            assertThat(lookup.tool()).isEqualTo("gmail.search");
            assertThat(lookup.query())
                    .contains("trendyol").contains("aras").contains("yurtici")
                    .contains("kargo").contains("newer_than:");
        });
        assertThat(plan.tokens()).isZero();
    }

    /** A tool that takes no query is asked with none — and is not dropped for having none. */
    @Test
    void aToolWithoutARequiredQueryIsAskedWithEmptyParameters() {
        Tool pulls = new GitHubTool.ListMyPullRequests("replay", FIXTURES);
        assertThat(SourceRouter.queryField(pulls)).isNull();

        SourceRouter.Lookup lookup = offline().route("github'da bekleyen PR var mı").lookups().get(0);
        assertThat(lookup.query()).isEmpty();
        assertThat(SourceRouter.params(pulls, lookup, 10).path("maxResults").asInt()).isEqualTo(10);
    }

    /** The parameter a routed query lands in comes from the tool's own schema, not from a table. */
    @Test
    void theRoutedQueryLandsInTheParameterTheToolDeclares() {
        assertThat(SourceRouter.queryField(new GmailTool.Search("replay", FIXTURES, null)))
                .isEqualTo("query");
        assertThat(SourceRouter.queryField(new JiraTool.SearchIssues("replay", FIXTURES)))
                .isEqualTo("jql");
        assertThat(SourceRouter.queryField(new JiraTool.GetIssue("replay", FIXTURES)))
                .isEqualTo("issueKey");

        SourceRouter.Lookup lookup = new SourceRouter.Lookup("jira.searchIssues", "project = KAN", "…");
        assertThat(SourceRouter.params(new JiraTool.SearchIssues("replay", FIXTURES), lookup, 10)
                .path("jql").asText()).isEqualTo("project = KAN");
    }

    @Test
    void withNoReadToolRegisteredThereIsNothingToAskAndNoCallIsMade() {
        TestDoubles.StaticLlmClient llm = new TestDoubles.StaticLlmClient("{}");
        SourceRouter.Plan plan = new SourceRouter(llm, new ToolRegistryImpl(List.of())).route(CARGO);

        assertThat(plan.lookups()).isEmpty();
        assertThat(llm.requests).isEmpty();
    }

    // ---- the query gate ---------------------------------------------------

    @Test
    void proseAndPackagingFromTheModelAreRejectedInFavourOfTheRules() {
        // A sentence about the search instead of the search.
        assertThat(SourceRouter.sanitize(
                "Kullanıcının kargo maillerini son otuz gün içinde arayıp sonuçları listeleyeceğim"))
                .isNull();
        // Fenced blocks, unbalanced parentheses and stray quotes never reach a provider.
        assertThat(SourceRouter.sanitize("```\nfrom:trendyol\n```")).isNull();
        assertThat(SourceRouter.sanitize("from:(trendyol OR aras newer_than:30d")).isNull();
        assertThat(SourceRouter.sanitize("subject:\"kargo newer_than:30d")).isNull();
        assertThat(SourceRouter.sanitize("x".repeat(500))).isNull();
        assertThat(SourceRouter.sanitize("")).isNull();

        // Packaging that is safe to strip, rather than reject.
        assertThat(SourceRouter.sanitize("q=from:trendyol newer_than:7d"))
                .isEqualTo("from:trendyol newer_than:7d");
        assertThat(SourceRouter.sanitize("\"from:trendyol newer_than:7d\""))
                .isEqualTo("from:trendyol newer_than:7d");
        assertThat(SourceRouter.sanitize("from:trendyol\n  newer_than:7d"))
                .isEqualTo("from:trendyol newer_than:7d");
        // A bare keyword search is a legitimate query.
        assertThat(SourceRouter.sanitize("kargo teslimat")).isEqualTo("kargo teslimat");
        // JQL has no colons; a long ORDER BY clause is a query, not prose.
        assertThat(SourceRouter.sanitize("project = KAN AND status != Done ORDER BY updated DESC"))
                .isEqualTo("project = KAN AND status != Done ORDER BY updated DESC");
    }

    @Test
    void aRejectedModelQueryFallsBackToTheRulesWithoutFailingTheRequest() {
        SourceRouter.Plan plan = router(new TestDoubles.StaticLlmClient(
                """
                {"lookups":[{"tool":"gmail.search","query":"Tabii, kullanıcının kargo maillerine \
                son otuz gün içinde bakayım ve sonuçları listeleyeyim","explanation":"…"}]}""")).route(CARGO);

        assertThat(plan.lookups().get(0).tool()).isEqualTo("gmail.search");
        assertThat(plan.lookups().get(0).query()).contains("kargo").contains("newer_than:");
        // The tokens the call cost are still reported.
        assertThat(plan.tokens()).isPositive();
    }

    /** {@code "KARGO"} and {@code "İADE"} must fold the same way an ASCII keyword list expects. */
    @Test
    void turkishDottedAndDotlessCapitalsStillMatchTheRules() {
        SourceRouter router = offline();

        assertThat(router.route("KARGOLARIM GELMİŞ Mİ").lookups().get(0).query()).contains("kargo");
        assertThat(router.route("SİPARİŞİM NEREDE").lookups().get(0).query()).contains("kargo");
        assertThat(router.route("Faturalarımı göster").lookups().get(0).query()).contains("fatura");
        assertThat(router.route("FATURA GELDİ Mİ").lookups().get(0).query()).contains("fatura");
    }

    @Test
    void anAddressInTheQuestionBecomesAFromFilter() {
        assertThat(offline().route("ayse@alterteam.dev adresinden bir şey geldi mi?")
                .lookups().get(0).query()).startsWith("from:ayse@alterteam.dev");
    }

    /**
     * "Şundan mail gelmiş mi" is the question this endpoint exists for. Live, the fallback
     * searched for the string "Atlassiandan" and reported nothing found while an Atlassian
     * mail was sitting in the inbox.
     */
    @Test
    void theTurkishAblativeSuffixNamesTheSenderRatherThanTheSearchTerm() {
        SourceRouter router = offline();

        assertThat(router.route("Atlassian'dan mail gelmiş mi?").lookups().get(0).query())
                .isEqualTo("from:Atlassian newer_than:90d");
        assertThat(router.route("Ayşe'den bir şey var mı?").lookups().get(0).query())
                .startsWith("from:Ayşe");
        assertThat(router.route("Migros'tan kampanya maili geldi mi?").lookups().get(0).query())
                .startsWith("from:Migros");
        // A sender the question names beats the topic rules — it is the narrower search.
        assertThat(router.route("Trendyol'dan kargo maili geldi mi?").lookups().get(0).query())
                .startsWith("from:Trendyol");
    }

    @Test
    void anUnknownTopicIsSearchedWithTheQuestionsOwnWords() {
        String query = offline().route("Vergi levhası ile ilgili mail gelmiş mi?")
                .lookups().get(0).query();

        assertThat(query).contains("Vergi").contains("levhası").contains("newer_than:");
        // Scaffolding words are not searched for: live, "ilgili" went into the query and
        // no mail about a tax certificate contains it.
        assertThat(query).doesNotContain("gelmiş").doesNotContain("ilgili");
    }

    @Test
    void anEmptyQuestionIsRejectedBeforeAnyCall() {
        TestDoubles.StaticLlmClient llm = new TestDoubles.StaticLlmClient("{}");

        assertThatThrownBy(() -> router(llm).route("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(llm.requests).isEmpty();
    }
}
