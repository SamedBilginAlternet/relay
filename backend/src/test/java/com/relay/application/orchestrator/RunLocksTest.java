package com.relay.application.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Two people, one flow.
 *
 * <p>The coordinator used to take its per-run lock with {@code computeIfAbsent} and drop it
 * out of the map in the {@code finally}. A thread queued behind the lock at that moment kept
 * the object it had already been handed, while the next caller found an empty slot and built
 * itself a second lock — so both were inside the same run at once. In front of a jury that is
 * the same Jira record created twice and the same Slack message posted twice, from one click
 * each by two people who both thought they were the only one.
 *
 * <p>The tests below are written with latches rather than sleeps because the failure is a
 * handover between two threads, and "it did not happen this time" is not an answer.
 */
class RunLocksTest {

    private static final long TIMEOUT_MS = 2000;

    @Test
    void a_lock_released_while_someone_waits_is_never_handed_out_twice() throws Exception {
        RunLocks locks = new RunLocks();
        UUID runId = UUID.randomUUID();
        CountDownLatch secondIsInside = new CountDownLatch(1);
        CountDownLatch secondMayLeave = new CountDownLatch(1);
        CountDownLatch thirdIsInside = new CountDownLatch(1);

        RunLocks.Lease first = locks.acquire(runId);

        Thread second = holder(locks, runId, secondIsInside, secondMayLeave);
        second.start();
        // The whole bug is about a thread that is *already queued* when the lock is given
        // back, so the test does not proceed until it demonstrably is.
        awaitBlocked(second);

        first.close();
        assertThat(secondIsInside.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .as("the queued thread takes the run over")
                .isTrue();

        Thread third = holder(locks, runId, thirdIsInside, new CountDownLatch(0));
        third.start();

        assertThat(thirdIsInside.await(300, TimeUnit.MILLISECONDS))
                .as("a third caller must wait: the run is still being driven by the second")
                .isFalse();

        secondMayLeave.countDown();
        assertThat(thirdIsInside.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .as("and gets in as soon as the run is genuinely free")
                .isTrue();

        second.join(TIMEOUT_MS);
        third.join(TIMEOUT_MS);
    }

    @Test
    void a_run_nobody_is_holding_is_forgotten_rather_than_kept_forever() {
        RunLocks locks = new RunLocks();
        UUID runId = UUID.randomUUID();

        RunLocks.Lease lease = locks.acquire(runId);
        assertThat(locks.tracked()).isEqualTo(1);
        lease.close();

        assertThat(locks.tracked())
                .as("a process that lives for months may not keep a lock per run it ever drove")
                .isZero();
    }

    /** Durdur must never queue behind the tool call it is trying to get away from. */
    @Test
    void a_stop_that_finds_the_run_busy_gives_up_instead_of_waiting() throws Exception {
        RunLocks locks = new RunLocks();
        UUID runId = UUID.randomUUID();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch mayLeave = new CountDownLatch(1);

        Thread driver = holder(locks, runId, inside, mayLeave);
        driver.start();
        assertThat(inside.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        assertThat(locks.tryAcquire(runId)).isEmpty();

        mayLeave.countDown();
        driver.join(TIMEOUT_MS);

        Optional<RunLocks.Lease> free = locks.tryAcquire(runId);
        assertThat(free).isPresent();
        free.orElseThrow().close();
        assertThat(locks.tracked())
                .as("a refused attempt must not leave a claim behind")
                .isZero();
    }

    private static Thread holder(RunLocks locks, UUID runId, CountDownLatch entered, CountDownLatch mayLeave) {
        Thread thread = new Thread(() -> {
            try (RunLocks.Lease lease = locks.acquire(runId)) {
                entered.countDown();
                mayLeave.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        return thread;
    }

    /** Spins until the thread is genuinely parked on the lock rather than merely started. */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                    || state == Thread.State.BLOCKED) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("thread never queued for the lock");
    }
}
