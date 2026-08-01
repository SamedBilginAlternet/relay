package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.cost.CostMeter;
import com.relay.application.json.Json;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.application.port.ToolResult;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.PauseReason;
import com.relay.domain.RiskLevel;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.domain.StepStatus;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a restart does to a run in flight, written down exactly as it is today — because the
 * gap between the three cases is not visible from the code and the screen reports one of them
 * wrongly.
 *
 * <p>A run left {@code running} is not recovered, which is deliberate: a tool call that was
 * cut off may or may not have reached the provider, and re-driving it could repeat a write
 * ({@code Coordinator.cancel} refuses to interrupt an in-flight call for the same reason). But
 * it is not <em>reported</em> either. There is no boot hook anywhere in {@code
 * backend/src/main} and {@code RunRepository} has no way to ask for unfinished runs, so the
 * History screen shows "çalışıyor" for ever, the trail says nothing about a restart, and the
 * one thing that would end it — Durdur — is something no part of the UI tells the user to
 * press. The system displays knowledge it does not have.
 *
 * <p>A run waiting on a human is fine, and that is worth holding on to: sessions and runs both
 * live in the database, so the approval still works after a restart. Whatever recovery is
 * eventually built must leave {@code awaiting_approval} and finished runs alone, and these
 * tests are what will say so.
 */
class RestartRecoveryTest {

    /** Counts its calls, so "nothing resumed the run" is an assertion and not a hope. */
    private static class PostMessageTool implements Tool {
        int calls;

        @Override
        public String name() {
            return "slack.postMessage";
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public RiskLevel risk() {
            return RiskLevel.WRITE;
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putArray("required").add("text");
            schema.putObject("properties").putObject("text").put("type", "string");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, Connection connection) {
            calls++;
            return ToolResult.ok(Json.object().put("ok", true), 3, "live");
        }
    }

    private record Api(RunService service, PostMessageTool tool) {
    }

    /**
     * A brand new API process over a store that already has runs in it — every collaborator
     * rebuilt, exactly like a container that has just come up.
     */
    private Api boot(TestDoubles.InMemoryRunRepository runs) {
        PostMessageTool tool = new PostMessageTool();
        ToolRegistry tools = new ToolRegistryImpl(List.of(tool));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        clock.set(Instant.parse("2026-07-31T10:00:00Z"));
        LlmClient llm = new TestDoubles.StaticLlmClient("{\"pass\":true,\"reason\":\"tamam\"}");
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs,
                new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm),
                new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        return new Api(new RunService(runs, coordinator, journal, clock, Runnable::run, 1.0), tool);
    }

    private static Step step() {
        return Step.create(java.util.UUID.randomUUID(), 1, "Kanala haber ver",
                AgentRole.toolAgent("slack.postMessage"), "slack.postMessage",
                Map.of("text", "KAN-42 kaydı kapatıldı."));
    }

    /** A run in whatever state the process died in, already in the store. */
    private Run stored(TestDoubles.InMemoryRunRepository runs, RunStatus status, Step step) {
        Run run = Run.create("KAN-42 kaydını kapat ve kanala haber ver",
                Instant.parse("2026-07-31T09:00:00Z"), 1.0);
        run.status(status);
        run.replaceSteps(List.of(step));
        runs.save(run);
        return run;
    }

    @Test
    void a_run_left_running_by_a_restart_is_still_running_after_it() {
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        Step step = step();
        step.markRunning(Instant.parse("2026-07-31T09:00:05Z"));
        Run run = stored(runs, RunStatus.RUNNING, step);

        Api api = boot(runs);

        // Nothing runs on boot, so nothing looks at this run and nothing resumes it.
        assertThat(api.service().get(run.id()).status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.finishedAt()).isNull();
        assertThat(step.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(api.tool().calls).isZero();
        // And the trail is silent about it: the screen will keep saying "çalışıyor" with
        // nothing anywhere to say the process it belonged to is gone.
        assertThat(run.messages()).isEmpty();
    }

    /** The History screen reads exactly this, which is why it shows a lie. */
    @Test
    void the_history_list_offers_no_way_to_tell_a_stuck_run_from_a_live_one() {
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        Run stuck = stored(runs, RunStatus.RUNNING, step());

        Api api = boot(runs);

        assertThat(api.service().list(0, 20)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(stuck.id());
            assertThat(row.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(row.finishedAt()).isNull();
        });
    }

    /**
     * Today's only exit, and it is one the user has to know about without being told: no
     * screen suggests pressing Durdur on a run that is not going anywhere.
     */
    @Test
    void the_only_way_out_of_a_run_left_running_is_someone_pressing_durdur() {
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        Step step = step();
        Run run = stored(runs, RunStatus.RUNNING, step);

        Api api = boot(runs);
        api.service().cancel(run.id(), "ayse@sirket.com");

        assertThat(run.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.finishedAt()).isNotNull();
        assertThat(step.status()).isEqualTo(StepStatus.REJECTED);
        assertThat(run.messages()).anySatisfy(message ->
                assertThat(message.content()).contains("Akış iptal edildi (ayse@sirket.com)"));
    }

    @Test
    void a_run_waiting_for_approval_survives_a_restart_and_still_resumes_on_approve() {
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        Step step = step();
        step.markAwaitingApproval(PauseReason.POLICY);
        Run run = stored(runs, RunStatus.AWAITING_APPROVAL, step);

        Api api = boot(runs);
        api.service().approve(run.id(), step.id(), "ayse@sirket.com");

        // The pause lives in the database, not in the process that asked the question.
        assertThat(api.tool().calls).isEqualTo(1);
        assertThat(step.status()).isEqualTo(StepStatus.DONE);
        assertThat(run.status()).isEqualTo(RunStatus.DONE);
    }

    /** Whatever sweeps stuck runs one day must not reach back into finished ones. */
    @Test
    void a_finished_run_is_not_touched_by_the_restart() {
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        Step step = step();
        step.markDone(Map.of("ok", true), Instant.parse("2026-07-31T09:00:09Z"));
        Run run = stored(runs, RunStatus.DONE, step);
        run.finishedAt(Instant.parse("2026-07-31T09:00:10Z"));

        boot(runs);

        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(run.finishedAt()).isEqualTo(Instant.parse("2026-07-31T09:00:10Z"));
        assertThat(step.status()).isEqualTo(StepStatus.DONE);
        assertThat(run.messages()).isEmpty();
    }
}
