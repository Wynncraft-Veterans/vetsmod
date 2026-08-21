package org.wynnvets.fetcher.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle contract for {@link PollingService}.
 *
 * <p>This is the repo's first concurrent test and the {@code test} Gradle task carries no
 * timeout, so an unbounded {@code await()} would hang the build rather than fail it. Every
 * wait here is therefore bounded and asserted on, every service is stopped in
 * {@link #stopService()}, and nothing asserts on elapsed wall-clock time &mdash; only on
 * ordering and on latch outcomes. Periods are tens of milliseconds so the class runs in well
 * under a second.</p>
 *
 * <p>The two negative waits below ({@code QUIET_MILLIS} after a stop, and the short window
 * that must elapse without a tick before a non-zero initial delay expires) can only fail in
 * one direction. A terminated executor cannot run a task, and a scheduler fires late rather
 * than early, so neither can go red on a loaded machine without the property under test
 * actually being broken.</p>
 */
class PollingServiceTest {

    /** Long enough for a leaked or un-stopped 20 ms schedule to tick many times over. */
    private static final long QUIET_MILLIS = 300L;

    private static final long TICK_MILLIS = 20L;

    private PollingService service;

    @AfterEach
    void stopService() {
        // The interrupt case arms the caller's flag deliberately and asserts it itself;
        // clear whatever is left so it cannot leak into the next test's awaits.
        Thread.interrupted();
        if (service != null) {
            service.stop();
            service = null;
        }
    }

    @Test
    void startSchedulesTheTask() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        service =
                new PollingService(
                        "VetsMod-Test-Start",
                        ran::countDown,
                        0,
                        TICK_MILLIS,
                        TimeUnit.MILLISECONDS);

        assertTrue(service.start(), "start() should report that it started the schedule");
        assertTrue(ran.await(2, TimeUnit.SECONDS), "the scheduled task never ran");
    }

    @Test
    void secondStartIsANoOpAndLeavesNoSecondExecutorRunning() throws InterruptedException {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch firstTick = new CountDownLatch(1);
        service =
                new PollingService(
                        "VetsMod-Test-Idempotent",
                        () -> {
                            ticks.incrementAndGet();
                            firstTick.countDown();
                        },
                        0,
                        TICK_MILLIS,
                        TimeUnit.MILLISECONDS);

        assertTrue(service.start(), "the first start() should report that it started");
        assertFalse(service.start(), "the second start() should report that it did nothing");
        assertTrue(firstTick.await(2, TimeUnit.SECONDS), "the scheduled task never ran");

        // stop() only ever shuts down the executor the field currently holds. If the second
        // start() had built one, the first would still be ticking after this returns -- which
        // is the leak this case discriminates, and the reason it counts rather than just
        // checking the return value.
        service.stop();
        int afterStop = ticks.get();
        Thread.sleep(QUIET_MILLIS);
        assertEquals(
                afterStop,
                ticks.get(),
                "a second executor was left running after stop() shut the first one down");
    }

    @Test
    void stopEndsTheScheduleAndClearsIsRunning() throws InterruptedException {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch firstTick = new CountDownLatch(1);
        service =
                new PollingService(
                        "VetsMod-Test-Stop",
                        () -> {
                            ticks.incrementAndGet();
                            firstTick.countDown();
                        },
                        0,
                        TICK_MILLIS,
                        TimeUnit.MILLISECONDS);

        service.start();
        assertTrue(service.isRunning(), "isRunning() should be true between start and stop");
        assertTrue(firstTick.await(2, TimeUnit.SECONDS), "the scheduled task never ran");

        service.stop();
        assertFalse(service.isRunning(), "isRunning() should be false after stop()");

        int afterStop = ticks.get();
        Thread.sleep(QUIET_MILLIS);
        assertEquals(afterStop, ticks.get(), "the task kept firing after stop()");
    }

    @Test
    void startAfterStopSchedulesOnAFreshExecutor() throws InterruptedException {
        AtomicReference<CountDownLatch> ran = new AtomicReference<>(new CountDownLatch(1));
        service =
                new PollingService(
                        "VetsMod-Test-Restart",
                        () -> ran.get().countDown(),
                        0,
                        TICK_MILLIS,
                        TimeUnit.MILLISECONDS);

        service.start();
        assertTrue(ran.get().await(2, TimeUnit.SECONDS), "the task never ran before the stop");
        service.stop();

        // A shut-down ScheduledExecutorService rejects every further submission, so reusing
        // the old one here would throw RejectedExecutionException rather than reschedule.
        ran.set(new CountDownLatch(1));
        assertTrue(service.start(), "start() after stop() should report that it started again");
        assertTrue(ran.get().await(2, TimeUnit.SECONDS), "the task never ran after the restart");
    }

    @Test
    void zeroInitialDelayFiresWellBeforeOnePeriod() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        service =
                new PollingService(
                        "VetsMod-Test-ZeroDelay", ran::countDown, 0, 5_000, TimeUnit.MILLISECONDS);

        service.start();
        assertTrue(
                ran.await(1, TimeUnit.SECONDS),
                "a zero initial delay should fire at once, not after the 5 s period");
    }

    @Test
    void aNonZeroInitialDelayIsHonoured() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        service =
                new PollingService(
                        "VetsMod-Test-DelayedStart",
                        ran::countDown,
                        400,
                        400,
                        TimeUnit.MILLISECONDS);

        service.start();
        // AnniSnapshotPoller waiting one full period before its first tick is the only reason
        // this parameter exists; hard-coding zero would trip this latch straight away.
        assertFalse(ran.await(150, TimeUnit.MILLISECONDS), "the task ran before its initial delay");
        assertTrue(ran.await(2, TimeUnit.SECONDS), "the task never ran after its initial delay");
    }

    @Test
    void stopReassertsAnInterruptTakenWhileDraining() throws InterruptedException {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        service =
                new PollingService(
                        "VetsMod-Test-Interrupt",
                        () -> {
                            taskStarted.countDown();
                            try {
                                // Nothing counts this down; only shutdownNow() ends the wait.
                                // Bounded anyway, so a broken stop() fails the assertions
                                // below instead of parking a thread for the whole build.
                                neverReleased.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                taskInterrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                        },
                        0,
                        TICK_MILLIS,
                        TimeUnit.MILLISECONDS);

        service.start();
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS), "the blocking task never began");

        // With the flag already set, awaitTermination throws at once rather than draining.
        Thread.currentThread().interrupt();
        service.stop();

        // Assert (and clear) the flag before any further await, or the awaits below throw.
        assertTrue(Thread.interrupted(), "stop() swallowed the caller's interrupt");
        assertTrue(
                taskInterrupted.await(2, TimeUnit.SECONDS),
                "stop() did not shutdownNow() the task that was still blocked");
    }

    @Test
    void theTaskRunsOnTheNamedDaemonThread() throws InterruptedException {
        AtomicReference<String> observedName = new AtomicReference<>();
        AtomicBoolean observedDaemon = new AtomicBoolean();
        CountDownLatch ran = new CountDownLatch(1);
        service =
                new PollingService(
                        "VetsMod-Test-ThreadIdentity",
                        () -> {
                            Thread self = Thread.currentThread();
                            observedName.set(self.getName());
                            observedDaemon.set(self.isDaemon());
                            ran.countDown();
                        },
                        0,
                        TICK_MILLIS,
                        TimeUnit.MILLISECONDS);

        service.start();
        assertTrue(ran.await(2, TimeUnit.SECONDS), "the scheduled task never ran");
        assertEquals(
                "VetsMod-Test-ThreadIdentity",
                observedName.get(),
                "the thread name passed in should be used verbatim");
        assertTrue(observedDaemon.get(), "the poller thread should be a daemon");
    }
}
