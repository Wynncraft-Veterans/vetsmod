package org.wynnvets.guild;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;

/**
 * Manages the player's vetsmod authentication state.
 *
 * <p>Replaces the legacy SHA-256 password unlock with a per-user bearer key
 * issued by dazebot via the Discord {@code /vetsmod} command. The key is
 * persisted in {@link VetsConfig} and sent in an {@code auth} frame after
 * each WebSocket connection is established.</p>
 *
 * <p>The legacy {@code VETS_WAITLIST_UNLOCK_TIME} /
 * {@code VETS_HONOURARY_UNLOCK_TIME} fields are retained as a "previously
 * unlocked under the old system" marker so {@link GuildStateManager} can
 * decide whether to show a session-start nag warning encouraging the user
 * to migrate to the new {@code /unlock &lt;key&gt;} flow.</p>
 *
 * <p>Tier values follow the canonical vetsmod tier vocabulary:
 * {@code member}, {@code waitlist}, {@code honourary}, {@code other}.
 * The legacy unlock states map to {@code waitlist} / {@code honourary}
 * respectively for back-compat warning logic.</p>
 *
 * <p>This class is package-private and accessed exclusively through
 * {@link GuildStateManager}'s facade methods.</p>
 */
final class UnlockManager {

    /** Canonical tier strings — kept in sync with dazebot's lib/verify_keys.py. */
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

    /** Last successful auth-frame response from the server. Empty when
     *  the current session has not yet authenticated. */
    private static volatile String currentTier = "";

    /** {@code true} once an {@code auth} frame has come back {@code ok}
     *  during the current session. Cleared on disconnect/reset. */
    private static volatile boolean authVerifiedThisSession = false;

    /** Most recent auth failure reason from the server (e.g. "revoked",
     *  "unknown key"), shown to the user on world join when relevant. */
    private static volatile String lastAuthFailureReason = "";

    /** Debug-only override: pretend the user is guildless+unlocked. */
    private static boolean debugForceGuildlessUnlocked = false;

    private UnlockManager() {}

    // ── Queries ────────────────────────────────────────────────────────

    /** @return {@code true} if a key is stored locally (not necessarily valid) */
    static boolean hasStoredKey() {
        String key = VetsConfig.getString(VetsConfig.VETS_AUTH_KEY);
        return key != null && !key.isEmpty();
    }

    /** @return the stored key, or empty string if none */
    static String getStoredKey() {
        String key = VetsConfig.getString(VetsConfig.VETS_AUTH_KEY);
        return key == null ? "" : key;
    }

    /** @return the tier string from the most recent successful auth this session */
    static String currentTier() {
        return currentTier;
    }

    /** @return {@code true} once the server has accepted our auth this session */
    static boolean isAuthenticatedThisSession() {
        return authVerifiedThisSession;
    }

    static String lastAuthFailureReason() {
        return lastAuthFailureReason;
    }

    /** @return {@code true} if the player has unlocked as a waitlist member */
    static boolean isWaitlistUnlocked() {
        return debugForceGuildlessUnlocked
            || (authVerifiedThisSession && TIER_WAITLIST.equals(currentTier))
            || legacyWaitlistMarker();
    }

    /** @return {@code true} if the player has unlocked as an honourary member */
    static boolean isHonouraryUnlocked() {
        return (authVerifiedThisSession && TIER_HONOURARY.equals(currentTier))
            || legacyHonouraryMarker();
    }

    /** Whether legacy SHA-256-based waitlist unlock state is still on disk.
     *  Used only as a "this user used the old system" signal for warning copy. */
    static boolean legacyWaitlistMarker() {
        return VetsConfig.getLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME) > 0L;
    }

    /** See {@link #legacyWaitlistMarker()}. */
    static boolean legacyHonouraryMarker() {
        return VetsConfig.getLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME) > 0L;
    }

    /** @return {@code true} when the debug guildless+unlocked override is active */
    static boolean isDebugForceGuildlessUnlocked() {
        return debugForceGuildlessUnlocked;
    }

    /** Enables or disables the debug override that forces the user to be
     *  treated as guildless and unlocked. */
    static void setDebugForceGuildlessUnlocked(boolean enabled) {
        debugForceGuildlessUnlocked = enabled;
        VetsLogger.debug("Debug guildless+unlocked override: {}",
                enabled ? "enabled" : "disabled");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /** Called by {@link GuildStateManager#loadPersistedState()}. Nothing
     *  transient is restored across mod restarts — auth happens fresh on each
     *  connection. */
    static void loadPersistedState() {
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = "";
    }

    /** Reset transient session state on disconnect. The persisted key remains. */
    static void reset() {
        debugForceGuildlessUnlocked = false;
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = "";
    }

    // ── Unlock command ────────────────────────────────────────────────

    /**
     * Store a new authentication key and immediately try to verify it.
     *
     * <p>Called by {@link org.wynnvets.mixin.client.command.UnlockCommandMixin}
     * when the user runs {@code /unlock &lt;key&gt;}. The key is persisted to
     * {@link VetsConfig} and an {@code auth} frame is sent on the existing
     * inbound WebSocket connection. The result is reported to the user
     * asynchronously via the auth-frame response handler in
     * {@link V1ApiManager}.</p>
     *
     * @param key the bearer token typed by the user
     * @return {@link UnlockAttemptResult} describing what happened locally
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
                || c == '_' || c == '-';
            if (!ok) {
                return GuildStateManager.UnlockAttemptResult.MALFORMED;
            }
        }

        VetsConfig.setString(VetsConfig.VETS_AUTH_KEY, key);
        // Clear stale tier/verified state — the server's response will
        // populate them. Until that lands the session is "key stored,
        // verifying...".
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = "";

        // Push the new key out immediately so the user sees a result this
        // session rather than only on the next reconnect. V1ApiManager will
        // also re-auth on every reconnect using the persisted key.
        V1ApiManager.sendAuth(key);
        VetsLogger.debug("Auth key stored ({}…), auth frame dispatched",
            key.length() >= 6 ? key.substring(0, 6) : key);

        return GuildStateManager.UnlockAttemptResult.STORED_VERIFYING;
    }

    // ── Auth-frame response handlers (called by V1ApiManager) ──────────

    /** Called when the server returns {@code {"status":"ok", "tier":...}}
     *  in response to our auth frame. */
    static void onAuthSuccess(String tier) {
        currentTier = tier == null ? TIER_OTHER : tier;
        authVerifiedThisSession = true;
        lastAuthFailureReason = "";
        VetsLogger.info("Auth verified by server: tier={}", currentTier);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("✅ vetsmod authentication verified — tier: " + currentTier)
                    .withStyle(ChatFormatting.GREEN),
                true
            );
        }

        // Refresh presence registration so the server's connected_users
        // list reflects our (server-confirmed) tier rather than whatever
        // we inferred locally.
        GuildStateManager.sendRegistrationIfReady();
    }

    /** Called when the server returns
     *  {@code {"status":"error", "detail":"auth rejected: <reason>"}}. */
    static void onAuthFailure(String detail) {
        currentTier = "";
        authVerifiedThisSession = false;
        lastAuthFailureReason = detail == null ? "unknown" : detail;
        VetsLogger.warn("Auth rejected by server: {}", lastAuthFailureReason);

        ChatUtils.sendLocalMessage(
            Component.literal("❌ vetsmod authentication failed: " + lastAuthFailureReason
                    + ". Run `/vetsmod` in #bot-commands at https://wynnvets.org/discord "
                    + "to issue a new key.")
                .withStyle(ChatFormatting.RED)
        );
    }
}
