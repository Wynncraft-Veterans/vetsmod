package org.wynnvets.mwe.anni.mode;

import com.wynntils.core.components.Models;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;

/**
 * Source-of-truth for mode transitions: the single chokepoint that
 * enforces the {@code /stream} mutex (spec §3.1) and prints user-facing
 * feedback before the underlying {@link VetsConfig#VETS_ANNI_MODE}
 * string lands.
 *
 * <p>The boss-bar and outline subsystems read the current mode via
 * {@link AnniMode#fromConfig()} (or via this manager's
 * {@link #current()} shim, which delegates). They do NOT write directly
 * to {@link VetsConfig#VETS_ANNI_MODE} — every write goes through
 * {@link #transitionTo(AnniMode, Source)} so the mutex stays honest.</p>
 *
 * <p>Two consumers will move the mode without user input: the
 * {@link AnniWindowWatcher} resets to silent at T+30 m (already shipped
 * in S2 — it writes the config directly because the watcher predates
 * this manager; that path is allowed because window-close is a
 * non-controversial cleanup that needs no mutex check), and
 * {@link StreamerModeChatDetector} auto-flips to silent on a detected
 * stream-on chat line. The detector goes through this manager via
 * {@link Source#AUTO_STREAM_ACTIVATED} so the user sees a chat
 * notification.</p>
 */
public final class AnniModeManager {

    /** Why a transition was requested — controls feedback wording and
     *  the DEBUG bypass. */
    public enum Source {
        /** A {@code /wv anni <mode>} command from the user. */
        USER_COMMAND,
        /** {@link AnniWindowWatcher} closing the hot window. (Reserved
         *  — currently the watcher writes config directly; included so a
         *  future refactor can route through here.) */
        AUTO_WINDOW_CLOSE,
        /** {@link StreamerModeChatDetector} observed a stream-on line. */
        AUTO_STREAM_ACTIVATED,
        /** {@code /wv debug tree anni mode set …} — bypasses the
         *  {@code /stream} mutex so we can test passive/aggressive
         *  rendering even while screen-recording a debug session. */
        DEBUG_BYPASS_MUTEX,
    }

    private static volatile boolean registered = false;

    private AnniModeManager() {
    }

    /** Idempotent registration hook. No-op today; kept for symmetry with
     *  the rest of the {@code mwe.anni} package and so future wiring
     *  (e.g. snapshot listener) has an obvious entry point. */
    public static void register() {
        if (registered) return;
        registered = true;
        VetsLogger.debug("AnniModeManager registered");
    }

    /** The current mode, read fresh from {@link VetsConfig}. */
    public static AnniMode current() {
        return AnniMode.fromConfig();
    }

    /**
     * Attempt a mode transition.
     *
     * <p>Refused when the target is PASSIVE/AGGRESSIVE and either of the
     * stream detectors says we're streaming, unless the source is
     * {@link Source#DEBUG_BYPASS_MUTEX}. Refused transitions print the
     * spec's "stream is suboptimal — try /toggle ghosts NONE" guidance
     * and leave the config untouched.</p>
     *
     * <p>Successful transitions persist via {@link VetsConfig#setString}
     * and print a user-friendly confirmation. No-op transitions
     * (target equals current mode) still print confirmation so the
     * user sees their action acknowledged.</p>
     *
     * @return {@code true} if the config was written, {@code false} if
     *         the transition was refused (so the caller can branch on
     *         outcome — e.g. {@code /wv debug} prefers a quieter log
     *         line on success).
     */
    public static boolean transitionTo(AnniMode target, Source source) {
        if (target == null) return false;
        AnniMode previous = current();
        boolean wantsActive = target != AnniMode.SILENT;
        boolean streamActive = wantsActive
                && source != Source.DEBUG_BYPASS_MUTEX
                && isInStream();
        if (streamActive) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Anni mode change refused: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("/stream is active")
                                    .withStyle(ChatFormatting.RED))
                            .append(Component.literal(". Stream is suboptimal for anni — try ")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal("/toggle ghosts NONE")
                                    .withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(" instead.")
                                    .withStyle(ChatFormatting.GRAY)));
            return false;
        }

        VetsConfig.setString(VetsConfig.VETS_ANNI_MODE, target.toConfigValue());

        // Suppress the user-facing confirmation for AUTO_STREAM_ACTIVATED;
        // the detector prints its own contextual message ("auto-changed
        // to silent: /stream activated") which is friendlier than the
        // generic "Anni mode: silent (was passive)" line.
        if (source == Source.AUTO_STREAM_ACTIVATED) {
            VetsLogger.debug("Anni mode auto-changed {} -> {} (stream activated)",
                    previous.toConfigValue(), target.toConfigValue());
            return true;
        }
        ChatUtils.sendLocalMessage(
                Component.literal("Anni mode: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(target.toConfigValue())
                                .withStyle(modeColor(target)))
                        .append(Component.literal(" (was ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(previous.toConfigValue())
                                .withStyle(modeColor(previous)))
                        .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY)));
        return true;
    }

    /** {@code true} if either Wynntils' streamer-mode signal OR our
     *  chat-line backup detector indicates the user is streaming. */
    private static boolean isInStream() {
        return Models.StreamerMode.isInStream() || StreamerModeChatDetector.lastSeenInStream();
    }

    private static ChatFormatting modeColor(AnniMode mode) {
        switch (mode) {
            case PASSIVE:    return ChatFormatting.GREEN;
            case AGGRESSIVE: return ChatFormatting.RED;
            case SILENT:
            default:         return ChatFormatting.WHITE;
        }
    }
}
