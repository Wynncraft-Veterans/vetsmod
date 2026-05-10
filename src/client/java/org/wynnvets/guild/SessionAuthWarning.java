package org.wynnvets.guild;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Displays a single per-session warning in chat when the player's vetsmod
 * authentication state diverges from what they probably expect.
 *
 * <p>Four cases drive the wording:</p>
 *
 * <ol>
 *   <li><b>Authenticated</b> — no warning.</li>
 *   <li><b>Has stored key, server rejected it</b> — show a "your key was
 *       rejected, run /vetsmod" warning regardless of unauth toggle.</li>
 *   <li><b>No stored key, but plausibly a VETS user</b> (in Returners or has
 *       legacy unlock markers from the old SHA-256 system) — show one of
 *       two warnings depending on whether unauth chat is currently allowed
 *       (server tells us via a {@code server_info} frame on outbound
 *       connect; default-true assumption while we wait for that frame).</li>
 *   <li><b>Plain non-VETS player</b> — no warning. They'd just see noise.</li>
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

    /** Once-per-session latch. Reset by {@link GuildStateManager#reset()}. */
    private static final AtomicBoolean firedThisSession = new AtomicBoolean(false);

    /** Discord URL used in warning copy — same one used elsewhere in the mod. */
    private static final String DISCORD_INVITE_URL = "https://wynnvets.org/discord";

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
    }

    /**
     * Schedule the once-per-session warning to fire after a short delay.
     * Safe to call multiple times — only the first call wins.
     */
    public static void scheduleOnceForThisSession() {
        if (!firedThisSession.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(WARNING_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                fireWarningIfApplicable();
            } catch (Exception e) {
                VetsLogger.debug("SessionAuthWarning fire failed: {}", e.getMessage());
            }
        }, "vetsmod-session-auth-warning").start();
    }

    private static void fireWarningIfApplicable() {
        // Case 1: authenticated this session — nothing to warn about.
        if (GuildStateManager.isAuthenticatedThisSession()) {
            return;
        }

        boolean hasKey = GuildStateManager.hasStoredAuthKey();
        boolean inReturners = GuildStateManager.isReturners();
        boolean hasLegacy = GuildStateManager.hasLegacyPasswordUnlock();
        boolean plausibleVetsUser = inReturners || hasLegacy;

        // Case 2: stored key but server rejected it. Always show, regardless
        // of unauth toggle — the user explicitly tried to authenticate and
        // failed, and the path to fix it is the same in both modes.
        if (hasKey) {
            String reason = UnlockManager.lastAuthFailureReason();
            String reasonSuffix = reason.isEmpty() ? "" : " (" + reason + ")";
            ChatUtils.sendLocalMessage(makeWarning(
                "Your stored vetsmod key was rejected" + reasonSuffix
                    + ". Run /vetsmod in #bot-commands to issue a new one.",
                ChatFormatting.RED
            ));
            return;
        }

        // Case 3: plausibly a VETS user, no key. Wording branches on the
        // server's current unauth toggle.
        if (plausibleVetsUser) {
            if (unauthEnabled) {
                ChatUtils.sendLocalMessage(makeWarning(
                    "vetsmod is running unauthenticated. Vets chat still works "
                        + "for now, but authentication will become mandatory soon. "
                        + "Run /vetsmod in #bot-commands to /unlock.",
                    ChatFormatting.YELLOW
                ));
            } else {
                ChatUtils.sendLocalMessage(makeWarning(
                    "You aren't authenticated, so vetsmod cannot send or receive "
                        + "VETS chat or use guild-specific features until you "
                        + "/unlock. Run /vetsmod in #bot-commands to get a key.",
                    ChatFormatting.RED
                ));
            }
            return;
        }

        // Case 4: not a VETS user. Stay quiet — the warning would be noise.
    }

    private static MutableComponent makeWarning(String text, ChatFormatting color) {
        // Make the discord URL clickable so users don't have to copy-paste.
        MutableComponent body = Component.literal(text).withStyle(color);
        MutableComponent link = Component.literal(" [" + DISCORD_INVITE_URL + "]")
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(DISCORD_INVITE_URL))));
        return body.append(link);
    }
}
