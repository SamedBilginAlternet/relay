package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.relay.support.TestDoubles;

/**
 * Asked to "close this", the planner once produced {@code jira.updateIssue RELAY-1} — a key
 * it invented. Jira happened to answer 404; on a tenant where that key exists it would have
 * closed someone else's issue. A writing step may only touch a record that was actually
 * named somewhere.
 */
class GroundedWriteTest {

    private ToolAgent toolAgent;
    private ToolRegistry tools;
    private LlmClient llm;
    private TestDoubles.FixedClock clock;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        tools = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures),
                new JiraTool.CreateIssue("replay", fixtures)));
        clock = new TestDoubles.FixedClock();
        llm = new StubLlmClient(tools);
        toolAgent = new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(),
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock), clock);
    }

    private Run runWith(String goal, Step step) {
        Run run = Run.create(goal, java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.replaceSteps(List.of(step));
        return run;
    }

    private static Step updateStep(String issueKey) {
        return Step.create(java.util.UUID.randomUUID(), 1, "Kaydı kapat", AgentRole.COORDINATOR,
                "jira.updateIssue", Map.of("issueKey", issueKey, "status", "Done"));
    }

    @Test
    void an_invented_issue_key_never_reaches_the_provider() {
        Run run = runWith("Ödeme servisi staging'de patlıyor, bunu kapat", updateStep("RELAY-1"));

        StepOutcome outcome = toolAgent.execute(run, run.steps().get(0));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).contains("uydurulmuş tanımlayıcı", "issueKey=RELAY-1");
    }

    @Test
    void a_key_the_user_typed_is_allowed_through() {
        Run run = runWith("KAN-42 kaydını kapat", updateStep("KAN-42"));

        StepOutcome outcome = toolAgent.execute(run, run.steps().get(0));

        assertThat(outcome.ok()).as("the goal names the record, so the write may proceed")
                .isTrue();
    }

    @Test
    void a_key_an_earlier_step_found_is_allowed_through() {
        Run run = Run.create("Blocker kayıtlarını bul ve kapat", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step search = Step.create(run.id(), 1, "Blocker'ları bul", AgentRole.COORDINATOR,
                "jira.searchIssues", Map.of("jql", "labels = blocker"));
        Step update = Step.create(run.id(), 2, "Kaydı kapat", AgentRole.COORDINATOR,
                "jira.updateIssue", Map.of("issueKey", "KAN-42", "status", "Done"));
        run.replaceSteps(List.of(search, update));
        search.result(Map.of("data", Map.of("issues", List.of(Map.of("key", "KAN-42")))));

        StepOutcome outcome = toolAgent.execute(run, update);

        assertThat(outcome.ok()).isTrue();
    }

    /** Reading is exploration — a search may look for anything without prior grounding. */
    @Test
    void reading_steps_are_not_grounded() {
        Run run = Run.create("Bugün ne var", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step search = Step.create(run.id(), 1, "Ara", AgentRole.COORDINATOR,
                "jira.searchIssues", Map.of("jql", "project = ANYTHING"));
        run.replaceSteps(List.of(search));

        assertThat(toolAgent.execute(run, search).ok()).isTrue();
    }

    /**
     * The case the gate exists for, and the one it used to miss.
     *
     * <p>{@code contains} asked whether the string appeared anywhere, so a search that
     * returned KAN-10 vouched for KAN-1. That is not a 404 the way an invented key usually
     * is — KAN-1 exists — so the write would have succeeded, quietly, on the wrong record.
     */
    @Test
    void a_key_that_is_only_a_prefix_of_a_real_one_is_still_ungrounded() {
        Run run = Run.create("Blocker kayıtlarını bul ve kapat", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step search = Step.create(run.id(), 1, "Blocker'ları bul", AgentRole.COORDINATOR,
                "jira.searchIssues", Map.of("jql", "labels = blocker"));
        Step update = Step.create(run.id(), 2, "Kaydı kapat", AgentRole.COORDINATOR,
                "jira.updateIssue", Map.of("issueKey", "KAN-1", "status", "Done"));
        run.replaceSteps(List.of(search, update));
        search.result(Map.of("data", Map.of("issues", List.of(Map.of("key", "KAN-10")))));

        StepOutcome outcome = toolAgent.execute(run, update);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).contains("uydurulmuş tanımlayıcı", "issueKey=KAN-1");
    }

    /** And the same match still lets the record that was actually found through. */
    @Test
    void the_key_the_search_really_returned_is_allowed_through() {
        Run run = Run.create("Blocker kayıtlarını bul ve kapat", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step search = Step.create(run.id(), 1, "Blocker'ları bul", AgentRole.COORDINATOR,
                "jira.searchIssues", Map.of("jql", "labels = blocker"));
        Step update = Step.create(run.id(), 2, "Kaydı kapat", AgentRole.COORDINATOR,
                "jira.updateIssue", Map.of("issueKey", "KAN-10", "status", "Done"));
        run.replaceSteps(List.of(search, update));
        search.result(Map.of("data", Map.of("issues", List.of(Map.of("key", "KAN-10")))));

        assertThat(toolAgent.execute(run, update).ok()).isTrue();
    }

    /**
     * A space in the value used to skip the check entirely. Whether a field names an existing
     * record is decided by the field's name, not by how the value is spelled.
     */
    @Test
    void an_identifier_with_a_space_is_checked_like_any_other() {
        Run run = runWith("Ödeme servisi staging'de patlıyor, bunu kapat", updateStep("KAN 42"));

        StepOutcome outcome = toolAgent.execute(run, run.steps().get(0));

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).contains("uydurulmuş tanımlayıcı", "issueKey=KAN 42");
    }

    /**
     * The connection settings are the documented third source of grounding
     * (docs/NASIL-CALISIYOR.md) — the goal and earlier results were tested, this one was not.
     */
    @Test
    void an_identifier_that_only_the_connection_knows_is_allowed_through() {
        TestDoubles.InMemoryConnectionRepository connections = new TestDoubles.InMemoryConnectionRepository();
        connections.save(new com.relay.domain.Connection(java.util.UUID.randomUUID(), "jira",
                Map.of("defaultIssueKey", "KAN-42"), java.time.Instant.parse("2026-07-31T09:00:00Z")));
        ToolAgent agent = new ToolAgent(tools, llm, connections,
                new AgentJournal(new TestDoubles.RecordingEventPublisher(), clock), clock);

        Run run = runWith("Bu kaydı kapat", updateStep("KAN-42"));

        assertThat(agent.execute(run, run.steps().get(0)).ok()).isTrue();
    }

    /**
     * Turkish is the product's own locale, and {@code "I".toLowerCase()} is {@code "ı"} there
     * — so a locale-sensitive fold would have made the gate answer differently depending on
     * the JVM's default. Every fold in the check is {@code Locale.ROOT}.
     */
    @Test
    void the_match_folds_case_the_same_way_in_every_locale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Run allowed = runWith("ISSUE-1 kaydını kapat", updateStep("issue-1"));
            Run invented = runWith("ISSUE-1 kaydını kapat", updateStep("issue-2"));

            assertThat(toolAgent.execute(allowed, allowed.steps().get(0)).ok()).isTrue();
            assertThat(toolAgent.execute(invented, invented.steps().get(0)).ok()).isFalse();
        } finally {
            Locale.setDefault(original);
        }
    }

    /** A project key is a destination, not a record — creating in it needs no grounding. */
    @Test
    void a_container_field_is_not_treated_as_an_invented_record() {
        Run run = Run.create("Bir hata kaydı aç", java.time.Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step create = Step.create(run.id(), 1, "Kayıt aç", AgentRole.COORDINATOR,
                "jira.createIssue", Map.of("projectKey", "KAN", "issueType", "Bug",
                        "summary", "Ödeme servisi 502 dönüyor"));
        run.replaceSteps(List.of(create));

        assertThat(toolAgent.execute(run, create).ok()).isTrue();
    }
}
