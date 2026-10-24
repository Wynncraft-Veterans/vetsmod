package org.wynnvets.guild;

import com.wynntils.utils.mc.McUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.mode.AnniModeManager;

/**
 * Manages the player's vetsmod authentication state.
 *
 * <p>Replaces the legacy SHA-256 password unlock with a per-user bearer key
 * issued by dazebot via the Discord {@code /vetsmod} command. The key is
 * persisted in {@link VetsConfig} and sent in an {@code auth} frame each time the
 * inbound WebSocket (re)connects; the outbound socket never gets one
 * ({@code outbound-socket-never-authenticated}).</p>
 *
 * <p>Tier values follow the canonical vetsmod tier vocabulary:
 * {@code member}, {@code waitlist}, {@code honourary}, {@code other}. A tier unlocks only
 * through an ok auth ack this session; the old system's on-disk unlock timestamps are
 * gone, and an old {@code config.json}'s copies are ignored and dropped at the next
 * save.</p>
 *
 * <p>This class is package-private. Callers outside the package reach it through
 * {@link GuildStateManager}'s facade methods; within the package,
 * {@link SessionAuthWarning} also reads {@link #lastAuthFailureReason()} and
 * {@link #keyRejectedByAuthAck()} directly, {@link ReturnersReauth} reads the key and tier
 * state and calls {@link #reauth(boolean)}, and {@link GuildLifecycle} calls {@link #reset()}
 * directly.</p>
 */
final class UnlockManager {

    /** Canonical tier strings — kept in sync with dazebot's lib/staff/verify_keys.py. */
    static final String TIER_MEMBER = "member";

    static final String TIER_WAITLIST = "waitlist";
    static final String TIER_HONOURARY = "honourary";
    static final String TIER_OTHER = "other";

    /** Minimum length for a vetsmod auth key. dazebot issues 43 chars (32 bytes
     *  base64url) — reject anything shorter as an obvious paste error. */
    private static final int MIN_KEY_LENGTH = 32;

    /** Maximum sane length. dazebot's 43-char tokens leave plenty of headroom;
     *  this guards against pasted gibberish that exceeds Minecraft's chat. */
    private static final int MAX_KEY_LENGTH = 200;

    // ── Transient session state ────────────────────────────────────────

    /** Tier from the last successful auth-frame response from the server. Empty
     *  until the session authenticates, and cleared again by an auth failure, a
     *  newly stored key, or disconnect/reset. */
    private static volatile String currentTier = "";

    /** {@code true} once an {@code auth} frame has come back {@code ok}
     *  during the current session. Cleared by an auth failure, a newly stored
     *  key, or disconnect/reset. */
    private static volatile boolean authVerifiedThisSession = false;

    /** Standing auth-failure reason from the server (e.g. "revoked",
     *  "unknown key"), shown to the user on world join when relevant. Cleared by
     *  a later ok auth ack, a newly stored key, or disconnect/reset. It can also be
     *  an "Authentication required" refusal of a non-auth frame. */
    private static volatile String lastAuthFailureReason = "";

    /** {@code true} when the standing failure is a rejection of the key by an auth reply
     *  ({@code "auth rejected: <reason>"}), as opposed to an "Authentication required" refusal
     *  of some other frame. Cleared with {@link #lastAuthFailureReason}. */
    private static volatile boolean keyRejectedByAuthAck = false;

    /** {@code true} when the user has just run {@code /unlock} and is awaiting
     *  the resulting auth-frame ack. Forces the next success ack to render its
     *  confirmation message regardless of {@link VetsConfigKeys#PRINT_SUCCESSFUL_AUTH}
     *  so the player always sees the result of their attempt. Cleared only by
     *  the failure handler and by the success line actually showing, so it
     *  survives a disconnect: a key typed off Wynncraft first goes out at the
     *  next Wynncraft join. */
    private static volatile boolean pendingUnlockNotify = false;

    /** {@code true} while a quiet {@link #reauth(boolean)} awaits its ack: a
     *  {@linkplain ReturnersReauth#isTransient transient} failure of that frame is logged, not
     *  shown in chat, and not counted as a rejection of the key. Cleared by the next ack either
     *  way, by a newly stored key and by reset. */
    private static volatile boolean quietReauth = false;

    private UnlockManager() {}

    // ── Queries ────────────────────────────────────────────────────────

    /** @return {@code true} if a key is stored locally (not necessarily valid) */
    static boolean hasStoredKey() {
        String key = VetsConfig.getString(VetsConfigKeys.VETS_AUTH_KEY);
        return key != null && !key.isEmpty();
    }

    /** @return the tier from the latest ok auth ack, or empty when none is standing (not
     *  yet authenticated, or cleared by an auth failure, a newly stored key or a disconnect) */
    static String currentTier() {
        return currentTier;
    }

    /** @return {@code true} from an ok auth ack until an auth failure, a
     *  newly stored key, or a disconnect clears it */
    static boolean isAuthenticatedThisSession() {
        return authVerifiedThisSession;
    }

    static String lastAuthFailureReason() {
        return lastAuthFailureReason;
    }

    /** Whether the standing auth failure ({@link #lastAuthFailureReason}) is a rejection of
     *  the stored key by an auth reply; a later refusal of some other frame replaces it, and
     *  clears this. See {@link #keyRejectedByAuthAck}. */
    static boolean keyRejectedByAuthAck() {
        return keyRejectedByAuthAck;
    }

    /** @return {@code true} if the player has unlocked as a waitlist member */
    static boolean isWaitlistUnlocked() {
        return authVerifiedThisSession && TIER_WAITLIST.equals(currentTier);
    }

    /** @return {@code true} if the player has unlocked as an honourary member */
    static boolean isHonouraryUnlocked() {
        return authVerifiedThisSession && TIER_HONOURARY.equals(currentTier);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /** Called by {@link GuildStateManager#loadPersistedState()}. Nothing
     *  transient is restored across mod restarts — auth happens fresh on each
     *  connection. */
    static void loadPersistedState() {
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = "";
        keyRejectedByAuthAck = false;
    }

    /** Reset transient session state on disconnect. The persisted key remains, and so does
     *  {@link #pendingUnlockNotify}, so a key stored off Wynncraft still shows its result. */
    static void reset() {
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = "";
        keyRejectedByAuthAck = false;
        quietReauth = false;
    }

    // ── Unlock command ────────────────────────────────────────────────

    /**
     * Store a new authentication key and immediately try to verify it.
     *
     * <p>The direct caller is {@link GuildStateManager#tryUnlock(String)}, which
     * is what {@link org.wynnvets.mixin.client.command.UnlockCommandMixin} invokes
     * when the user runs {@code /unlock &lt;key&gt;}; the mixin's code never calls this
     * class. The key is persisted to
     * {@link VetsConfig} and passed to {@link V1ApiManager#sendAuth(String)}, which sends
     * the {@code auth} frame on the inbound WebSocket if it is up and otherwise leaves the
     * stored key to go out on the next inbound (re)connect. The result is reported to the
     * user asynchronously when
     * {@link org.wynnvets.api.InboundAckRouter InboundAckRouter} routes the auth-frame ack.</p>
     *
     * @param key the bearer token typed by the user
     * @return {@link GuildStateManager.UnlockAttemptResult} describing what happened locally
     *         (the network round-trip is reported separately when it
     *         resolves)
     */
    static GuildStateManager.UnlockAttemptResult tryUnlock(String key) {
        if (key == null || key.isEmpty()) {
            return GuildStateManager.UnlockAttemptResult.MISSING_KEY;
        }
        // Cheap shape check: keys are URL-safe base64 (a-z A-Z 0-9 _ -).
        // Reject anything obviously not a key so a typo doesn't get persisted.
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            return GuildStateManager.UnlockAttemptResult.MALFORMED;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '_'
                            || c == '-';
            if (!ok) {
                return GuildStateManager.UnlockAttemptResult.MALFORMED;
            }
        }

        VetsConfig.setString(VetsConfigKeys.VETS_AUTH_KEY, key);
        // Clear stale tier/verified state — the server's response will
        // populate them. Until that lands the session is "key stored,
        // verifying...".
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = "";
        keyRejectedByAuthAck = false;
        // Force the next auth ack to surface its result to the user, even if
        // printSuccessfulAuth is latched off, and never let a quiet re-auth swallow it.
        pendingUnlockNotify = true;
        quietReauth = false;

        // Push the new key out immediately so the user sees a result this
        // session rather than only on the next reconnect. V1ApiManager will
        // also re-auth on every reconnect using the persisted key.
        V1ApiManager.sendAuth(key);
        VetsLogger.debug(
                "Auth key stored ({}…), auth frame dispatched",
                key.length() >= 6 ? key.substring(0, 6) : key);

        return GuildStateManager.UnlockAttemptResult.STORED_VERIFYING;
    }

    /**
     * Re-sends the stored key's auth frame, for {@link ReturnersReauth} after a mid-session
     * Returners join. Does nothing without a stored key or a live inbound socket. With
     * {@code quiet}, a {@linkplain ReturnersReauth#isTransient transient} failure of this frame
     * stays out of chat (see {@link #onAuthFailure}); a definitive rejection is still shown. It is
     * never quiet while an {@code /unlock} result is pending ({@link #pendingUnlockNotify}).
     * A success goes through {@link #onAuthSuccess} as any ack does.
     */
    static void reauth(boolean quiet) {
        String key = VetsConfig.getString(VetsConfigKeys.VETS_AUTH_KEY);
        if (key == null || key.isEmpty() || !V1ApiManager.isInboundConnected()) {
            return;
        }
        // Never quiet while an /unlock result is pending: this frame's quiet flag would
        // otherwise also cover that one's ack.
        quietReauth = quiet && !pendingUnlockNotify;
        V1ApiManager.sendAuth(key);
    }

    // ── Auth-frame response handlers (direct caller: GuildStateManager) ──────────

    /** Called when the server returns {@code {"status":"ok", "tier":...}}
     *  in response to our auth frame. */
    static void onAuthSuccess(String tier) {
        currentTier = tier == null ? TIER_OTHER : tier;
        authVerifiedThisSession = true;
        lastAuthFailureReason = "";
        keyRejectedByAuthAck = false;
        quietReauth = false;
        VetsLogger.info("Auth verified by server: tier={}", currentTier);
        // The ack arrives on the WebSocket thread. The state above is plain
        // volatile writes; the toast, the config latch, the registration
        // refresh and the anni-mode default belong on the client thread.
        McUtils.mc().execute(UnlockManager::showAuthSuccess);
    }

    /** The client-thread half of {@link #onAuthSuccess}: the confirmation, its latch, the
     *  registration refresh and the anni-mode default
     *  ({@code AnniModeManager.applyStartupDefaultIfNeeded}). {@link #pendingUnlockNotify} is
     *  consumed only when the line is
     *  shown, so an ack that lands before there is a player leaves it for the next one. A line
     *  that answers {@code /unlock} always goes to chat; a config-driven one goes to the action
     *  bar unless a screen is open. */
    private static void showAuthSuccess() {
        boolean configWantsPrint = VetsConfig.get(VetsConfigKeys.PRINT_SUCCESSFUL_AUTH);
        LocalPlayer player = McUtils.player();
        if (player != null && (pendingUnlockNotify || configWantsPrint)) {
            boolean answersUnlock = pendingUnlockNotify;
            pendingUnlockNotify = false;
            Component msg =
                    Component.literal("Authentication verified - tier: " + currentTier)
                            .withStyle(ChatFormatting.GREEN);
            if (answersUnlock || McUtils.screen() != null) {
                // An /unlock answer goes to chat, beside the "Saved your key" line it
                // answers and like the failure line: on Wynncraft the action bar is
                // redrawn with the health and mana bar at once, so a toast there is
                // gone before it can be read. The action-bar overlay is also
                // suppressed while a Screen is open (e.g. Wynncraft's class-selection
                // screen shown on login), but chat scrollback still renders through
                // the screen, so a config-driven line falls back to chat then too.
                ChatUtils.sendLocalMessage(msg);
            } else {
                player.displayClientMessage(msg, true);
            }
            // Latch off so the message doesn't re-fire on every WS reconnect.
            // onAuthFailure() flips it back on so a transient error→recovery
            // re-shows the confirmation. Only do this when the config was
            // what triggered the display — a /unlock-driven render shouldn't
            // overwrite the user's standing preference.
            if (configWantsPrint) {
                VetsConfig.set(VetsConfigKeys.PRINT_SUCCESSFUL_AUTH, false);
            }
        }

        // Refresh presence registration so the server's connected_users
        // list reflects our (server-confirmed) tier rather than whatever
        // we inferred locally.
        GuildStateManager.sendRegistrationIfReady();
        // The ack may have changed the tier, and with it enrichment eligibility:
        // re-derive the default anni mode for a still-unset user, as a world join does.
        AnniModeManager.applyStartupDefaultIfNeeded();
    }

    /** Called (through {@link GuildStateManager#onAuthFailure(String)}) when an error ack that
     *  is not staff-action-shaped arrives while an auth ack is awaited, or carries an "auth
     *  rejected" / "Authentication required" detail while no staff-action callback is pending;
     *  typically {@code {"status":"error", "detail":"auth rejected: <reason>"}}.
     *
     *  <p>The server ends the session on a failed frame, so the state is cleared either way. A
     *  {@linkplain ReturnersReauth#isTransient transient} failure of a quiet {@link #reauth}
     *  stops after the log line: no chat line, no re-armed latch, and no key rejection, so
     *  {@link ReturnersReauth}'s retry still runs.</p> */
    static void onAuthFailure(String detail) {
        boolean quiet = quietReauth && ReturnersReauth.isTransient(detail);
        quietReauth = false;
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = detail == null ? "unknown" : detail;
        // temporary-server's auth handler answers a bad key with "auth rejected: <reason>";
        // any other detail is not a verdict on the key, and nor is a quiet re-auth's
        // transient one.
        keyRejectedByAuthAck = !quiet && detail != null && detail.startsWith("auth rejected");
        // The failure path renders its own message below, so the /unlock notify
        // request is satisfied by that. Only a quiet re-auth skips it, and reauth is
        // never quiet while an /unlock result is pending, so that result is never the
        // quiet one.
        pendingUnlockNotify = false;
        VetsLogger.warn("Auth rejected by server: {}", lastAuthFailureReason);
        if (quiet) {
            VetsLogger.warn("Returners re-auth failed transiently; its retry will try again");
            return;
        }

        // Re-arm the success notification so the next error→recovery cycle
        // surfaces a fresh "verified" confirmation rather than staying silent.
        VetsConfig.set(VetsConfigKeys.PRINT_SUCCESSFUL_AUTH, true);

        // Hop to the client thread for the chat line, as the success path does.
        String reason = lastAuthFailureReason;
        McUtils.mc()
                .execute(
                        () ->
                                ChatUtils.sendLocalMessage(
                                        Component.literal(
                                                        "Authentication failed: "
                                                                + reason
                                                                + ". Try `~vetsmod` in"
                                                                + " #bot-commands at"
                                                                + " https://wynnvets.org/discord"
                                                                + " to issue a new key?")
                                                .withStyle(ChatFormatting.RED)));
    }
}
