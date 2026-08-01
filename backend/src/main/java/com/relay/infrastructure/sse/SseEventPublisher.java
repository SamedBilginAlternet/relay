package com.relay.infrastructure.sse;

import com.relay.application.port.EventPublisher;
import com.relay.application.port.RunEvent;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Two threads are always in play — the one driving the run and the servlet thread of
 * whoever just connected — so a run's backlog, its watchers and the sequence they are
 * numbered by are one object with one lock, rather than three maps that have to be kept
 * in step by hand.
 */
@Component
public class SseEventPublisher implements EventPublisher {

    private static final Logger LOG = System.getLogger(SseEventPublisher.class.getName());
    private static final int BACKLOG = 400;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
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
        Channel channel = channels.computeIfAbsent(runId, key -> new Channel());
        Frame frame;
        List<Watcher> targets;
        synchronized (channel) {
            frame = channel.record(event);
            targets = List.copyOf(channel.watchers);
        }
        for (Watcher watcher : targets) {
            deliver(runId, watcher, frame);
        }
    }

    /** Subscribes a client and replays what it missed. */
    public SseEmitter subscribe(UUID runId) {
        return subscribe(runId, new SseEmitter(TIMEOUT_MS));
    }

    /**
     * The same subscription with the emitter handed in.
     *
     * <p>Exists so a test can watch what actually reaches the wire. Everything this class
     * promises — the replay, the hang-up, dropping a broken client — is only observable
     * through the emitter, and a {@code new SseEmitter()} created three lines deep inside
     * the method is not observable at all.
     */
    SseEmitter subscribe(UUID runId, SseEmitter emitter) {
        Channel channel = channels.computeIfAbsent(runId, key -> new Channel());
        Watcher watcher = new Watcher(emitter);
        List<Frame> replay;
        boolean over;
        synchronized (channel) {
            replay = List.copyOf(channel.history);
            // The cursor and the registration are set in the same breath as the snapshot is
            // taken. A frame published between "what have I missed" and "count me in" used to
            // fall into the gap between them: it was not in the replay and not yet on the
            // watcher list, and nobody ever wrote it.
            watcher.expect(replay.isEmpty() ? channel.lastId + 1 : replay.get(0).id());
            channel.watchers.add(watcher);
            over = channel.over;
        }
        emitter.onCompletion(() -> remove(runId, watcher));
        emitter.onTimeout(() -> remove(runId, watcher));
        emitter.onError(e -> remove(runId, watcher));

        for (Frame frame : replay) {
            deliver(runId, watcher, frame);
        }
        // Somebody opening a run that is already over still gets the whole story — that is
        // the point of the backlog — but then the line ends, instead of hanging on a flow
        // that will never say anything again.
        if (over) {
            complete(runId, watcher);
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
        Channel channel = channels.computeIfAbsent(runId, key -> new Channel());
        List<Watcher> targets;
        synchronized (channel) {
            channel.over = true;
            targets = List.copyOf(channel.watchers);
        }
        for (Watcher watcher : targets) {
            complete(runId, watcher);
        }
    }

    public int subscriberCount() {
        return channels.values().stream().mapToInt(channel -> channel.watchers.size()).sum();
    }

    // -----------------------------------------------------------------------

    /**
     * Writes one frame to one client, in order.
     *
     * <p>Replay and live fan-out run on different threads and can reach the same watcher at
     * the same time, so the frame's own number decides: anything already written is dropped,
     * anything early waits until its turn comes round.
     */
    private void deliver(UUID runId, Watcher watcher, Frame frame) {
        synchronized (watcher) {
            if (frame.id() < watcher.next) {
                return;
            }
            if (frame.id() > watcher.next) {
                watcher.pending.put(frame.id(), frame);
                return;
            }
            Frame due = frame;
            while (due != null) {
                if (!write(runId, watcher, due)) {
                    return;
                }
                watcher.next = due.id() + 1;
                due = watcher.pending.remove(watcher.next);
            }
        }
    }

    /** @return {@code false} when the client is gone and has been dropped. */
    private boolean write(UUID runId, Watcher watcher, Frame frame) {
        try {
            watcher.emitter.send(SseEmitter.event().name(frame.event().type()).data(frame.event().data()));
            return true;
        } catch (IOException | IllegalStateException e) {
            remove(runId, watcher);
            return false;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sse send failed for run " + runId, e);
            remove(runId, watcher);
            return false;
        }
    }

    private void complete(UUID runId, Watcher watcher) {
        try {
            watcher.emitter.complete();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sse close failed for run " + runId, e);
        } finally {
            remove(runId, watcher);
        }
    }

    /** One heartbeat round. Package-private so a test need not wait twenty seconds for it. */
    void ping() {
        channels.forEach((runId, channel) -> {
            for (Watcher watcher : channel.watchers) {
                synchronized (watcher) {
                    try {
                        watcher.emitter.send(SseEmitter.event().comment("keepalive"));
                    } catch (Exception e) {
                        remove(runId, watcher);
                    }
                }
            }
        });
    }

    private void remove(UUID runId, Watcher watcher) {
        Channel channel = channels.get(runId);
        if (channel != null) {
            channel.watchers.remove(watcher);
        }
    }

    /** One run's live channel: the frames worth repeating and whoever is listening. */
    private static final class Channel {

        /** Guarded by this channel's monitor. */
        private final Deque<Frame> history = new ArrayDeque<>();
        private final List<Watcher> watchers = new CopyOnWriteArrayList<>();
        /** Guarded by this channel's monitor. */
        private long lastId;
        /** Guarded by this channel's monitor. */
        private boolean over;

        /** Caller holds the monitor. */
        Frame record(RunEvent event) {
            Frame frame = new Frame(++lastId, event);
            history.addLast(frame);
            while (history.size() > BACKLOG) {
                history.removeFirst();
            }
            return frame;
        }
    }

    /** One numbered frame. The number is what keeps a replay from overtaking the live feed. */
    private record Frame(long id, RunEvent event) {
    }

    /** One open connection, and how far down the run it has been written. */
    private static final class Watcher {

        private final SseEmitter emitter;
        /** Frames that arrived before their turn. Guarded by this watcher's monitor. */
        private final Map<Long, Frame> pending = new HashMap<>();
        /** Guarded by this watcher's monitor. */
        private long next;

        Watcher(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void expect(long firstId) {
            this.next = firstId;
        }
    }
}
