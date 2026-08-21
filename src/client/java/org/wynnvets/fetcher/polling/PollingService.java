package org.wynnvets.fetcher.polling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One place where a poller's lifecycle is defined.
 *
 * <p>Every class in this package used to hand-roll the same thing: a
 * {@link ScheduledExecutorService} field, an inline daemon
 * {@link java.util.concurrent.ThreadFactory ThreadFactory} that names its thread and marks it
 * a daemon, a plain {@code boolean} idempotence guard, and a {@code scheduleAtFixedRate} call.
 * The copies had drifted &mdash; on the guard's name, on whether the lifecycle methods were
 * {@code synchronized}, on where the start-log line went. This class holds the shape once;
 * each poller keeps only the {@link Runnable} it schedules and the parameters it schedules it
 * with.</p>
 *
 * <p><b>The task is taken as-is.</b> Nothing is wrapped around it &mdash; no exception
 * handling, no logging, no rescheduling. Every caller already catches inside its own lambda,
 * with its own message at its own level, and pulling that in here would have rewritten log
 * call sites inside a migration whose whole claim is that nothing changed. One consequence is
 * therefore inherited rather than fixed: no caller catches {@link Throwable}, so an
 * {@link Error} escaping the task &mdash; {@code NoClassDefFoundError},
 * {@code OutOfMemoryError} &mdash; permanently cancels that schedule, silently, exactly as
 * {@link ScheduledExecutorService#scheduleAtFixedRate} has always specified.</p>
 *
 * <p><b>The thread name is the caller's, verbatim.</b> Two of the names passed in disagree
 * with the class that passes them ({@code VetsMod-StaffRanksFetcher},
 * {@code VetsMod-SupportersFetcher}) and two name an instance rather than a class. That is
 * not an oversight to tidy up: a thread name is thread-dump identity, and renaming one is a
 * diagnostics change wearing a refactor's clothes.</p>
 *
 * <p><b>The lifecycle is {@code synchronized}; the six hand-rolled copies mostly were not.</b>
 * Only one of them declared its lifecycle methods {@code synchronized} and none declared its
 * guard {@code volatile}. Unifying on the safe direction has no present symptom &mdash; every
 * {@link #start()} in the mod runs on the mod-init thread, back-to-back &mdash; so this is
 * latent correctness, not a fixed bug.</p>
 *
 * <p><b>On {@link #stop()} having no production caller.</b> It does not, and neither did the
 * six methods it replaces; they were deleted as dead code. This one is reachable from
 * {@code PollingServiceTest}, is written to restore the caller's interrupt flag, and is where
 * a real teardown would hook if the mod ever grows one. The poller threads are daemons, so
 * nothing today needs it.</p>
 *
 * <p>The constructor allocates nothing but the record of intent &mdash; the executor is built
 * in {@link #start()} &mdash; so holding a {@code PollingService} in a {@code static final}
 * field widens no class's initialisation footprint. This class touches neither Minecraft nor
 * Wynntils.</p>
 */
public final class PollingService {

    /** How long {@link #stop()} lets in-flight work drain before it stops waiting. */
    private static final long DRAIN_TIMEOUT_SECONDS = 5L;

    private final String threadName;
    private final Runnable task;
    private final long initialDelay;
    private final long period;
    private final TimeUnit unit;

    private ScheduledExecutorService scheduler;
    private boolean running;

    /**
     * Records what to run and how often. Starts nothing.
     *
     * @param threadName the name given to the single daemon thread this service creates,
     *     used verbatim
     * @param task run on every tick, taken as-is and never wrapped
     * @param initialDelay delay before the first run, in {@code unit}
     * @param period delay between runs, in {@code unit}
     * @param unit the time unit of {@code initialDelay} and {@code period}
     */
    public PollingService(
            String threadName, Runnable task, long initialDelay, long period, TimeUnit unit) {
        this.threadName = threadName;
        this.task = task;
        this.initialDelay = initialDelay;
        this.period = period;
        this.unit = unit;
    }

    /**
     * Starts the schedule on a fresh single-thread daemon executor. Idempotent.
     *
     * <p>The return value is what lets a caller keep a start-log line inside the idempotence
     * guard without this class knowing anything about logging.</p>
     *
     * @return {@code true} if this call started the schedule, {@code false} if it was already
     *     running and nothing happened
     */
    public synchronized boolean start() {
        if (running) {
            return false;
        }

        running = true;
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, threadName);
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
        return true;
    }

    /**
     * Stops the schedule, draining for {@value #DRAIN_TIMEOUT_SECONDS} seconds before
     * cancelling what is still running.
     *
     * <p>If the calling thread is interrupted while draining, the executor is cancelled and
     * the interrupt is re-asserted on the caller rather than swallowed &mdash; the interrupt
     * belongs to whoever called {@code stop()}, not to this service.</p>
     *
     * <p>A stopped service can be started again; {@link #start()} builds a new executor rather
     * than reusing the shut-down one.</p>
     */
    public synchronized void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        running = false;
    }

    /** @return {@code true} between a {@link #start()} that returned {@code true} and the
     *  {@link #stop()} that follows it. */
    public synchronized boolean isRunning() {
        return running;
    }
}
