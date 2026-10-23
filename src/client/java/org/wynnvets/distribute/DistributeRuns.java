package org.wynnvets.distribute;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;

/**
 * How a {@code /wv distribute} run stops when it cannot go on.
 *
 * <p>A run that loses the menu it drives &mdash; the player's Esc, a server close, a screen that
 * replaces it &mdash; stops as a whole. One yellow line names the reason, nothing it armed calls
 * back, and no green "complete" line follows, so {@code @split}'s later phases do not start
 * either. A recipient who cannot be found while the Members menu is still up is not a stop: the
 * run skips them and goes on. Neither is a head finding nobody to send to; that run completes as
 * usual. A run whose precondition fails is refused in red instead ({@link #refuse}).
 * {@code vetsmod_distribute.md} §7 lists which exit does which.</p>
 */
public final class DistributeRuns {

    private DistributeRuns() {}

    /**
     * Ends the run with a yellow {@code Distribution stopped: <reason>.}. The caller has already
     * disarmed whatever it armed, and must not run its completion callback.
     *
     * @param reason lower case, without a final full stop, e.g. {@code "the Members menu is not
     *     open"}
     */
    public static void stop(String reason) {
        VetsLogger.debug("DistributeRuns: stopped ({})", reason);
        ChatUtils.sendLocalMessage(
                Component.literal("Distribution stopped: " + reason + ".")
                        .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Ends the run with {@code message} in red: a precondition the run needs is not met, so
     * it sends nothing. As with {@link #stop}, the caller disarms first and runs no completion
     * callback.
     */
    public static void refuse(String message) {
        VetsLogger.debug("DistributeRuns: refused ({})", message);
        ChatUtils.sendLocalMessage(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
