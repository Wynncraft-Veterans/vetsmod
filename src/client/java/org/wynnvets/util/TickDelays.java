package org.wynnvets.util;

import com.wynntils.core.components.Managers;
import org.wynnvets.logging.VetsLogger;

/**
 * Delayed work on the client thread, through Wynntils' tick scheduler
 * ({@code Managers.TickScheduler}).
 *
 * <p>{@link #runAfter} runs its body on the client (Render) thread, at the head of a client tick,
 * about the delay it is given, counted in 50 ms ticks. Counting ticks is not counting time:
 * Minecraft runs up to 10 overdue ticks back to back in one frame, so a body can run up to about
 * half a second early just after a stall (up to a frame early in smooth play), and late when the
 * client falls far enough behind that ticks are dropped.</p>
 *
 * <p>Besides the rounding in {@link #ticksFor}, the scheduler has traps this class closes:</p>
 *
 * <ul>
 *   <li>It keys its tasks by their {@link Runnable}, so scheduling an equal one again replaces the
 *       pending task instead of adding a second. Each call here wraps its body in a new object that
 *       keeps {@link Object#equals}, so repeat calls never merge, even for one lambda
 *       instance.</li>
 *   <li>Nothing a task throws propagates out of the scheduler: Wynntils' event bus catches and logs
 *       it, and the task, never removed, runs again on every tick. Here any throw, an
 *       {@link Error} included, is logged once as a warning and ends the task.</li>
 * </ul>
 *
 * <p>Nothing here touches {@code Managers} outside a method body. A static reference would run
 * Wynntils' static initialisers whenever this class loads, which can be before Wynntils has
 * initialised.</p>
 */
public final class TickDelays {

    /** A client tick lasts 50 ms, at 20 ticks a second. */
    private static final long MILLIS_PER_TICK = 50;

    private TickDelays() {}

    /**
     * Runs {@code body} on the client thread about {@code delayMs} from now, counted in ticks
     * (see the class Javadoc). Safe to call from any thread.
     *
     * @param delayMs the delay in milliseconds; zero or less runs the body on the next tick pass
     *     (from inside a task, possibly the current one)
     * @param label names the work in the warning logged if the body throws
     * @param body the work
     */
    public static void runAfter(long delayMs, String label, Runnable body) {
        Managers.TickScheduler.scheduleLater(guarded(label, body), ticksFor(delayMs));
    }

    /**
     * The scheduler delay for {@code delayMs}: {@code ceil(delayMs / 50)}, floored at 0. The
     * scheduler runs a task only after at least that many more ticks, so rounding up never runs a
     * body before {@code delayMs} worth of ticks; how long those ticks take is up to Minecraft (see
     * the class Javadoc).
     */
    // Package-private for unit tests. See TickDelaysTest.
    static int ticksFor(long delayMs) {
        long ticks = Math.max(0, Math.ceilDiv(delayMs, MILLIS_PER_TICK));
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    /** A new task that runs {@code body} and logs anything it throws. */
    // Package-private for unit tests. See TickDelaysTest.
    static Runnable guarded(String label, Runnable body) {
        return new Guarded(label, body);
    }

    /**
     * Runs a body and logs anything it throws. Deliberately not a record and without
     * {@code equals} or {@code hashCode}: the scheduler keys tasks by their {@link Runnable}, so
     * each instance must equal only itself for repeat calls never to merge.
     */
    private static final class Guarded implements Runnable {
        private final String label;
        private final Runnable body;

        Guarded(String label, Runnable body) {
            this.label = label;
            this.body = body;
        }

        @Override
        public void run() {
            try {
                body.run();
            } catch (Throwable t) {
                VetsLogger.warn("{} failed", label, t);
            }
        }
    }
}
