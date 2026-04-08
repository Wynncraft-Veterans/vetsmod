package org.wynnvets.guild;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Manages password-based unlock state for waitlist and honourary members.
 *
 * <p>Unlock codes are SHA-256 hashed and compared against known hashes.
 * Timestamps are persisted via {@link VetsConfig} with a 7-day expiry:
 * after that period the user must re-enter the password. A configurable
 * number of expiry warnings are shown before silently clearing stale
 * timestamps.</p>
 *
 * <p>This class is package-private and accessed exclusively through
 * {@link GuildStateManager}'s facade methods.</p>
 */
final class UnlockManager {

    // ── Constants ──────────────────────────────────────────────────────

    private static final String WAITLIST_PASSWORD_HASH =
            "8f74db5451e8e6e74189fa5e8a2d31efbb1853629fb80b30461bafc8a97fe07e";
    private static final String HONOURARY_PASSWORD_HASH =
            "4fe3af27e525245e9f3f3764e06b4eb5997ac09d553b4fdf59f9c9420caebca4";

    /** Unlock codes persist for 7 days before requiring re-entry. */
    private static final long UNLOCK_EXPIRY_MS = 7 * 24 * 60 * 60 * 1_000L;

    /** Maximum number of expiry warnings before silently clearing stale timestamps. */
    private static final long MAX_EXPIRY_WARNINGS = 3;

    // ── State ──────────────────────────────────────────────────────────

    private static boolean waitlistUnlocked = false;
    private static boolean honouraryUnlocked = false;
    private static boolean debugForceGuildlessUnlocked = false;

    private UnlockManager() {}

    // ── Queries ────────────────────────────────────────────────────────

    /** @return {@code true} if the player has unlocked as a waitlist member */
    static boolean isWaitlistUnlocked() {
        return debugForceGuildlessUnlocked || waitlistUnlocked;
    }

    /** @return {@code true} if the player has unlocked as an honourary member */
    static boolean isHonouraryUnlocked() {
        return honouraryUnlocked;
    }

    /** @return {@code true} when the debug guildless+unlocked override is active */
    static boolean isDebugForceGuildlessUnlocked() {
        return debugForceGuildlessUnlocked;
    }

    /**
     * Enables or disables the debug override that forces the user to be
     * treated as guildless and unlocked.
     *
     * @param enabled {@code true} to force guildless+unlocked behaviour
     */
    static void setDebugForceGuildlessUnlocked(boolean enabled) {
        debugForceGuildlessUnlocked = enabled;
        VetsLogger.debug("Debug guildless+unlocked override: {}",
                enabled ? "enabled" : "disabled");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Restores persisted unlock timestamps from {@link VetsConfig},
     * treating entries older than {@link #UNLOCK_EXPIRY_MS} as expired.
     */
    static void loadPersistedState() {
        long now = System.currentTimeMillis();

        long waitlistTime = VetsConfig.getLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME);
        waitlistUnlocked = waitlistTime > 0 && (now - waitlistTime) < UNLOCK_EXPIRY_MS;

        long honouraryTime = VetsConfig.getLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME);
        honouraryUnlocked = honouraryTime > 0 && (now - honouraryTime) < UNLOCK_EXPIRY_MS;
    }

    /**
     * Resets transient unlock state on disconnect, reloading persisted
     * timestamps with expiry checks.
     */
    static void reset() {
        debugForceGuildlessUnlocked = false;
        long now = System.currentTimeMillis();

        long waitlistTime = VetsConfig.getLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME);
        waitlistUnlocked = waitlistTime > 0 && (now - waitlistTime) < UNLOCK_EXPIRY_MS;

        long honouraryTime = VetsConfig.getLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME);
        honouraryUnlocked = honouraryTime > 0 && (now - honouraryTime) < UNLOCK_EXPIRY_MS;
    }

    // ── Unlock logic ───────────────────────────────────────────────────

    /**
     * Attempts to unlock with a password, checking against both the
     * waitlist and honourary password hashes.
     *
     * @param password the plaintext password to check
     * @return the type of unlock granted, or {@link GuildStateManager.UnlockType#NONE}
     */
    static GuildStateManager.UnlockType tryUnlock(String password) {
        String hash = sha256(password);
        if (hash == null) {
            return GuildStateManager.UnlockType.NONE;
        }

        long now = System.currentTimeMillis();

        if (hash.equals(WAITLIST_PASSWORD_HASH)) {
            waitlistUnlocked = true;
            VetsConfig.setLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME, now);
            VetsConfig.setLong(VetsConfig.VETS_UNLOCK_EXPIRY_WARNINGS, 0L);
            VetsLogger.debug("Mod unlocked via waitlist password");
            return GuildStateManager.UnlockType.WAITLIST;
        }

        if (hash.equals(HONOURARY_PASSWORD_HASH)) {
            honouraryUnlocked = true;
            VetsConfig.setLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME, now);
            VetsConfig.setLong(VetsConfig.VETS_UNLOCK_EXPIRY_WARNINGS, 0L);
            VetsLogger.debug("Mod unlocked via honourary password");
            return GuildStateManager.UnlockType.HONOURARY;
        }

        return GuildStateManager.UnlockType.NONE;
    }

    /**
     * Checks whether a previously-persisted unlock has expired and, if so,
     * warns the player. After {@link #MAX_EXPIRY_WARNINGS} warnings the
     * stale timestamps are silently cleared.
     */
    static void checkAndWarnUnlockExpiry() {
        long now = System.currentTimeMillis();

        long waitlistTime = VetsConfig.getLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME);
        long honouraryTime = VetsConfig.getLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME);
        boolean waitlistExpired = waitlistTime > 0 && (now - waitlistTime) >= UNLOCK_EXPIRY_MS;
        boolean honouraryExpired = honouraryTime > 0 && (now - honouraryTime) >= UNLOCK_EXPIRY_MS;

        if (!waitlistExpired && !honouraryExpired) {
            return;
        }

        long warnings = VetsConfig.getLong(VetsConfig.VETS_UNLOCK_EXPIRY_WARNINGS);
        if (warnings >= MAX_EXPIRY_WARNINGS) {
            if (waitlistExpired) {
                VetsConfig.setLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME, 0L);
            }
            if (honouraryExpired) {
                VetsConfig.setLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME, 0L);
            }
            VetsConfig.setLong(VetsConfig.VETS_UNLOCK_EXPIRY_WARNINGS, 0L);
            return;
        }

        VetsConfig.setLong(VetsConfig.VETS_UNLOCK_EXPIRY_WARNINGS, warnings + 1);

        if (waitlistExpired) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Your waitlist unlock has expired. Use /unlock <password> to re-activate.")
                            .withStyle(ChatFormatting.YELLOW)
            );
        }
        if (honouraryExpired) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Your honourary unlock has expired. Use /unlock <password> to re-activate.")
                            .withStyle(ChatFormatting.YELLOW)
            );
        }
    }

    // ── Internals ──────────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hash of a string.
     *
     * @param input the plaintext input
     * @return the lowercase hex-encoded hash, or {@code null} on error
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            VetsLogger.error("SHA-256 algorithm not available", e);
            return null;
        }
    }
}
