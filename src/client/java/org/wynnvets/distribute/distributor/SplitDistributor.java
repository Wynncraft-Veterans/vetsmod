package org.wynnvets.distribute.distributor;

import com.wynntils.core.components.Managers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;

/**
 * Implements {@code /wv distribute @split <resource> <count>}: divides
 * {@code <count>} three ways and chains the existing pool dispatchers
 * &mdash; {@link GraidsDistributor}, {@link ObjectivesDistributor},
 * {@link RandomDistributor} &mdash; one after the other, each with
 * its third of the total.
 *
 * <h2>Split arithmetic</h2>
 * <p>Each pool gets {@code count / 3} as a floor. The remainder
 * ({@code count % 3}, either 0, 1, or 2) is distributed by shuffling
 * the three pool indices and bumping the first {@code remainder} by
 * +1, matching the "random modulo" convention used by
 * {@link ObjectivesDistributor} and {@link GraidsDistributor}.</p>
 *
 * <h2>Phase ordering</h2>
 * <p>Phases run in a fixed sequence: {@code @graids} &rarr;
 * {@code @objectives} &rarr; {@code @random}. {@code @graids} must
 * run first because it scans the guild log for raid completions, and
 * Wynncraft caps that log at ~100 most-recent entries &mdash;
 * distributing aspects (or anything else that emits log entries) first
 * would push graid records off the back of the window. Which pools
 * receive the {@code count % 3} remainder <em>is</em> randomised via the
 * shuffle above, so the operator can't choose them.</p>
 *
 * <h2>Pool failures</h2>
 * <p>Each underlying dispatcher invokes its {@code onComplete}
 * callback on its own no-recipient and roster-failure exits, so a pool
 * that finds no recipients (empty graid log, no objective completers)
 * doesn't stall the chain. The next phase starts
 * {@link #PHASE_DELAY_TICKS} after the previous reports done. A phase
 * can still end without reporting &mdash; {@code members-list-walker-drops-completion},
 * {@code member-slot-presser-drops-completion}, and the rows of
 * {@code vetsmod_distribute.md} §7 whose callback column says no
 * &mdash; and the chain stalls behind it.</p>
 *
 * <h2>Skipping zero-count phases</h2>
 * <p>For very small {@code count} values (e.g. {@code 1} or {@code 2}),
 * some pools end up with 0 rewards. Those phases are skipped entirely
 * &mdash; no menu opens just to send nothing &mdash; by pre-collapsing
 * them at chain-build time.</p>
 */
public final class SplitDistributor {

    private static final Random RNG = new Random();

    /** Settle gap. Belt-and-braces on top of
     *  {@code Handlers.Command.queueCommand}'s built-in 7-tick command
     *  spacing: a short scheduler delay before each objectives or random
     *  phase starts. A phase that ends on the Members menu gives it no
     *  close packet to wait for: it dismisses the menu client-side through
     *  {@link MemberSlotPresser#closeMembersScreen()}, which sends no
     *  {@code ServerboundContainerClosePacket} (see
     *  {@code close-members-screen-sends-no-close-packet}). */
    private static final int PHASE_DELAY_TICKS = 10;

    private SplitDistributor() {}

    public static void dispatch(int count, MemberSlotPresser.Resource resource) {
        int[] pools = splitCount(count);

        ChatUtils.sendLocalMessage(
                Component.literal(
                                "Splitting "
                                        + count
                                        + "x "
                                        + resource.displayName()
                                        + ": "
                                        + pools[0]
                                        + " @graids, "
                                        + pools[1]
                                        + " @objectives, "
                                        + pools[2]
                                        + " @random")
                        .withStyle(ChatFormatting.AQUA));

        // Chain backwards so each phase's onComplete closure already
        // knows the next phase to run. Pools with count == 0 collapse
        // into their successor's runnable directly, avoiding a wasted
        // menu open. Each non-empty objectives or random phase is wrapped
        // in a settle delay (see PHASE_DELAY_TICKS), even when an empty
        // graids pool makes it the first to run; the graids phase is not.
        final Runnable terminal =
                () ->
                        ChatUtils.sendLocalMessage(
                                Component.literal("Split distribution complete.")
                                        .withStyle(ChatFormatting.GREEN));

        final Runnable randomPhase =
                pools[2] > 0
                        ? delayed(() -> RandomDistributor.dispatch(pools[2], resource, terminal))
                        : terminal;

        final Runnable objectivesPhase =
                pools[1] > 0
                        ? delayed(
                                () ->
                                        ObjectivesDistributor.dispatch(
                                                pools[1], resource, randomPhase))
                        : randomPhase;

        final Runnable graidsPhase =
                pools[0] > 0
                        ? () -> GraidsDistributor.dispatch(pools[0], resource, objectivesPhase)
                        : objectivesPhase;

        graidsPhase.run();
    }

    /** Wraps {@code body} in a {@link #PHASE_DELAY_TICKS}-tick scheduler
     *  call, so the phase starts that long after it is invoked &mdash;
     *  normally from the previous phase's {@code onComplete}. */
    private static Runnable delayed(Runnable body) {
        return () -> Managers.TickScheduler.scheduleLater(body, PHASE_DELAY_TICKS);
    }

    /**
     * Splits {@code count} into three pools — [@graids, @objectives,
     * @random] — each getting {@code count / 3} as a floor and
     * the {@code count % 3} remainder awarded to a random subset.
     */
    // Package-private for unit tests. See SplitDistributorTest.
    static int[] splitCount(int count) {
        int base = count / 3;
        int remainder = count % 3;
        int[] pools = {base, base, base};
        if (remainder > 0) {
            List<Integer> indices = new ArrayList<>(List.of(0, 1, 2));
            Collections.shuffle(indices, RNG);
            for (int i = 0; i < remainder; i++) {
                pools[indices.get(i)] += 1;
            }
        }
        return pools;
    }
}
