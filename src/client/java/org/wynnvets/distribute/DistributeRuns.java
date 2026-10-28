package org.wynnvets.distribute;

import com.wynntils.core.components.Managers;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.distribute.distributor.MemberSlotPresser;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.walker.GuildLogWalker;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.logging.VetsLogger;

/**
 * How a {@code /wv distribute} run, or a {@code /wv laurels update} run, starts, waits its turn,
 * and ends.
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
 * <p>A run's chat lines name it by the noun it was submitted with: {@code "distribution"} for
 * {@code /wv distribute}, {@code "laurels update"} for the other.</p>
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

    /** A run waiting its turn, and the noun its chat lines name it by. */
    private record Queued(String noun, Runnable run) {}

    /** Runs waiting for the current one to end, oldest first. */
    private static final Deque<Queued> queued = new ArrayDeque<>();

    /** The noun of the run holding the package's state, for {@link #stop}'s line and the next
     *  queued run's "Queued" line. Set when a run starts or is dequeued, cleared by
     *  {@link #reset()}. */
    private static String currentNoun = null;

    /** Bumped by {@link #reset()}. A step a run scheduled before a disconnect checks it through
     *  {@link #fenced} and does nothing after one: a queued run already scheduled in the gap, or a
     *  head's next step still waiting on its HTTP fetch or a phase gap. */
    private static int generation = 0;

    private DistributeRuns() {}

    /**
     * Starts {@code run} now if no run holds the package's state, and otherwise queues it, with a
     * gray line, to start once the current run finishes or is refused. {@code run} must end in
     * exactly one of {@link #finished()}, {@link #stop(String)} or {@link #refuse(String)}.
     *
     * @param noun what the run's chat lines call it, lower case and singular, e.g.
     *     {@code "distribution"}
     */
    public static void submit(String noun, Runnable run) {
        if (running) {
            queued.add(new Queued(noun, run));
            ChatUtils.sendLocalMessage(
                    Component.literal(queuedLine(currentNoun)).withStyle(ChatFormatting.GRAY));
            return;
        }
        running = true;
        currentNoun = noun;
        run.run();
    }

    /** Ends the run normally, and starts the next queued one {@link #QUEUE_GAP_TICKS} later. */
    public static void finished() {
        if (!running) return;
        Queued next = queued.poll();
        if (next == null) {
            running = false;
            return;
        }
        currentNoun = next.noun();
        VetsLogger.debug("DistributeRuns: starting the next queued run, {} left", queued.size());
        Managers.TickScheduler.scheduleLater(fenced(generation, next.run()), QUEUE_GAP_TICKS);
    }

    /** The disconnect count, for a run to capture before it hands a step to an HTTP callback or a
     *  later tick, and pass to {@link #fenced}. */
    public static int generation() {
        return generation;
    }

    /**
     * Wraps {@code step} so it runs only if no disconnect has come since the run read
     * {@code generation} from {@link #generation()}. {@link #reset()} disarms what a run has
     * armed, but cannot reach a step still in flight: one that lands after it would arm the
     * searcher or the opener again in a dead or new session, and its stop or finish would free
     * the gate of whatever run the player starts next.
     */
    public static Runnable fenced(int generation, Runnable step) {
        return () -> {
            if (generation == DistributeRuns.generation) {
                step.run();
            } else {
                VetsLogger.debug("DistributeRuns: dropped a step from before a disconnect");
            }
        };
    }

    /**
     * Ends the run with a yellow {@code <Noun> stopped: <reason>.}, and cancels every
     * queued run. The caller has already disarmed whatever it armed, and must not run its
     * completion callback.
     *
     * @param reason lower case, without a final full stop, e.g. {@code "the Members menu is not
     *     open"}
     */
    public static void stop(String reason) {
        VetsLogger.debug("DistributeRuns: stopped ({})", reason);
        ChatUtils.sendLocalMessage(
                Component.literal(stopLine(currentNoun, reason)).withStyle(ChatFormatting.YELLOW));
        List<String> cancelled = queued.stream().map(Queued::noun).toList();
        queued.clear();
        running = false;
        if (!cancelled.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal(cancelledLine(cancelled)).withStyle(ChatFormatting.YELLOW));
        }
    }

    /** The gray line for a run queued behind the run named {@code currentNoun}. */
    // Package-private for unit tests. See DistributeRunsTest.
    static String queuedLine(String currentNoun) {
        return "Queued: starts when the current " + currentNoun + " finishes.";
    }

    /** The yellow line for the run named {@code noun} ending in {@link #stop}. */
    // Package-private for unit tests. See DistributeRunsTest.
    static String stopLine(String noun, String reason) {
        return StringUtils.capitalize(noun) + " stopped: " + reason + ".";
    }

    /**
     * The yellow line for the queued runs a stop cancelled, {@code nouns} being non-empty: their
     * shared noun, pluralised, or "runs" when they differ.
     */
    // Package-private for unit tests. See DistributeRunsTest.
    static String cancelledLine(List<String> nouns) {
        int count = nouns.size();
        String noun = nouns.stream().distinct().count() == 1 ? nouns.get(0) : "run";
        return "Cancelled " + count + " queued " + noun + (count == 1 ? "." : "s.");
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
     * Ends everything silently when the client disconnects (called by
     * {@link org.wynnvets.listeners.ServerConnectionListener ServerConnectionListener}): drops
     * the queue, disarms the searcher, both walkers, the presser and the opener without running
     * their callbacks (the opener also drops a {@code /guild manage} still waiting in Wynntils'
     * queue, which outlives a disconnect), frees the run gate, and bumps the count every
     * {@link #fenced} step checks, so a step still in flight does nothing when it lands. So
     * nothing of a run outlives a relog: no stale watchdog or late fetch can stop or drive the
     * next session's run, and a run that never ended cannot hold the gate.
     */
    public static void reset() {
        if (running || !queued.isEmpty()) {
            VetsLogger.debug(
                    "DistributeRuns: reset on disconnect, {} queued dropped", queued.size());
        }
        queued.clear();
        running = false;
        currentNoun = null;
        generation++;
        MembersListSearcher.cancel();
        MembersListWalker.cancel();
        GuildLogWalker.cancel();
        MemberSlotPresser.cancel();
        GuildManageOpener.cancel();
    }
}
