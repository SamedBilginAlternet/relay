package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Decision;
import com.relay.domain.Run;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * "Silme yasak" is the strongest thing Relay says about itself, and until now the only proof
 * of it was a branch in an enum. No registered tool is {@code DESTRUCTIVE}, so nothing ever
 * walked a destructive step through the coordinator: the policy refusal, the YASAK line in the
 * trail and — the part that actually matters — the tool never being called were all untested.
 *
 * <p>The tool here exists only in this test tree. That is the point: the day someone adds a
 * real delete, this is the behaviour it inherits, and it is written down before it is needed.
 */
class DestructiveStepTest {

    private record Rig(RunService service, Run run, Step step, TestDoubles.DestructiveTool tool) {
    }

    private Rig drive() {
        TestDoubles.DestructiveTool tool = new TestDoubles.DestructiveTool();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        LlmClient llm = new TestDoubles.StaticLlmClient("{\"pass\":true,\"reason\":\"tamam\"}");
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
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0);

        Run run = Run.create("KAN-42 kaydını tamamen sil", Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        Step step = Step.create(run.id(), 1, "Kaydı sil", AgentRole.toolAgent("jira.deleteIssue"),
                "jira.deleteIssue", Map.of("issueKey", "KAN-42"));
        run.replaceSteps(List.of(step));
        runs.save(run);
        service.driveNow(run.id());
        return new Rig(service, run, step, tool);
    }

    @Test
    void a_destructive_step_is_rejected_and_written_into_the_trail() {
        Rig rig = drive();

        assertThat(rig.tool().calls).as("the provider was never called").isZero();
        assertThat(rig.step().status()).isEqualTo(StepStatus.REJECTED);
        assertThat(rig.step().decision()).isEqualTo(Decision.REJECTED);
        // The grounds are named in the language the timeline is read in (#81).
        assertThat(rig.step().rejectReason()).contains("policy forbidden").contains("yıkıcı riski");
        assertThat(rig.run().messages()).anySatisfy(message ->
                assertThat(message.content()).startsWith("YASAK — ").contains("yıkıcı riski"));
    }

    /**
     * A destructive step never reaches the approval gate: it is not a question for a human,
     * it is a refusal. Approving what cannot run would be a signature on nothing.
     */
    @Test
    void a_destructive_step_is_never_offered_for_approval() {
        Rig rig = drive();

        assertThat(rig.step().pausedBy()).isNull();
        assertThat(rig.run().steps()).noneMatch(step -> step.status() == StepStatus.AWAITING_APPROVAL);
    }
}
