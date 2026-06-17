package org.wynnvets.mwe.anni.mode;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;

/**
 * Belt-and-braces detector for the vanilla {@code /stream} mode, used
 * alongside Wynntils' {@code Models.StreamerMode.isInStream()}.
 *
 * <p>Wynntils reads the streamer-mode boss-bar packet to track state.
 * In the rare window where that signal lags the chat acknowledgement
 * (or Wynntils' bar matcher misses due to API drift), this detector
 * keeps us honest by watching the chat lines Wynncraft prints when
 * the user toggles {@code /stream}. The two detectors are OR'd inside
 * {@link AnniModeManager#transitionTo(AnniMode, AnniModeManager.Source)},
 * so a positive on either refuses passive/aggressive activation.</p>
 *
 * <p>Chat-line patterns (substring matched against
 * {@code Component.getString()} after format stripping):
 * <ul>
 *   <li>Stream-on: {@code "Streamer mode was enabled"} —
 *       full template starts with {@code &a&{fr:cp}…&{fr:d}}.</li>
 *   <li>Stream-off: {@code "Streamer mode disabled"} (note: no
 *       "was", per the actual chat output).</li>
 * </ul>
 *
 * <p>On stream-on we additionally call
 * {@link AnniModeManager#transitionTo} to flip out of any active
 * passive/aggressive mode — without this, an existing synthetic boss
 * bar would collide with Wynntils' {@code StreamerModeBar}. Stream-off
 * does NOT auto-restore the previous mode; the user re-enables manually.</p>
 *
 * <p>Hooked into the chat pipeline via a one-line call from
 * {@link org.wynnvets.mixin.client.chat.ChatLogMixin}.</p>
 */
public final class StreamerModeChatDetector {

    private static final String PATTERN_ON  = "Streamer mode was enabled";
    private static final String PATTERN_OFF = "Streamer mode disabled";

    private static volatile boolean registered = false;
    private static volatile boolean lastSeenInStream = false;

    private StreamerModeChatDetector() {
    }

    /** Idempotent registration. No-op today — the {@link ChatUtils}-style
     *  invocation lives at the call site (ChatLogMixin), so this exists
     *  only for API symmetry with other {@code mwe.anni} singletons. */
    public static void register() {
        if (registered) return;
        registered = true;
        VetsLogger.debug("StreamerModeChatDetector registered");
    }

    /** Current best-guess of {@code /stream} state from the chat
     *  pipeline. {@code false} on cold start (no chat lines seen yet);
     *  flips on the first detected toggle. */
    public static boolean lastSeenInStream() {
        return lastSeenInStream;
    }

    /**
     * Observe an incoming chat line. Safe to call on any thread (chat
     * pipeline runs on the render thread, but we only touch a volatile
     * flag and call back into singletons that tolerate it).
     *
     * <p>Called from {@code ChatLogMixin}'s {@code addMessage} HEAD
     * inject — the same hook used for guild detection. Errors are
     * swallowed (logged at debug) so a malformed chat line never
     * propagates into the chat pipeline.</p>
     */
    public static void observe(Component message) {
        if (message == null) return;
        String text;
        try {
            text = message.getString();
        } catch (Exception e) {
            VetsLogger.debug("StreamerModeChatDetector: getString failed: {}", e.getMessage());
            return;
        }
        if (text == null || text.isEmpty()) return;

        if (text.contains(PATTERN_ON)) {
            handleStreamOn();
        } else if (text.contains(PATTERN_OFF)) {
            handleStreamOff();
        }
    }

    private static void handleStreamOn() {
        boolean was = lastSeenInStream;
        lastSeenInStream = true;
        if (was) return;
        // Only auto-flip when there's actually something to flip out
        // of — silent users get no spurious chat noise on /stream.
        AnniMode current = AnniMode.fromConfig();
        if (current == AnniMode.SILENT) {
            VetsLogger.debug("StreamerModeChatDetector: stream-on detected (already silent)");
            return;
        }
        boolean flipped = AnniModeManager.transitionTo(
                AnniMode.SILENT, AnniModeManager.Source.AUTO_STREAM_ACTIVATED);
        if (flipped) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Anni mode auto-changed to ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("silent")
                                    .withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(": ")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal("/stream activated")
                                    .withStyle(ChatFormatting.RED))
                            .append(Component.literal(". ")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal("/stream")
                                    .withStyle(ChatFormatting.RED))
                            .append(Component.literal(" does the same thing as ")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal("/toggle ghosts off")
                                    .withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(" but disables anni mode and makes things a nuisance for organisers. Prefer ")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(Component.literal("/toggle ghosts off")
                                    .withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(".")
                                    .withStyle(ChatFormatting.GRAY)));
        }
    }

    private static void handleStreamOff() {
        if (!lastSeenInStream) return;
        lastSeenInStream = false;
        VetsLogger.debug("StreamerModeChatDetector: stream-off detected (no auto-restore)");
    }
}
