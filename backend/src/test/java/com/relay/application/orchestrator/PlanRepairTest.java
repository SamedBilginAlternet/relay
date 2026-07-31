package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.LlmRequest;
import com.relay.application.port.LlmResponse;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/**
 * A plan that writes to a record it never looked up is a plan with a hole in it. Failing the
 * run there leaves the user with an error and nothing done, so the coordinator repairs it:
 * a read step goes in front, visibly, and the write is retried against what was found.
 */
class PlanRepairTest {

    /** Plans one ungrounded write; afterwards answers whatever each caller needs. */
    private static class ScriptedLlm implements LlmClient {
        int planCalls;

        @Override
        public LlmResponse complete(LlmRequest request) {
            String content = switch (request.purpose()) {
                case LlmPurpose.PLAN -> {
                    planCalls++;
                    yield """
                            {"steps":[{"title":"Kaydı kapat","toolName":"jira.updateIssue",
                              "params":{"issueKey":"RELAY-1","status":"Done"}}]}
                            """;
                }
                case LlmPurpose.TOOL_PARAMS -> request.user().contains("TOOL: jira.searchIssues")
                        ? "{\"jql\":\"labels = blocker\"}"
                        // The specialist reads the key off the step that just ran.
                        : "{\"issueKey\":\"RELAY-14\",\"status\":\"Done\"}";
                default -> "{\"pass\":true,\"reason\":\"tamam\"}";
            };
            return new LlmResponse(content, 100, 50, 0.0002, "scripted", false);
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public boolean degraded() {
            return false;
        }
    }

    private Run runGoal(String goal) {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(
                new JiraTool.SearchIssues("replay", fixtures),
                new JiraTool.UpdateIssue("replay", fixtures)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new ScriptedLlm();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        Executor sameThread = Runnable::run;
        RunService service = new RunService(runs, coordinator, journal, clock, sameThread, 1.0);

        Run run = service.start(goal, null);
        // The write parks on the approval gate; the human says yes.
        for (int guard = 0; guard < 4 && run.status() == RunStatus.AWAITING_APPROVAL; guard++) {
            Step parked = List.copyOf(run.steps()).stream()
                    .filter(step -> step.status() == com.relay.domain.StepStatus.AWAITING_APPROVAL)
                    .findFirst()
                    .orElse(null);
            if (parked == null) {
                break;
            }
            service.approve(run.id(), parked.id());
        }
        return run;
    }

    @Test
    void a_read_step_is_inserted_in_front_of_the_ungrounded_write() {
        Run run = runGoal("Ödeme servisi staging'de patlıyor, bunu kapat");

        List<Step> steps = run.steps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).toolName()).isEqualTo("jira.searchIssues");
        assertThat(steps.get(0).ordinal()).isEqualTo(1);
        assertThat(steps.get(1).toolName()).isEqualTo("jira.updateIssue");
        assertThat(steps.get(1).ordinal()).isEqualTo(2);
    }

    @Test
    void the_repaired_write_targets_a_record_that_actually_came_back() {
        Run run = runGoal("Ödeme servisi staging'de patlıyor, bunu kapat");

        Step write = run.steps().get(1);
        assertThat(write.params()).containsEntry("issueKey", "RELAY-14");
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }

    /** The repair is announced, not slipped in — the audit trail has to show it. */
    @Test
    void the_repair_is_written_into_the_journal() {
        Run run = runGoal("Ödeme servisi staging'de patlıyor, bunu kapat");

        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).contains("jira.searchIssues adımı eklendi"));
    }
}
