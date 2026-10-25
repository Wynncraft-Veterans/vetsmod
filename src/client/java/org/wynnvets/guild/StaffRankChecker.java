package org.wynnvets.guild;

import com.wynntils.core.components.Handlers;
import java.util.Locale;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;

/**
 * Detects and tracks the player's staff rank by issuing {@code /gu rank}
 * to the Wynncraft server and parsing the response.
 *
 * <p>Staff status is persisted across sessions via {@link VetsConfig} and
 * refreshed once per day (controlled by {@link #STAFF_CHECK_COOLDOWN_MS}).
 * The check queues {@code /gu rank} through Wynntils' command queue and completes when
 * {@link #processMessage(String)} sees the reply in chat, or times out. A timed-out check
 * keeps the cached flag and still stamps the daily check, so a slow command queue does not
 * demote a real staff member, and a reply nothing recognises is not re-requested at every
 * world join. A guildless player's reply ({@code You must be in a guild to use this.}) is
 * recognised and clears the flag like the unauthorized one. The flag is also dropped, and the
 * daily stamp reset so the next refresh rechecks, when the player leaves the guild or a guild
 * check finds them guildless ({@link #clearForGuildChange()}). It is not tracked per guild: a
 * player now in another guild keeps it until a check answers, so consumers that must not trust
 * it for a non-Returner also test the guild, as {@code /wv list world} does.</p>
 *
 * <p>This class is package-private. Outside the package it is reached through
 * {@link GuildStateManager}'s facade methods; {@link GuildLifecycle} also calls
 * {@link #reset()} directly.</p>
 */
final class StaffRankChecker {

    // ── Constants ──────────────────────────────────────────────────────

    /** Timeout for a single /gu rank response (generous to account for
     *  Wynntils command queue depth). */
    private static final long STAFF_RANK_TIMEOUT_MS = 10_000L;

    /** Minimum interval between automatic staff rank checks (24 hours). */
    private static final long STAFF_CHECK_COOLDOWN_MS = 24 * 60 * 60 * 1_000L;

    /**
     * Grace period after a check completes during which duplicate
     * {@code addMessage} calls are still suppressed by
     * {@link #isProcessingModStaffRankCheck()}.
     */
    private static final long SUPPRESSION_GRACE_MS = 500L;

    // ── State ──────────────────────────────────────────────────────────

    private static boolean isStaff = false;
    private static long lastStaffCheckTime = 0;
    private static boolean waitingForStaffRankCheck = false;
    private static boolean isModInitiatedStaffRankCheck = false;
    private static long staffRankRequestTime = 0;
    private static long staffRankSuppressUntil = 0;

    private StaffRankChecker() {}

    // ── Queries ────────────────────────────────────────────────────────

    /**
     * @return {@code true} when the player holds a staff rank (Captain+)
     */
    static boolean isStaff() {
        return isStaff;
    }

    /**
     * @return {@code true} while a mod-initiated {@code /gu rank} check is
     *         in progress
     */
    static boolean isCheckingStaffStatus() {
        return waitingForStaffRankCheck;
    }

    /**
     * @return {@code true} during a mod-initiated rank check <em>or</em> the
     *         brief suppression grace period after one completes
     */
    static boolean isProcessingModStaffRankCheck() {
        if (isModInitiatedStaffRankCheck) return true;
        return staffRankSuppressUntil > 0 && System.currentTimeMillis() < staffRankSuppressUntil;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Restores persisted staff status and last-check timestamp from
     * {@link VetsConfig}.
     */
    static void loadPersistedState() {
        isStaff = VetsConfig.get(VetsConfigKeys.VETS_IS_STAFF);
        long persistedCheckTime = VetsConfig.getLong(VetsConfigKeys.VETS_LAST_STAFF_CHECK);
        long now = System.currentTimeMillis();
        lastStaffCheckTime =
                (persistedCheckTime >= 0 && persistedCheckTime <= now) ? persistedCheckTime : 0;
    }

    /**
     * Resets transient state on server disconnect, reloading the persisted
     * staff flag so the next world join starts with accurate state.
     */
    static void reset() {
        staffRankSuppressUntil = 0;
        isStaff = VetsConfig.get(VetsConfigKeys.VETS_IS_STAFF);
        long persistedCheckTime = VetsConfig.getLong(VetsConfigKeys.VETS_LAST_STAFF_CHECK);
        long now = System.currentTimeMillis();
        lastStaffCheckTime =
                (persistedCheckTime >= 0 && persistedCheckTime <= now) ? persistedCheckTime : 0;
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankRequestTime = 0;
    }

    // ── Refresh logic ──────────────────────────────────────────────────

    /** Whether a staff-rank check finished within the last {@code ms} milliseconds. */
    static boolean checkedWithin(long ms) {
        return lastStaffCheckTime > 0 && System.currentTimeMillis() - lastStaffCheckTime < ms;
    }

    /**
     * Starts a staff-rank refresh if the daily cooldown has elapsed (or if
     * {@code forceRefresh} is {@code true}).
     *
     * @param forceRefresh bypass the daily cooldown
     * @return {@code true} if a refresh was started
     */
    static synchronized boolean refreshStaffStatusIfNeeded(boolean forceRefresh) {
        if (waitingForStaffRankCheck) {
            return false;
        }

        long now = System.currentTimeMillis();
        boolean hasRecentCheck =
                lastStaffCheckTime > 0 && (now - lastStaffCheckTime) < STAFF_CHECK_COOLDOWN_MS;
        if (!forceRefresh && hasRecentCheck) {
            return false;
        }

        sendStaffRankCheckCommand();
        return true;
    }

    // ── Response processing ────────────────────────────────────────────

    /**
     * Inspects incoming chat messages for {@code /gu rank} responses and
     * updates staff status accordingly.
     *
     * @param message the plain-text chat message
     */
    static void processMessage(String message) {
        String safeMessage = message == null ? "" : message;

        // Timeout guard
        if (waitingForStaffRankCheck
                && System.currentTimeMillis() - staffRankRequestTime > STAFF_RANK_TIMEOUT_MS) {
            waitingForStaffRankCheck = false;
            isModInitiatedStaffRankCheck = false;
            markStaffCheckCompletedNow();
        }

        if (!waitingForStaffRankCheck) {
            return;
        }

        if (isStaffRankUnauthorizedResponse(safeMessage)) {
            setStaffStatus(false);
            completeCheck();
            VetsLogger.debug("Staff rank check result: not staff");
        } else if (isNotInGuildResponse(safeMessage)) {
            setStaffStatus(false);
            completeCheck();
            VetsLogger.debug("Staff rank check result: not in a guild, so not staff");
        } else if (isStaffRankAuthorizedResponse(safeMessage)) {
            setStaffStatus(true);
            completeCheck();
            VetsLogger.debug("Staff rank check result: is staff");
        }
    }

    // ── Guild change ───────────────────────────────────────────────────

    /**
     * Drops a cached staff flag that cannot hold any more because the player left their guild or a
     * guild check found them guildless: the flag goes false (through {@link #setStaffStatus} so it
     * persists) and the last-check stamp resets to 0, field and {@code VETS_LAST_STAFF_CHECK},
     * so the next {@link #refreshStaffStatusIfNeeded} rechecks instead of waiting out the day.
     * Does nothing for a player not cached as staff. Not for an {@code OTHER_GUILD} result, which
     * says nothing about the rank. A wrongly dropped steward is recognised again by the next
     * check, which {@code GuildChatDispatcher} forces when a non-staff player sends {@code /v}.
     * Called by {@link GuildLifecycle#onGuildLeft()} and {@link GuildChecker} on a
     * {@code GUILDLESS} result.
     */
    static void clearForGuildChange() {
        if (!shouldClearStaffFlag(isStaff, true)) {
            return;
        }
        setStaffStatus(false);
        lastStaffCheckTime = 0;
        VetsConfig.setLong(VetsConfigKeys.VETS_LAST_STAFF_CHECK, 0L);
        VetsLogger.debug("Staff flag cleared: guild left or guildless; recheck due");
    }

    /**
     * Whether {@link #clearForGuildChange} has a flag to drop: the player is cached as staff and
     * the guild-left or guildless signal fired.
     */
    // Package-private for unit tests. See StaffRankCheckerTest.
    static boolean shouldClearStaffFlag(boolean cachedStaff, boolean guildLeftOrGuildless) {
        return cachedStaff && guildLeftOrGuildless;
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static void setStaffStatus(boolean value) {
        isStaff = value;
        VetsConfig.set(VetsConfigKeys.VETS_IS_STAFF, value);
    }

    private static void markStaffCheckCompletedNow() {
        lastStaffCheckTime = System.currentTimeMillis();
        VetsConfig.setLong(VetsConfigKeys.VETS_LAST_STAFF_CHECK, lastStaffCheckTime);
    }

    private static void completeCheck() {
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;
        markStaffCheckCompletedNow();
    }

    /**
     * Queues {@code /gu rank} through the Wynntils rate-limited command
     * queue.  The response is processed asynchronously by
     * {@link #processMessage(String)} as chat messages arrive.
     */
    private static void sendStaffRankCheckCommand() {
        waitingForStaffRankCheck = true;
        isModInitiatedStaffRankCheck = true;
        staffRankRequestTime = System.currentTimeMillis();

        Handlers.Command.queueCommand("gu rank");
        VetsLogger.debug("Staff rank check queued via Wynntils command queue");
    }

    // Package-private for unit tests. See StaffRankCheckerTest.
    static boolean isStaffRankUnauthorizedResponse(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("you must be a")
                && lower.contains("captain")
                && lower.contains("to use this command");
    }

    // Package-private for unit tests. See StaffRankCheckerTest.
    static boolean isStaffRankAuthorizedResponse(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("invalid arguments, try:") && lower.contains("rank [name] [rank]");
    }

    /** What Wynncraft answers {@code /gu rank} from a player in no guild: {@code You must be in a
     *  guild to use this.} (aqua), captured in game. */
    // Package-private for unit tests. See StaffRankCheckerTest.
    static boolean isNotInGuildResponse(String message) {
        if (message == null) return false;
        return message.toLowerCase(Locale.ROOT).contains("you must be in a guild");
    }

    /**
     * Whether a chat line is any reply to a mod-initiated {@code /gu rank}: the unauthorized one,
     * the authorized one or the not-in-a-guild one. The one test {@link #processMessage} and
     * {@code ChatLogMixin}'s hiding of the reply share, through
     * {@link GuildStateManager#isStaffRankReply}.
     */
    // Package-private for unit tests. See StaffRankCheckerTest.
    static boolean isStaffRankReply(String message) {
        return isStaffRankUnauthorizedResponse(message)
                || isStaffRankAuthorizedResponse(message)
                || isNotInGuildResponse(message);
    }
}
