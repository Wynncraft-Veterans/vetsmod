package org.wynnvets.distribute;

import com.wynntils.core.components.Managers;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Implements {@code /wv distribute @split <resource> <count>}: divides
 * {@code <count>} three ways and chains the existing pool dispatchers
 * &mdash; {@link RandomDistributor}, {@link GraidsDistributor},
 * {@link ObjectivesDistributor} &mdash; one after the other, each with
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
 * <p>Phases run in a fixed sequence: {@code @random} &rarr;
 * {@code @graids} &rarr; {@code @objectives}. The ordering is fixed
 * rather than randomised because each phase opens its own menu and
 * the third phase ({@code @objectives}) can leave the Members menu
 * scrolled to a useful page for the operator to inspect afterwards.
 * The choice of which pool gets which third <em>is</em> randomised
 * via the remainder shuffle above, so the operator can't game the
 * split.</p>
 *
 * <h2>Pool failures</h2>
 * <p>Each underlying dispatcher invokes its {@code onComplete}
 * callback on every exit path, so a pool that finds no recipients
 * (empty graid log, no objective completers) doesn't stall the chain.
 * The next phase starts as soon as the previous reports done.</p>
 *
 * <h2>Skipping zero-count phases</h2>
 * <p>For very small {@code count} values (e.g. {@code 1} or {@code 2}),
 * some pools end up with 0 rewards. Those phases are skipped entirely
 * &mdash; no menu opens just to send nothing &mdash; by pre-collapsing
 * them at chain-build time.</p>
 */
public final class SplitDistributor {

    private static final Random RNG = new Random();

    /** Settle gap between phases. Belt-and-braces on top of
     *  {@code Handlers.Command.queueCommand}'s built-in 7-tick command
     *  rate limit: queueCommand paces consecutive commands but doesn't
     *  account for the {@code ServerboundContainerClosePacket} that
     *  each phase fires when its menu finishes. A short scheduler
     *  delay between phases gives the server time to settle the close
     *  before the next phase's menu-open command flies. */
    private static final int PHASE_DELAY_TICKS = 10;

    private SplitDistributor() {}

    public static void dispatch(int count, MemberDistributor.Resource resource) {
        int[] pools = splitCount(count);

        ChatUtils.sendLocalMessage(
                Component.literal("Splitting " + count + "x " + resource.displayName()
                        + ": " + pools[0] + " @random, "
                        + pools[1] + " @graids, "
                        + pools[2] + " @objectives")
                        .withStyle(ChatFormatting.AQUA));

        // Chain backwards so each phase's onComplete closure already
        // knows the next phase to run. Pools with count == 0 collapse
        // into their successor's runnable directly, avoiding a wasted
        // menu open. Every phase transition is gated by a settle delay
        // (see PHASE_DELAY_TICKS) so the previous menu's close packet
        // has been processed before the next command flies.
        final Runnable terminal = () -> ChatUtils.sendLocalMessage(
                Component.literal("Split distribution complete.")
                        .withStyle(ChatFormatting.GREEN));

        final Runnable objectivesPhase = pools[2] > 0
                ? delayed(() -> ObjectivesDistributor.dispatch(pools[2], resource, terminal))
                : terminal;

        final Runnable graidsPhase = pools[1] > 0
                ? delayed(() -> GraidsDistributor.dispatch(pools[1], resource, objectivesPhase))
                : objectivesPhase;

        final Runnable randomPhase = pools[0] > 0
                ? () -> RandomDistributor.dispatch(pools[0], resource, graidsPhase)
                : graidsPhase;

        randomPhase.run();
    }

    /** Wraps {@code body} in a {@link #PHASE_DELAY_TICKS}-tick scheduler
     *  call so the previous phase's screen close has time to drain
     *  before the next phase fires its opening command. */
    private static Runnable delayed(Runnable body) {
        return () -> Managers.TickScheduler.scheduleLater(body, PHASE_DELAY_TICKS);
    }

    /**
     * Splits {@code count} into three pools — [@random, @graids,
     * @objectives] — each getting {@code count / 3} as a floor and
     * the {@code count % 3} remainder awarded to a random subset.
     */
    private static int[] splitCount(int count) {
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
