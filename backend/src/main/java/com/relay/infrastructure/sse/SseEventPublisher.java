package com.relay.infrastructure.sse;

import com.relay.application.port.EventPublisher;
import com.relay.application.port.RunEvent;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out of orchestration events over SSE.
 *
 * <p>Every run keeps a small backlog so a client that subscribes after the run started
 * (or reconnects) immediately gets the whole story instead of joining mid-sentence.
 */
@Component
public class SseEventPublisher implements EventPublisher {

    private static final Logger LOG = System.getLogger(SseEventPublisher.class.getName());
    private static final int BACKLOG = 400;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<RunEvent>> backlog = new ConcurrentHashMap<>();
    /** Runs that have said their last word. A late subscriber is served and then hung up on. */
    private final Set<UUID> over = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public SseEventPublisher() {
        heartbeat.scheduleAtFixedRate(this::ping, 20, 20, TimeUnit.SECONDS);
    }

    @Override
    public void publish(UUID runId, RunEvent event) {
        backlog.computeIfAbsent(runId, k -> new ArrayDeque<>()).addLast(event);
        Deque<RunEvent> queue = backlog.get(runId);
        synchronized (queue) {
            while (queue.size() > BACKLOG) {
                queue.removeFirst();
            }
        }
        List<SseEmitter> targets = emitters.get(runId);
        if (targets == null) {
            return;
        }
        for (SseEmitter emitter : new ArrayList<>(targets)) {
            send(runId, emitter, event);
        }
    }

    /** Subscribes a client and replays what it missed. */
    public SseEmitter subscribe(UUID runId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(e -> remove(runId, emitter));

        Deque<RunEvent> queue = backlog.get(runId);
        if (queue != null) {
            List<RunEvent> snapshot;
            synchronized (queue) {
                snapshot = new ArrayList<>(queue);
            }
            snapshot.forEach(event -> send(runId, emitter, event));
        }
        // Somebody opening a run that is already over still gets the whole story — that is
        // the point of the backlog — but then the line ends, instead of hanging on a flow
        // that will never say anything again.
        if (over.contains(runId)) {
            complete(runId, emitter);
        }
        return emitter;
    }

    /**
     * The run is finished: hang up on everyone watching it.
     *
     * <p>Only the ending is announced, not the backlog: replaying the story to a late
     * arrival is a deliberate feature (docs/NASIL-CALISIYOR.md), and the run detail screen
     * relies on it.
     */
    @Override
    public void closed(UUID runId) {
        over.add(runId);
        List<SseEmitter> targets = emitters.remove(runId);
        if (targets == null) {
            return;
        }
        for (SseEmitter emitter : new ArrayList<>(targets)) {
            complete(runId, emitter);
        }
    }

    private void complete(UUID runId, SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sse close failed for run " + runId, e);
        } finally {
            remove(runId, emitter);
        }
    }

    private void send(UUID runId, SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
        } catch (IOException | IllegalStateException e) {
            remove(runId, emitter);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sse send failed for run " + runId, e);
            remove(runId, emitter);
        }
    }

    private void ping() {
        emitters.forEach((runId, list) -> {
            for (SseEmitter emitter : new ArrayList<>(list)) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception e) {
                    remove(runId, emitter);
                }
            }
        });
    }

    private void remove(UUID runId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(runId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(runId);
            }
        }
    }

    public int subscriberCount() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }
}
