package com.relay.infrastructure.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.RunEvent;
import com.relay.domain.PauseReason;
import com.relay.domain.Run;
import com.relay.domain.RunStatus;
import com.relay.domain.Step;
import com.relay.support.TestDoubles;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The channel the live screen is fed through.
 *
 * <p>Everything the Live screen shows arrives over this one class — the replay a late
 * arrival gets, the reconnect after a dropped line, the keepalive that stops a proxy
 * closing an idle stream, and the drop of a client that went away. None of it had a test:
 * the orchestrator tests all publish into a list-appending double, so what was covered was
 * "an event was published", never "an event reached the client".
 *
 * <p>These tests talk to the real publisher and watch the emitter, because the emitter is
 * the only place the difference shows up.
 */
class SseEventPublisherTest {

    @Test
    void a_late_subscriber_receives_the_whole_story_from_the_beginning() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();

        publisher.publish(runId, RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", List.of())));
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_STARTED, Map.of("stepId", "1")));

        RecordingEmitter latecomer = new RecordingEmitter();
        publisher.subscribe(runId, latecomer);

        assertThat(latecomer.names())
                .as("joining mid-sentence is not watching a run")
                .containsExactly(RunEvent.RUN_PLANNED, RunEvent.STEP_STARTED);

        publisher.publish(runId, RunEvent.of(RunEvent.RUN_FINISHED, Map.of("status", "done")));

        assertThat(latecomer.names())
                .as("and the story carries on from where the replay left off")
                .containsExactly(RunEvent.RUN_PLANNED, RunEvent.STEP_STARTED, RunEvent.RUN_FINISHED);
    }

    @Test
    void a_reconnecting_client_can_tell_the_replay_from_new_events() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();

        RecordingEmitter first = new RecordingEmitter();
        publisher.subscribe(runId, first);
        publisher.publish(runId, RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", List.of())));
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_STARTED, Map.of("stepId", "1")));
        first.broken = true;
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_FINISHED, Map.of("stepId", "1")));

        RecordingEmitter reconnected = new RecordingEmitter();
        publisher.subscribe(runId, reconnected);

        assertThat(reconnected.names())
                .as("the reconnect is served the same three frames, in the same order")
                .containsExactly(RunEvent.RUN_PLANNED, RunEvent.STEP_STARTED, RunEvent.STEP_FINISHED);
        assertThat(reconnected.frames.get(0).data())
                .as("and with the same contents — a replay describes the past, it does not redraw it")
                .isEqualTo(Map.of("steps", List.of()));
    }

    @Test
    void a_broken_emitter_is_dropped_without_taking_the_others_down() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();

        RecordingEmitter gone = new RecordingEmitter();
        RecordingEmitter watching = new RecordingEmitter();
        publisher.subscribe(runId, gone);
        publisher.subscribe(runId, watching);
        gone.broken = true;

        publisher.publish(runId, RunEvent.of(RunEvent.STEP_STARTED, Map.of("stepId", "1")));

        assertThat(watching.names())
                .as("one client closing its laptop does not end the run for everyone else")
                .containsExactly(RunEvent.STEP_STARTED);
        assertThat(publisher.subscriberCount())
                .as("and the dead connection is not kept on the list to be written to again")
                .isEqualTo(1);
    }

    @Test
    void the_backlog_never_grows_past_its_bound() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();

        for (int i = 1; i <= 1000; i++) {
            publisher.publish(runId, RunEvent.of(RunEvent.AGENT_MESSAGE, Map.of("n", i)));
        }

        RecordingEmitter latecomer = new RecordingEmitter();
        publisher.subscribe(runId, latecomer);

        assertThat(latecomer.frames).hasSize(400);
        assertThat(latecomer.frames.get(0).data())
                .as("what is kept is the newest four hundred, not the first four hundred")
                .isEqualTo(Map.of("n", 601));
        assertThat(latecomer.frames.get(399).data()).isEqualTo(Map.of("n", 1000));
    }

    /**
     * The backlog is written by the thread driving the run and read by the servlet thread of
     * whoever just connected. It used to be appended to outside the lock that guarded the
     * read, on a deque that says in its own javadoc it is not thread safe.
     */
    @Test
    void an_event_published_while_a_client_subscribes_is_neither_lost_nor_duplicated() throws Exception {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();
        int total = 500;
        RecordingEmitter watching = new RecordingEmitter();
        CountDownLatch started = new CountDownLatch(1);

        Thread driver = new Thread(() -> {
            started.countDown();
            for (int i = 1; i <= total; i++) {
                publisher.publish(runId, RunEvent.of(RunEvent.AGENT_MESSAGE, Map.of("n", i)));
            }
        }, "publishing");
        driver.start();
        started.await();
        publisher.subscribe(runId, watching);
        driver.join();

        List<Integer> seen = watching.frames.stream()
                .map(frame -> (Integer) ((Map<?, ?>) frame.data()).get("n"))
                .toList();
        assertThat(seen).isNotEmpty();
        assertThat(seen)
                .as("from wherever it joined, every event once, in order, up to the last one published")
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(seen.get(0), total).boxed().toList());
    }

    /**
     * The backlog was never emptied. Four hundred frames per run, held for the lifetime of
     * the process, whether the run ended a minute ago or in March — memory that grew in a
     * straight line with the number of runs ever started.
     */
    @Test
    void a_finished_run_stops_holding_its_events_in_memory() {
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        Run run = Run.create("Jira'da blocker'ları bul", Instant.parse("2026-08-01T09:00:00Z"), 0.5);
        Step step = Step.create(run.id(), 1, "Blocker'ları ara", "jira-agent", "jira.searchIssues", Map.of());
        step.markDone(Map.of("issues", List.of()), Instant.parse("2026-08-01T09:00:04Z"));
        run.addStep(step);
        run.status(RunStatus.DONE);
        runs.save(run);
        SseEventPublisher publisher = new SseEventPublisher(runs);

        RecordingEmitter watching = new RecordingEmitter();
        publisher.subscribe(run.id(), watching);
        publisher.publish(run.id(), RunEvent.of(RunEvent.RUN_FINISHED, Map.of("status", "done")));
        publisher.closed(run.id());

        assertThat(publisher.remembers(run.id()))
                .as("the run is over and nobody is watching: the database has the story now")
                .isFalse();

        RecordingEmitter latecomer = new RecordingEmitter();
        publisher.subscribe(run.id(), latecomer);
        assertThat(latecomer.names())
                .as("and a latecomer is still told it, out of the rows")
                .containsExactly(RunEvent.RUN_PLANNED, RunEvent.STEP_FINISHED,
                        RunEvent.RUN_COST, RunEvent.RUN_FINISHED);
        assertThat(publisher.subscriberCount()).isZero();
    }

    @Test
    void sse_events_carry_monotonic_ids() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();
        RecordingEmitter watching = new RecordingEmitter();
        publisher.subscribe(runId, watching);

        publisher.publish(runId, RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", List.of())));
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_STARTED, Map.of("stepId", "1")));
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_FINISHED, Map.of("stepId", "1")));

        assertThat(watching.ids())
                .as("an event with no id is an event EventSource cannot resume from")
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void a_client_that_says_where_it_stopped_is_given_only_what_came_after() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();
        publisher.publish(runId, RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", List.of())));
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_STARTED, Map.of("stepId", "1")));
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_FINISHED, Map.of("stepId", "1")));

        RecordingEmitter resumed = new RecordingEmitter();
        publisher.subscribe(runId, resumed, 2L);

        assertThat(resumed.names())
                .as("Last-Event-ID means carry on, not start again")
                .containsExactly(RunEvent.STEP_FINISHED);
        assertThat(resumed.ids()).containsExactly(3L);
    }

    /**
     * The numbering lives in memory, so a restarted API cannot place an id from the run
     * before it. Saying "from the start" out loud beats a silently empty stream.
     */
    @Test
    void an_id_the_run_cannot_place_is_answered_from_the_beginning() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();
        publisher.publish(runId, RunEvent.of(RunEvent.RUN_PLANNED, Map.of("steps", List.of())));
        publisher.publish(runId, RunEvent.of(RunEvent.STEP_STARTED, Map.of("stepId", "1")));

        RecordingEmitter confused = new RecordingEmitter();
        publisher.subscribe(runId, confused, 4096L);

        assertThat(confused.frames.get(0).text()).contains(":replay-from-start");
        assertThat(confused.names()).containsExactly(RunEvent.RUN_PLANNED, RunEvent.STEP_STARTED);
    }

    /**
     * Happened twice during QA, on ordinary deploys: the buffer dies with the process, and
     * every run that was parked on a human came back with a stream that answered keepalives
     * and nothing else. The steps are on disk; losing memory need not lose the story.
     */
    @Test
    void a_client_reconnecting_after_restart_still_sees_the_pending_step() {
        TestDoubles.InMemoryRunRepository runs = new TestDoubles.InMemoryRunRepository();
        Run run = Run.create("Jira'da blocker'ları bul", Instant.parse("2026-08-01T09:00:00Z"), 0.5);
        Step step = Step.create(run.id(), 1, "Kaydı aç", "jira-agent", "jira.createIssue",
                Map.of("projectKey", "KAN", "summary", "QA oturum testi"));
        step.markAwaitingApproval(PauseReason.POLICY);
        run.addStep(step);
        run.status(RunStatus.AWAITING_APPROVAL);
        runs.save(run);

        // A publisher that has never heard of this run: the process it was started in is gone.
        SseEventPublisher afterRestart = new SseEventPublisher(runs);
        RecordingEmitter reconnected = new RecordingEmitter();
        afterRestart.subscribe(run.id(), reconnected);

        assertThat(reconnected.names())
                .as("an awaiting run whose stream says nothing is a screen that died in silence")
                .containsExactly(RunEvent.RUN_PLANNED, RunEvent.STEP_AWAITING, RunEvent.RUN_COST);
        assertThat(reconnected.frames.get(1).data())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("stepId", step.id().toString())
                .containsEntry("params", Map.of("projectKey", "KAN", "summary", "QA oturum testi"));
        assertThat(afterRestart.subscriberCount())
                .as("the run is still waiting on a person, so the line stays open")
                .isEqualTo(1);
    }

    @Test
    void a_heartbeat_keeps_an_idle_stream_open() {
        SseEventPublisher publisher = new SseEventPublisher();
        UUID runId = UUID.randomUUID();
        RecordingEmitter watching = new RecordingEmitter();
        publisher.subscribe(runId, watching);

        publisher.ping();

        assertThat(watching.frames).hasSize(1);
        assertThat(watching.frames.get(0).text())
                .as("a run that is thinking still has to look alive to every proxy in between")
                .contains(":keepalive");
    }

    // -----------------------------------------------------------------------

    /** What was written, and whether the far end is still there to receive it. */
    record Frame(String text, Object data) {
    }

    /**
     * An emitter that keeps what was sent to it instead of writing it to a socket.
     *
     * <p>{@code super.send} is deliberately not called: there is no servlet response behind
     * this emitter, and the point is to read the frame, not to serialise it.
     */
    static final class RecordingEmitter extends SseEmitter {

        final List<Frame> frames = new ArrayList<>();
        /** The client went away. The next write is the moment the server finds out. */
        boolean broken;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) {
                throw new IOException("client went away");
            }
            StringBuilder text = new StringBuilder();
            Object payload = null;
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                text.append(part.getData());
                if (!(part.getData() instanceof String)) {
                    payload = part.getData();
                }
            }
            frames.add(new Frame(text.toString(), payload));
        }

        /** The {@code event:} name of every frame, in the order it arrived. */
        List<String> names() {
            return frames.stream()
                    .map(frame -> frame.text().lines()
                            .filter(line -> line.startsWith("event:"))
                            .map(line -> line.substring("event:".length()))
                            .collect(Collectors.joining()))
                    .filter(name -> !name.isBlank())
                    .toList();
        }

        /** The {@code id:} of every frame that carries one. */
        List<Long> ids() {
            return frames.stream()
                    .flatMap(frame -> frame.text().lines())
                    .filter(line -> line.startsWith("id:"))
                    .map(line -> Long.valueOf(line.substring("id:".length())))
                    .toList();
        }
    }
}
