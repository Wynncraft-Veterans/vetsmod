package org.wynnvets.distribute;

import com.wynntils.core.components.Managers;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;

/**
 * How a {@code /wv distribute} run starts, waits its turn, and ends.
 *
 * <p>All of the package's state is static, so only one run may hold it at a time.
 * {@link #submit} starts a run at once, or queues it behind the one in progress; each run then
 * ends in exactly one of three ways:</p>
 * <ul>
 *   <li>{@link #finished()} &mdash; the run did what it set out to do, possibly sending nothing
 *       because a head found nobody to send to. The next queued run starts
 *       {@link #QUEUE_GAP_TICKS} later.</li>
 *   <li>{@link #stop(String)} &mdash; the run lost the menu it drives (the player's Esc, a server
 *       close, a screen that replaces it) or stalled. One yellow line names the reason, nothing
 *       it armed calls back, no green "complete" line follows, and {@code @split}'s later phases
 *       do not start. Everything queued behind it is cancelled too: a stop usually means the
 *       player pressed Esc, or the menu broke in a way the next run would meet as well.</li>
 *   <li>{@link #refuse(String)} &mdash; a precondition the run needs is not met, so it sends
 *       nothing. A red line, and the next queued run still starts.</li>
 * </ul>
 * <p>A recipient who cannot be found while the Members menu is still up ends none of these: the
 * run skips them and goes on. {@code vetsmod_distribute.md} §7 lists which exit does which.</p>
 *
 * <p>Every method runs on the client thread, as everything that calls them does.</p>
 */
public final class DistributeRuns {

    /** Gap between one run finishing and the next queued run starting &mdash; the same 10 ticks
     *  {@code SplitDistributor} leaves between phases, for the same reason: the previous run's
     *  close packet and {@code /guild manage} spacing get time to settle. */
    private static final int QUEUE_GAP_TICKS = 10;

    /** Whether a run holds the package's state, from {@link #submit} until it ends. Stays true
     *  across the gap before a queued run starts. */
    private static boolean running = false;

    /** Runs waiting for the current one to end, oldest first. */
    private static final Deque<Runnable> queued = new ArrayDeque<>();

    private DistributeRuns() {}

    /**
     * Starts {@code run} now if no run holds the package's state, and otherwise queues it, with a
     * gray line, to start once the current run finishes or is refused. {@code run} must end in
     * exactly one of {@link #finished()}, {@link #stop(String)} or {@link #refuse(String)}.
     */
    public static void submit(Runnable run) {
        if (running) {
            queued.add(run);
            ChatUtils.sendLocalMessage(
                    Component.literal("Queued: starts when the current distribution finishes.")
                            .withStyle(ChatFormatting.GRAY));
            return;
        }
        running = true;
        run.run();
    }

    /** Ends the run normally, and starts the next queued one {@link #QUEUE_GAP_TICKS} later. */
    public static void finished() {
        if (!running) return;
        Runnable next = queued.poll();
        if (next == null) {
            running = false;
            return;
        }
        VetsLogger.debug("DistributeRuns: starting the next queued run, {} left", queued.size());
        Managers.TickScheduler.scheduleLater(next, QUEUE_GAP_TICKS);
    }

    /**
     * Ends the run with a yellow {@code Distribution stopped: <reason>.}, and cancels every
     * queued run. The caller has already disarmed whatever it armed, and must not run its
     * completion callback.
     *
     * @param reason lower case, without a final full stop, e.g. {@code "the Members menu is not
     *     open"}
     */
    public static void stop(String reason) {
        VetsLogger.debug("DistributeRuns: stopped ({})", reason);
        ChatUtils.sendLocalMessage(
                Component.literal("Distribution stopped: " + reason + ".")
                        .withStyle(ChatFormatting.YELLOW));
        int cancelled = queued.size();
        queued.clear();
        running = false;
        if (cancelled > 0) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "Cancelled "
                                            + cancelled
                                            + " queued distribution"
                                            + (cancelled == 1 ? "." : "s."))
                            .withStyle(ChatFormatting.YELLOW));
        }
    }

    /**
     * Ends the run with {@code message} in red: a precondition the run needs is not met, so
     * it sends nothing. The next queued run still starts. As with {@link #stop}, the caller
     * disarms first and runs no completion callback.
     */
    public static void refuse(String message) {
        VetsLogger.debug("DistributeRuns: refused ({})", message);
        ChatUtils.sendLocalMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        finished();
    }

    /**
     * Drops the queue and frees the run gate, silently, when the client disconnects (called by
     * {@link org.wynnvets.listeners.ServerConnectionListener ServerConnectionListener}). No run
     * survives a disconnect, and this keeps a run that never ended from holding the gate past
     * a relog.
     */
    public static void reset() {
        if (running || !queued.isEmpty()) {
            VetsLogger.debug(
                    "DistributeRuns: reset on disconnect, {} queued dropped", queued.size());
        }
        queued.clear();
        running = false;
    }
}
