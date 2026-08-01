package com.relay.application.orchestrator;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock per run — kept for exactly as long as somebody could still be using it.
 *
 * <p>The obvious version of this ({@code computeIfAbsent} on the way in, {@code remove} in
 * the {@code finally}) does not exclude anybody, and the hole is not theoretical:
 *
 * <pre>
 * T1: computeIfAbsent -> L1, lock()          drives the run
 * T2: computeIfAbsent -> L1, lock()          queued behind L1
 * T1: unlock(); locks.remove(runId, L1)      L1 is no longer in the map
 * T3: computeIfAbsent -> L2 (new), lock()    walks straight in
 *     =&gt; T2 and T3 are inside the same run at the same time
 * </pre>
 *
 * <p>Two threads inside one run means the same tool call twice: two Jira records, two Slack
 * messages, two cost lines, and a {@code runs.save} landing on top of another. That is one
 * colleague pressing Onayla while another presses Durdur — or the same person with the demo
 * open in two tabs.
 *
 * <p>So the entry is reference counted instead. A caller claims the lock and the map entry
 * in one atomic step and gives both back the same way; the entry disappears only once the
 * count reaches zero, which is precisely the moment when nobody holds it and nobody is
 * queued for it. {@link ConcurrentHashMap#compute} is what makes "claim" and "forget"
 * indivisible — checking {@code isLocked()} before removing would only narrow the window,
 * not close it, because a thread can sit between {@code computeIfAbsent} and {@code lock()}.
 *
 * <p>Never removing at all would also be correct, and it is a smaller diff. It was not taken
 * because the map is keyed by run and the process is long lived: a demo day of a few
 * thousand runs leaks a few hundred kilobytes that nothing ever reclaims, and the leak grows
 * with exactly the number the product wants to grow. Counting costs one {@code int}.
 */
final class RunLocks {

    /**
     * The lock plus the number of callers that have claimed it.
     *
     * <p>{@code users} is deliberately a plain field: every read and write happens inside a
     * {@code compute} on the same key, so the map's own per-bin lock provides both the
     * mutual exclusion and the happens-before edge.
     */
    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    /** A claim on a run, held while the caller works and returned exactly once. */
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private final Map<UUID, Entry> locks = new ConcurrentHashMap<>();

    /** Waits for the run to be free, then takes it. */
    Lease acquire(UUID runId) {
        Entry entry = claim(runId);
        entry.lock.lock();
        return lease(runId, entry);
    }

    /**
     * Takes the run only if it is free right now.
     *
     * <p>Durdur uses this: the driving thread holds the run for the whole of a tool call, and
     * the person trying to get away from that call must not be made to wait on it.
     *
     * @return empty when somebody else is inside
     */
    Optional<Lease> tryAcquire(UUID runId) {
        Entry entry = claim(runId);
        if (!entry.lock.tryLock()) {
            forget(runId);
            return Optional.empty();
        }
        return Optional.of(lease(runId, entry));
    }

    /** How many runs are currently being held or waited on. Zero when the system is idle. */
    int tracked() {
        return locks.size();
    }

    private Entry claim(UUID runId) {
        return locks.compute(runId, (key, existing) -> {
            Entry entry = existing == null ? new Entry() : existing;
            entry.users++;
            return entry;
        });
    }

    private void forget(UUID runId) {
        locks.compute(runId, (key, existing) -> existing == null || --existing.users == 0 ? null : existing);
    }

    private Lease lease(UUID runId, Entry entry) {
        return new Lease() {
            private boolean returned;

            @Override
            public void close() {
                if (returned) {
                    return;
                }
                returned = true;
                entry.lock.unlock();
                forget(runId);
            }
        };
    }
}
