package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.RunEvent;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Decision;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end orchestration with the deterministic stub LLM and replayed tools —
 * exactly the configuration the demo falls back to.
 */
class OrchestratorHappyPathTest {

    private static final String GOAL =
            "Sprint'teki blocker'ları Jira'da bul, durumlarını güncelle ve ekibe Slack'ten özet at";

    private TestDoubles.InMemoryRunRepository runs;
    private TestDoubles.RecordingEventPublisher events;
    private RunService runService;

    @BeforeEach
    void setUp() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.GetIssue("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures),
                new JiraTool.AddComment("replay", fixtures),
                new SlackTool.ListChannels("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));

        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new StubLlmClient(tools);
        runs = new TestDoubles.InMemoryRunRepository();
        events = new TestDoubles.RecordingEventPublisher();

        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        PolicyEngine policyEngine = new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools);
        Planner planner = new Planner(llm, tools, costMeter, journal);
        ToolAgent toolAgent = new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(),
                journal, clock);
        Verifier verifier = new Verifier(llm);
        Coordinator coordinator = new Coordinator(runs, planner, toolAgent, verifier, policyEngine,
                costMeter, events, journal, clock);

        Executor sameThread = Runnable::run;
        runService = new RunService(runs, coordinator, journal, clock, sameThread, 1.0);
    }

    @Test
    void planStopsAtTheFirstWriteStepAndFinishesAfterApprovals() {
        Run run = runService.start(GOAL, null);

        // 1. planned, then parked on the first write step
        assertThat(events.has(RunEvent.RUN_PLANNED)).isTrue();
        assertThat(run.steps()).isNotEmpty();
        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);

        Step first = run.steps().get(0);
        assertThat(first.toolName()).isEqualTo("jira.searchIssues");
        assertThat(first.status()).isEqualTo(StepStatus.DONE);
        assertThat(first.decision()).isEqualTo(Decision.AUTO);
        assertThat(Map.class.cast(first.result()).get("mode")).isEqualTo("replay");

        Step awaiting = run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow();
        assertThat(awaiting.toolName()).isEqualTo("jira.updateIssue");
        assertThat(events.has(RunEvent.STEP_AWAITING)).isTrue();

        // 2. approve every gate until the run finishes
        int guard = 0;
        while (run.status() == RunStatus.AWAITING_APPROVAL && guard++ < 10) {
            Step gate = run.steps().stream()
                    .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                    .findFirst()
                    .orElseThrow();
            runService.approve(run.id(), gate.id());
        }

        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(run.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.DONE));
        assertThat(events.has(RunEvent.RUN_FINISHED)).isTrue();
    }

    @Test
    void parametersAreResolvedFromEarlierStepResults() {
        Run run = runService.start(GOAL, null);
        approveEverything(run);

        Step update = stepFor(run, "jira.updateIssue");
        // The issue key comes from the replayed search result, not from thin air.
        assertThat(update.params().get("issueKey")).isEqualTo("RELAY-14");

        Step post = stepFor(run, "slack.postMessage");
        assertThat(String.valueOf(post.params().get("channel"))).startsWith("#");
        assertThat(String.valueOf(post.params().get("text"))).contains("RELAY-14");
    }

    @Test
    void everyTransitionShowsUpOnTheTimeline() {
        Run run = runService.start(GOAL, null);
        approveEverything(run);

        assertThat(events.ofType(RunEvent.STEP_STARTED)).hasSameSizeAs(run.steps());
        assertThat(events.ofType(RunEvent.STEP_FINISHED)).hasSameSizeAs(run.steps());
        assertThat(events.ofType(RunEvent.RUN_COST)).isNotEmpty();
        assertThat(events.ofType(RunEvent.AGENT_MESSAGE)).isNotEmpty();

        // agent-to-agent chatter is persisted on the run as well
        assertThat(run.messages()).anySatisfy(message -> {
            assertThat(message.fromAgent()).isEqualTo("planner");
            assertThat(message.toAgent()).isEqualTo("coordinator");
        });
        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.fromAgent()).isEqualTo("verifier"));
    }

    @Test
    void costAccumulatesAcrossStepsEvenOnTheStub() {
        Run run = runService.start(GOAL, null);
        approveEverything(run);

        assertThat(run.costTokens()).isGreaterThan(0);
        long stepTokens = run.steps().stream().mapToLong(Step::tokens).sum();
        assertThat(run.costTokens()).isGreaterThanOrEqualTo(stepTokens);
    }

    @Test
    void rejectingAStepRecordsTheReasonAndKeepsGoing() {
        Run run = runService.start(GOAL, null);
        Step gate = run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow();

        runService.reject(run.id(), gate.id(), "bu ticket'lara dokunma");
        approveEverything(run);

        assertThat(gate.status()).isEqualTo(StepStatus.REJECTED);
        assertThat(gate.decision()).isEqualTo(Decision.REJECTED);
        assertThat(gate.rejectReason()).contains("dokunma");
        assertThat(run.status()).isIn(RunStatus.DONE, RunStatus.FAILED);
        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).contains("dokunma"));
    }

    @Test
    void forbiddenToolIsRejectedAndAudited() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.SearchIssues("replay", fixtures)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryPolicyRepository policies = new TestDoubles.InMemoryPolicyRepository();
        PolicyEngine policyEngine = new PolicyEngine(policies, tools);
        policyEngine.set("jira.searchIssues", com.relay.domain.PolicyMode.FORBIDDEN);

        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        LlmClient llm = new StubLlmClient(tools);
        Coordinator coordinator = new Coordinator(runs, new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm), policyEngine, costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, null);

        Run run = service.start("Jira'da blocker'ları bul", null);

        Step step = run.steps().get(0);
        assertThat(step.status()).isEqualTo(StepStatus.REJECTED);
        assertThat(step.rejectReason()).contains("politika izin vermiyor");
        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.fromAgent()).isEqualTo("policy"));
    }

    @Test
    void budgetExceededPausesTheRunAndApprovingItResumes() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.SearchIssues("replay", fixtures)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();

        // A stub that actually bills, so the budget can be blown deterministically.
        LlmClient billing = new LlmClient() {
            private final StubLlmClient delegate = new StubLlmClient(tools);

            @Override
            public com.relay.application.port.LlmResponse complete(
                    com.relay.application.port.LlmRequest request) {
                var response = delegate.complete(request);
                return new com.relay.application.port.LlmResponse(response.content(), 500, 200, 0.01,
                        "billing-stub", true);
            }

            @Override
            public String name() {
                return "billing-stub";
            }

            @Override
            public boolean degraded() {
                return true;
            }
        };

        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        PolicyEngine policyEngine = new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools);
        Coordinator coordinator = new Coordinator(runs, new Planner(billing, tools, costMeter, journal),
                new ToolAgent(tools, billing, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(billing), policyEngine, costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, null);

        // Planning alone costs $0.01, which is over the $0.005 ceiling.
        Run run = service.start("Jira'da blocker'ları bul", 0.005);

        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.fromAgent()).isEqualTo("cost"));

        Step gate = run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow();
        service.approve(run.id(), gate.id());

        assertThat(run.budgetOverridden()).isTrue();
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(run.costUsd()).isGreaterThan(0.005);
    }

    // ---- helpers ----------------------------------------------------------

    private void approveEverything(Run run) {
        int guard = 0;
        while (run.status() == RunStatus.AWAITING_APPROVAL && guard++ < 12) {
            Step gate = run.steps().stream()
                    .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                    .findFirst()
                    .orElse(null);
            if (gate == null) {
                break;
            }
            runService.approve(run.id(), gate.id());
        }
    }

    private Step stepFor(Run run, String toolName) {
        return run.steps().stream()
                .filter(step -> toolName.equals(step.toolName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step for " + toolName));
    }
}
