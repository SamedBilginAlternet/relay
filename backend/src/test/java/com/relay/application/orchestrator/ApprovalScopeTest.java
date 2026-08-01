package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.RunEvent;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.PauseReason;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.SlackTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.OrchestratorHarness;
import com.relay.support.TestDoubles;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An approval may only grant what the person pressing it was asked about.
 *
 * <p>It used to grant more. {@code approve} could not see <em>why</em> the step had stopped —
 * the reason lived nowhere but in a sentence — so it fell back on "is the run over budget?".
 * A run that drifted past its ceiling while a Slack message sat at the write gate turned that
 * message's approval into an unlimited budget for the rest of the run, and the screen said
 * nothing about money. The second half was the same mistake read backwards: the write gate was
 * evaluated before the money gate, so a run stopped by cost announced itself as a request for
 * writing permission.
 *
 * <p>Deleting these tests means going back to a demo where "Onayla" on a message is also a
 * signature on the spending limit.
 */
class ApprovalScopeTest {

    private final TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
    private final TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();

    /**
     * A run whose planning alone costs more than its ceiling, so it stops on money before it
     * has done anything else.
     */
    private RunService overspendingService(double costPerCall) {
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.SearchIssues("replay", new FixtureStore())));
        return service(tools, billing(tools, costPerCall));
    }

    /** The demo configuration: replayed tools, deterministic stub model, nothing billed. */
    private RunService freeService() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures),
                new SlackTool.ListChannels("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));
        return service(tools, new StubLlmClient(tools));
    }

    private RunService service(ToolRegistry tools, LlmClient llm) {
        return OrchestratorHarness.with(tools).llm(llm).runs(runs).events(events)
                .budgetUsd(null).build().service;
    }

    /** The stub model, but it charges — so the ceiling can be crossed deterministically. */
    private static LlmClient billing(ToolRegistry tools, double costUsd) {
        return new LlmClient() {
            private final StubLlmClient delegate = new StubLlmClient(tools);

            @Override
            public LlmResponse complete(LlmRequest request) {
                LlmResponse response = delegate.complete(request);
                return new LlmResponse(response.content(), 500, 200, costUsd, "billing-stub", true);
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
    }

    @Test
    void approving_a_write_does_not_also_raise_the_spending_ceiling() {
        RunService service = freeService();
        Run run = service.start(
                "Sprint'teki blocker'ları Jira'da bul, durumlarını güncelle ve ekibe Slack'ten özet at", 1.0);

        Step gate = parked(run);
        assertThat(gate.pausedBy()).as("this pause is about a write, not about money")
                .isEqualTo(PauseReason.POLICY);

        // The run drifts past its ceiling while the write sits at the gate — a specialist
        // retry, a verifier round, anything that costs. This is the exact case that used to
        // turn a message approval into an unlimited budget.
        run.addCost(0, 10.0);
        assertThat(run.overBudget()).isTrue();

        service.approve(run.id(), gate.id());

        assertThat(run.budgetOverridden())
                .as("approving a message says nothing about how much the run may spend")
                .isFalse();
        assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(parked(run).pausedBy())
                .as("the run stops again, this time on the gate it actually hit")
                .isEqualTo(PauseReason.BUDGET);
    }

    @Test
    void approving_a_budget_pause_does_raise_the_ceiling() {
        // Planning alone costs $0.01, which is over the $0.005 ceiling.
        RunService service = overspendingService(0.01);
        Run run = service.start("Jira'da blocker'ları bul", 0.005);

        Step gate = parked(run);
        assertThat(gate.pausedBy()).isEqualTo(PauseReason.BUDGET);

        service.approve(run.id(), gate.id());

        assertThat(run.budgetOverridden()).isTrue();
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(run.costUsd()).isGreaterThan(0.005);
    }

    /**
     * Lifting the ceiling is not a signature on the step behind it: the money gate can land on
     * a write, and that write still has to be read and approved on its own.
     */
    @Test
    void lifting_the_ceiling_does_not_pre_approve_the_write_behind_it() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new SlackTool.ListChannels("replay", fixtures),
                new SlackTool.PostMessage("replay", fixtures)));
        RunService service = service(tools, billing(tools, 0.01));

        // The read alone bills past the ceiling, so the money gate is what the write meets
        // first — while nobody has said anything about the write itself yet.
        Run run = service.startFromPlaybook("Blocker'ları bul ve ekibe yaz", "test",
                List.of(new RunService.SeedStep("Blocker'ları bul", "jira.searchIssues",
                                java.util.Map.of("jql", "labels = blocker")),
                        new RunService.SeedStep("Ekibe yaz", "slack.postMessage",
                                java.util.Map.of("channel", "#genel", "text", "Blocker özeti"))),
                0.005);

        Step money = parked(run);
        assertThat(money.toolName()).isEqualTo("slack.postMessage");
        assertThat(money.pausedBy()).isEqualTo(PauseReason.BUDGET);

        service.approve(run.id(), money.id());

        Step again = parked(run);
        assertThat(again.id()).as("the very same write step").isEqualTo(money.id());
        assertThat(again.pausedBy())
                .as("the message is asked about separately, on its own screen")
                .isEqualTo(PauseReason.POLICY);
    }

    @Test
    void a_budget_pause_says_it_is_about_money_not_about_permission() {
        RunService service = overspendingService(0.01);
        service.start("Jira'da blocker'ları bul", 0.005);

        RunEvent awaiting = events.ofType(RunEvent.STEP_AWAITING).get(0);

        assertThat(String.valueOf(awaiting.data().get("pausedBy"))).isEqualTo("budget");
        assertThat(String.valueOf(awaiting.data().get("reason")))
                .contains("bütçe aşıldı")
                .doesNotContain("onay gerekiyor —");
    }

    private static Step parked(Run run) {
        return run.steps().stream()
                .filter(step -> step.status() == StepStatus.AWAITING_APPROVAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step is waiting on a human"));
    }
}
