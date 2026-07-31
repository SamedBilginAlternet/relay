package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.GitHubTool;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A card on the Bugün screen must not be a shortcut around governance: a suggested WRITE
 * still parks on the human, and an unregistered tool never becomes a run at all.
 */
class RunFromSuggestionTest {

    private RunService runService;
    private TestDoubles.RecordingEventPublisher events;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new JiraTool.CreateIssue("replay", fixtures),
                new JiraTool.ListMyIssues("replay", fixtures),
                new GitHubTool.ListMyPullRequests("replay", fixtures)));

        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        events = new TestDoubles.RecordingEventPublisher();

        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        PolicyEngine policyEngine = new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools);
        Coordinator coordinator = new Coordinator(runs, new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm), policyEngine, costMeter, events, journal, clock);
        runService = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0, tools);
    }

    @Test
    void aWriteSuggestionStillStopsAtTheApprovalGate() {
        Run run = runService.startFromSuggestion("jira.createIssue",
                Map.of("projectKey", "KAN", "issueType", "Bug",
                        "summary", "Ödeme servisi staging'de 502 dönüyor"),
                "Jira ticket aç", null);

        assertThat(run.steps()).hasSize(1);
        Step step = run.steps().get(0);
        assertThat(step.toolName()).isEqualTo("jira.createIssue");
        assertThat(step.status()).isEqualTo(StepStatus.AWAITING_APPROVAL);
        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.goal()).isEqualTo("Jira ticket aç");

        runService.approve(run.id(), step.id());

        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(step.status()).isEqualTo(StepStatus.DONE);
        assertThat(events.has("run.finished")).isTrue();
    }

    @Test
    void aReadSuggestionRunsWithoutAskingButIsStillARealRun() {
        Run run = runService.startFromSuggestion("jira.listMyIssues", Map.of(),
                "Üstümdeki işleri getir", null);

        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).status()).isEqualTo(StepStatus.DONE);
        // Never planned — the user already decided what to run.
        assertThat(events.has("run.planned")).isFalse();
    }

    @Test
    void anUnknownToolIsRefusedBeforeARunExists() {
        assertThatThrownBy(() -> runService.startFromSuggestion("pagerduty.page", Map.of(), "Nöbetçiyi ara", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tool");

        assertThatThrownBy(() -> runService.startFromSuggestion("  ", Map.of(), "boş", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
