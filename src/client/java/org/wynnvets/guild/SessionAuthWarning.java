package org.wynnvets.guild;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.TickDelays;

/**
 * Displays a single per-session warning in chat when the player's vetsmod
 * authentication state diverges from what they probably expect.
 *
 * <p>Four cases drive the wording:</p>
 *
 * <ol>
 *   <li><b>Authenticated</b> — no warning.</li>
 *   <li><b>Has stored key, not verified</b> — if an auth reply rejected it, a red "your key
 *       was rejected, run ~vetsmod" warning regardless of unauth toggle; if the vets API
 *       socket is down, a yellow "couldn't verify your key" line instead; if it is up but no
 *       verdict has arrived, nothing, since the auth reply prints its own line when it
 *       lands.</li>
 *   <li><b>No stored key, in Returners</b> — show one of
 *       two warnings depending on whether unauth chat is currently allowed
 *       (server tells us via a {@code server_info} frame on outbound
 *       connect; default-true assumption while we wait for that frame).</li>
 *   <li><b>No stored key, not in Returners</b> — no warning. They'd just see noise.</li>
 * </ol>
 *
 * <p>The warning fires once per session, after a short delay so the
 * outbound {@code server_info} frame and the inbound auth round-trip have
 * a chance to land first.</p>
 */
public final class SessionAuthWarning {

    /** Server-reported "unauth chat is currently allowed" toggle. Defaults
     *  to true so first-session clients (before the server_info frame
     *  lands) get the gentler "this will be turned off soon" copy rather
     *  than the more alarming "you can't talk" copy. Updated to the real
     *  value as soon as the server pushes the frame. */
    private static volatile boolean unauthEnabled = true;

    /** Once-per-session latch. Reset by {@link GuildLifecycle#reset()}. */
    private static final AtomicBoolean firedThisSession = new AtomicBoolean(false);

    /** Bumped by every {@link #reset()}, so a warning scheduled before a disconnect cannot
     *  fire into the next session's state. */
    private static final AtomicInteger generation = new AtomicInteger();

    /** Discord URL used in warning copy — same one used elsewhere in the mod. */
    public static final String DISCORD_INVITE_URL = "https://wynnvets.org/discord";

    /** How long after world join we wait before showing the warning, giving
     *  the auth round-trip + server_info frame a chance to land. */
    private static final long WARNING_DELAY_MS = 5_000L;

    private SessionAuthWarning() {}

    /**
     * Update the cached unauth toggle from a server_info frame.
     * Called by {@link org.wynnvets.api.V1ApiManager}'s outbound listener.
     */
    public static void onServerInfo(boolean unauthEnabled) {
        SessionAuthWarning.unauthEnabled = unauthEnabled;
        VetsLogger.debug("server_info: unauth_enabled={}", unauthEnabled);
    }

    /** Forget all transient session state (called on disconnect). */
    public static void reset() {
        firedThisSession.set(false);
        unauthEnabled = true;
        generation.incrementAndGet();
    }

    /**
     * Schedule the once-per-session warning to fire after a short delay.
     * Safe to call multiple times — only the first call wins.
     */
    public static void scheduleOnceForThisSession() {
        if (!firedThisSession.compareAndSet(false, true)) {
            return;
        }
        final int myGeneration = generation.get();
        TickDelays.runAfter(
                WARNING_DELAY_MS,
                "Session-auth warning",
                () -> {
                    if (myGeneration != generation.get()) return;
                    try {
                        fireWarningIfApplicable();
                    } catch (Exception e) {
                        VetsLogger.debug("SessionAuthWarning fire failed: {}", e.getMessage());
                    }
                });
    }

    private static void fireWarningIfApplicable() {
        // Case 1: authenticated this session — nothing to warn about.
        if (GuildStateManager.isAuthenticatedThisSession()) {
            return;
        }

        boolean hasKey = GuildStateManager.hasStoredAuthKey();
        boolean inReturners = GuildStateManager.isReturners();

        // Case 2: a stored key, not verified. Only an auth reply that rejected it
        // earns the "rejected" warning, shown regardless of unauth toggle — the user
        // explicitly tried to authenticate and failed, and the path to fix it is the
        // same in both modes. With the socket down nothing could verify it, so say
        // that instead; connected with no verdict yet, the auth reply will print
        // its own line when it lands.
        if (hasKey) {
            if (UnlockManager.keyRejectedByAuthAck()) {
                String reason = UnlockManager.lastAuthFailureReason();
                String reasonSuffix = reason.isEmpty() ? "" : " (" + reason + ")";
                ChatUtils.sendLocalMessage(
                        withDiscordLink(
                                "Your stored vetsmod key was rejected"
                                        + reasonSuffix
                                        + ". Run ~vetsmod in #bot-commands to issue a new one.",
                                ChatFormatting.RED));
            } else if (!V1ApiManager.isInboundConnected()) {
                ChatUtils.sendLocalMessage(
                        Component.literal(
                                        "Couldn't verify your vetsmod key: Unable to reach the"
                                                + " vets API, which may be down.")
                                .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        // Case 3: in Returners, no key. Wording branches on the server's
        // current unauth toggle.
        if (inReturners) {
            if (unauthEnabled) {
                ChatUtils.sendLocalMessage(
                        withDiscordLink(
                                "vetsmod is running unauthenticated. Vets chat still works "
                                        + "for now, but authentication will become mandatory soon. "
                                        + "Run ~vetsmod in #bot-commands to /unlock.",
                                ChatFormatting.YELLOW));
            } else {
                ChatUtils.sendLocalMessage(
                        withDiscordLink(
                                "You aren't authenticated. Vetsmod cannot send or receive "
                                        + "VETS chat or use guild-specific features until you "
                                        + "/unlock. Run ~vetsmod in #bot-commands to get a key.",
                                ChatFormatting.RED));
            }
            return;
        }

        // Case 4: not a VETS user. Stay quiet — the warning would be noise.
    }

    /**
     * {@code text} in {@code color}, followed by the clickable aqua {@link #DISCORD_INVITE_URL},
     * so users don't have to copy-paste it. Also used by {@code InviteGate}.
     */
    public static MutableComponent withDiscordLink(String text, ChatFormatting color) {
        MutableComponent body = Component.literal(text).withStyle(color);
        MutableComponent link =
                Component.literal(" [" + DISCORD_INVITE_URL + "]")
                        .setStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.AQUA)
                                        .withUnderlined(true)
                                        .withClickEvent(
                                                new ClickEvent.OpenUrl(
                                                        java.net.URI.create(DISCORD_INVITE_URL))));
        return body.append(link);
    }
}
