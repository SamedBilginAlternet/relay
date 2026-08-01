package com.relay.infrastructure.sse;

import com.relay.application.auth.AuthService;
import com.relay.application.port.Clock;
import com.relay.application.port.EventPublisher;
import com.relay.application.port.RunEvent;
import com.relay.application.port.RunRepository;
import com.relay.application.port.SessionListener;
import com.relay.application.port.SessionRepository;
import com.relay.domain.Run;
import com.relay.infrastructure.auth.SessionCookies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
public class SseEventPublisher implements EventPublisher, SessionListener {

    private static final Logger LOG = System.getLogger(SseEventPublisher.class.getName());
    private static final int BACKLOG = 400;
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    /** Where a story that is no longer in memory is read back from. Null in unit tests. */
    private final RunRepository runs;
    /** What every heartbeat re-asks: is the session behind this connection still a session? */
    private final SessionRepository sessions;
    private final Clock clock;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /** A publisher with no memory beyond this process. Only the fan-out tests want this. */
    public SseEventPublisher() {
        this(null, null, null);
    }

    SseEventPublisher(RunRepository runs) {
        this(runs, null, null);
    }

    @Autowired
    public SseEventPublisher(RunRepository runs, SessionRepository sessions, Clock clock) {
        this.runs = runs;
        this.sessions = sessions;
        this.clock = clock;
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
        HttpServletRequest request = currentRequest();
        return subscribe(runId, new SseEmitter(TIMEOUT_MS), resumePoint(request), sessionOf(request));
    }

    /** A subscription that has nothing to carry on from. */
    SseEmitter subscribe(UUID runId, SseEmitter emitter) {
        return subscribe(runId, emitter, null, null);
    }

    SseEmitter subscribe(UUID runId, SseEmitter emitter, Long resumeFrom) {
        return subscribe(runId, emitter, resumeFrom, null);
    }

    /**
     * The same subscription with the emitter handed in.
     *
     * <p>Exists so a test can watch what actually reaches the wire. Everything this class
     * promises — the replay, the hang-up, dropping a broken client — is only observable
     * through the emitter, and a {@code new SseEmitter()} created three lines deep inside
     * the method is not observable at all.
     *
     * @param resumeFrom  the last frame id the client says it already has, or {@code null}
     * @param sessionHash the session this connection belongs to, or {@code null} when sign-in
     *                    is switched off — the connection is then nobody's to revoke
     */
    SseEmitter subscribe(UUID runId, SseEmitter emitter, Long resumeFrom, String sessionHash) {
        Watcher watcher = new Watcher(emitter, sessionHash);
        // Claimed inside the map's own lock, before anything else can decide this run is
        // over and let its channel go: a watcher on the list is what keeps it alive.
        Channel channel = channels.compute(runId, (key, current) -> {
            Channel live = current == null ? new Channel() : current;
            live.watchers.add(watcher);
            return live;
        });
        rebuildIfForgotten(runId, channel);
        List<Frame> replay;
        boolean over;
        boolean restarted;
        synchronized (channel) {
            List<Frame> kept = List.copyOf(channel.history);
            boolean resumable = resumeFrom != null && !kept.isEmpty()
                    && resumeFrom >= kept.get(0).id() - 1 && resumeFrom <= channel.lastId;
            replay = resumable ? kept.stream().filter(frame -> frame.id() > resumeFrom).toList() : kept;
            restarted = resumeFrom != null && !resumable;
            // The cursor is set in the same breath as the snapshot is taken, and the watcher
            // was on the list before either. A frame published between "what have I missed"
            // and "count me in" used to fall into the gap between them: not in the replay,
            // not yet on the watcher list, written by nobody. Now it lands on a watcher whose
            // cursor is not set yet, waits its turn, and goes out exactly once.
            watcher.expect(replay.isEmpty() ? channel.lastId + 1 : replay.get(0).id());
            over = channel.over;
        }
        emitter.onCompletion(() -> remove(runId, watcher));
        emitter.onTimeout(() -> remove(runId, watcher));
        emitter.onError(e -> remove(runId, watcher));

        // The client asked to carry on from an id this run cannot place — it was trimmed out
        // of the backlog, or the API restarted and the numbering began again. Say so and send
        // the story from the top; the reducer is idempotent, so a second telling costs
        // nothing but bytes, and a silent hole would cost the screen.
        if (restarted) {
            note(runId, watcher, "replay-from-start");
        }
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
     * The run is finished: hang up on everyone watching it, and let its frames go.
     *
     * <p>Replaying the story to a late arrival is a deliberate feature and the run detail
     * screen relies on it — but it does not have to be replayed out of this process. The
     * steps and the chatter are on disk, so a channel nobody is listening to any more is
     * memory held for a story {@link RunReplay} can tell again from the rows.
     *
     * <p>Without this the map only ever grew: four hundred frames per run, kept for the
     * lifetime of the process, whether the run ended an hour ago or in March.
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
        forgetIfDone(runId, channel);
    }

    public int subscriberCount() {
        return channels.values().stream().mapToInt(channel -> channel.watchers.size()).sum();
    }

    /** Is this run's story still being held in memory? Package-private: an assertion needs it. */
    boolean remembers(UUID runId) {
        return channels.containsKey(runId);
    }

    // -----------------------------------------------------------------------

    /**
     * Fills an empty channel from the database before anyone is served from it.
     *
     * <p>The buffer is process memory. A restart therefore left every run that was waiting
     * on a human with a stream that answered nothing but keepalives — the screen kept its
     * "Onay bekliyor" badge over an empty timeline, and no reconnect could fix it, because
     * there was nothing in memory left to reconnect to. The steps and the agent chatter are
     * on disk; losing the buffer does not have to mean losing the story.
     *
     * <p>The read happens outside the lock and is thrown away if the channel filled up in
     * the meantime, so the driving thread never waits on a query.
     */
    private void rebuildIfForgotten(UUID runId, Channel channel) {
        if (runs == null || !channel.isEmpty()) {
            return;
        }
        Run run = runs.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        List<RunEvent> story = RunReplay.of(run);
        boolean over = run.status().terminal();
        if (story.isEmpty() && !over) {
            return;
        }
        synchronized (channel) {
            if (!channel.history.isEmpty()) {
                return;
            }
            story.forEach(channel::record);
            channel.over |= over;
        }
    }

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
            // The id is what makes EventSource's own reconnect work: the browser sends the
            // last one back as Last-Event-ID and expects to be given the rest. Without it
            // that protocol is dead and a dropped line is either a full re-telling or a hole,
            // with nothing on the wire to tell the two apart.
            watcher.emitter.send(SseEmitter.event()
                    .id(Long.toString(frame.id()))
                    .name(frame.event().type())
                    .data(frame.event().data()));
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

    /** A comment line: visible to anyone reading the stream, invisible to EventSource. */
    private void note(UUID runId, Watcher watcher, String text) {
        synchronized (watcher) {
            try {
                watcher.emitter.send(SseEmitter.event().comment(text));
            } catch (Exception e) {
                remove(runId, watcher);
            }
        }
    }

    /**
     * The request this stream is being opened for.
     *
     * <p>Both things read off it — the reconnect header and the session cookie — belong to
     * the transport rather than to the runs API, and the rules for answering them live here,
     * next to the buffer and the watcher list they act on.
     */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /**
     * The frame id the reconnecting client says it already has.
     *
     * @return the id, or {@code null} when there is no header, no request, or nonsense in it
     */
    private static Long resumePoint(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader("Last-Event-ID");
        try {
            return header == null || header.isBlank() ? null : Long.valueOf(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The session this connection is opened under — its hash, never the cookie itself. */
    private static String sessionOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String token = SessionCookies.token(request);
        return token == null || token.isBlank() ? null : AuthService.hashToken(token);
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

    /**
     * The session behind a connection is gone: hang up on it now, everywhere.
     *
     * <p>Immediate, rather than waiting for the next heartbeat, because the case this
     * exists for is somebody signing out of a machine they do not trust and expecting the
     * screen to go dead before they walk away from it.
     */
    @Override
    public void sessionEnded(String tokenHash) {
        if (tokenHash == null) {
            return;
        }
        channels.forEach((runId, channel) -> {
            for (Watcher watcher : channel.watchers) {
                if (tokenHash.equals(watcher.sessionHash)) {
                    complete(runId, watcher);
                }
            }
        });
    }

    /**
     * One heartbeat round. Package-private so a test need not wait twenty seconds for it.
     *
     * <p>It is also where the session is re-checked. {@link #sessionEnded} is the fast path
     * and only works inside the process that was signed out of; this one query per open
     * connection every twenty seconds is the guarantee that holds whatever happens — an
     * expiry, a row deleted by hand, a second instance.
     */
    void ping() {
        channels.forEach((runId, channel) -> {
            for (Watcher watcher : channel.watchers) {
                if (revoked(watcher.sessionHash)) {
                    complete(runId, watcher);
                    continue;
                }
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

    /** Unowned connections are never revoked: with sign-in off there is nothing to revoke. */
    private boolean revoked(String sessionHash) {
        if (sessionHash == null || sessions == null || clock == null) {
            return false;
        }
        return sessions.findByTokenHash(sessionHash)
                .filter(session -> !session.expired(clock.now()))
                .isEmpty();
    }

    private void remove(UUID runId, Watcher watcher) {
        Channel channel = channels.get(runId);
        if (channel != null) {
            channel.watchers.remove(watcher);
            forgetIfDone(runId, channel);
        }
    }

    /** A finished run with nobody left on it keeps nothing: the database has the story. */
    private void forgetIfDone(UUID runId, Channel channel) {
        channels.computeIfPresent(runId, (key, current) -> {
            if (current != channel) {
                return current;
            }
            synchronized (current) {
                if (!current.over || !current.watchers.isEmpty()) {
                    return current;
                }
                current.history.clear();
                return null;
            }
        });
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

        boolean isEmpty() {
            synchronized (this) {
                return history.isEmpty();
            }
        }

        /** Caller holds the monitor. */
        Frame record(RunEvent event) {
            Frame frame = new Frame(++lastId, once(event));
            history.addLast(frame);
            while (history.size() > BACKLOG) {
                history.removeFirst();
            }
            return frame;
        }
    }

    /**
     * A step's result, once.
     *
     * <p>{@code step.finished} carried it twice, byte for byte: at the top of the frame and
     * again inside the step the frame also carries. On a single reading step that was ninety
     * per cent of the run's whole SSE traffic — measured at 9,475 bytes each on a 21kB
     * stream — for a value the screen reads from one of the two places and ignores in the
     * other. The nested copy is the one nothing reads: the reducer takes the result from the
     * top and the step only for its timestamps.
     */
    private static RunEvent once(RunEvent event) {
        if (!RunEvent.STEP_FINISHED.equals(event.type())
                || !(event.data().get("step") instanceof Map<?, ?> view)
                || !view.containsKey("result")) {
            return event;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        view.forEach((key, value) -> step.put(String.valueOf(key), value));
        step.remove("result");
        Map<String, Object> data = new LinkedHashMap<>(event.data());
        data.put("step", step);
        return new RunEvent(event.type(), data);
    }

    /** One numbered frame. The number is what keeps a replay from overtaking the live feed. */
    private record Frame(long id, RunEvent event) {
    }

    /** One open connection, and how far down the run it has been written. */
    private static final class Watcher {

        private final SseEmitter emitter;
        /** Whose connection this is. Null when sign-in is off and nobody owns it. */
        private final String sessionHash;
        /** Frames that arrived before their turn. Guarded by this watcher's monitor. */
        private final Map<Long, Frame> pending = new HashMap<>();
        /** Guarded by this watcher's monitor. */
        private long next;

        Watcher(SseEmitter emitter, String sessionHash) {
            this.emitter = emitter;
            this.sessionHash = sessionHash;
        }

        void expect(long firstId) {
            this.next = firstId;
        }
    }
}
