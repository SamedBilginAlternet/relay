package com.relay.infrastructure.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.cost.CostMeter;
import com.relay.application.orchestrator.AgentJournal;
import com.relay.application.orchestrator.Coordinator;
import com.relay.application.orchestrator.Planner;
import com.relay.application.orchestrator.RunService;
import com.relay.application.orchestrator.ToolAgent;
import com.relay.application.orchestrator.Verifier;
import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.LlmClient;
import com.relay.application.port.RunEvent;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.FixtureStore;
import com.relay.infrastructure.tools.JiraTool;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A finished flow hangs up.
 *
 * <p>Nothing ever called {@code complete()}. A run that was over kept its connection for the
 * full thirty-minute timeout and a keepalive every twenty seconds; when the timeout finally
 * struck, the browser could not tell it from a dropped line, reconnected, and was replayed
 * the entire run onto a screen that had already finished it. Every replay rewrote the step
 * timings with the clock of the moment — so a single blip during a demo wipes the durations
 * off the screen and the loop starts again half an hour later.
 *
 * <p>These tests exist so that the ending stays an ending.
 */
class SseStreamLifecycleTest {

    @Test
    void a_finished_run_closes_its_stream_instead_of_idling_for_half_an_hour() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();

        publisher.subscribe(runId);
        publisher.publish(runId, RunEvent.of(RunEvent.RUN_FINISHED, Map.of("status", "done")));
        assertThat(publisher.subscriberCount())
                .as("the frame alone hangs up on nobody")
                .isEqualTo(1);

        publisher.closed(runId);

        assertThat(publisher.subscriberCount())
                .as("a finished run holds no connection open")
                .isZero();
    }

    @Test
    void a_client_that_arrives_after_the_end_gets_the_story_and_then_a_closed_stream() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();

        publisher.publish(runId, RunEvent.of(RunEvent.STEP_STARTED, Map.of("stepId", "1")));
        publisher.publish(runId, RunEvent.of(RunEvent.RUN_FINISHED, Map.of("status", "done")));
        publisher.closed(runId);

        publisher.subscribe(runId);

        assertThat(publisher.subscriberCount())
                .as("replaying the story to a latecomer is a feature; leaving the line open is not")
                .isZero();
    }

    /** The orchestrator is the only thing that knows a run ended, so it has to say so. */
    @Test
    void the_orchestrator_tells_the_transport_when_a_run_is_over() {
        FixtureStore fixtures = new FixtureStore();
        ToolRegistry tools = new ToolRegistryImpl(List.of(new JiraTool.SearchIssues("replay", fixtures)));
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        TestDoubles.RecordingEventPublisher events = new TestDoubles.RecordingEventPublisher();
        LlmClient llm = new StubLlmClient(tools);
        CostMeter costMeter = new CostMeter();
        AgentJournal journal = new AgentJournal(events, clock);
        Coordinator coordinator = new Coordinator(runs, new Planner(llm, tools, costMeter, journal),
                new ToolAgent(tools, llm, new TestDoubles.InMemoryConnectionRepository(), journal, clock),
                new Verifier(llm), new PolicyEngine(new TestDoubles.InMemoryPolicyRepository(), tools),
                costMeter, events, journal, clock);
        RunService service = new RunService(runs, coordinator, journal, clock, Runnable::run, null);

        Run run = service.start("Jira'da blocker'ları bul", null);

        assertThat(run.status()).isEqualTo(RunStatus.DONE);
        assertThat(events.closed)
                .as("run.finished is the last frame, so the line is closed right behind it")
                .containsExactly(run.id());
    }
}
