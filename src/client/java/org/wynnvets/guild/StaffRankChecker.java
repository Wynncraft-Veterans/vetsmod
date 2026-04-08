package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.models.worlds.type.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Detects and tracks the player's staff rank by issuing {@code /gu rank}
 * to the Wynncraft server and parsing the response.
 *
 * <p>Staff status is persisted across sessions via {@link VetsConfig} and
 * refreshed once per day (controlled by {@link #STAFF_CHECK_COOLDOWN}).
 * The check runs asynchronously on a background thread with render-thread
 * round-trips to send the command and receive the response.</p>
 *
 * <p>This class is package-private and accessed exclusively through
 * {@link GuildStateManager}'s facade methods.</p>
 */
final class StaffRankChecker {

    // ── Constants ──────────────────────────────────────────────────────

    /** Timeout for a single /gu rank response. */
    private static final long STAFF_RANK_TIMEOUT_MS = 5_000L;

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
        return staffRankSuppressUntil > 0
                && System.currentTimeMillis() < staffRankSuppressUntil;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Restores persisted staff status and last-check timestamp from
     * {@link VetsConfig}.
     */
    static void loadPersistedState() {
        isStaff = VetsConfig.get(VetsConfig.VETS_IS_STAFF);
        long persistedCheckTime = VetsConfig.getLong(VetsConfig.VETS_LAST_STAFF_CHECK);
        long now = System.currentTimeMillis();
        lastStaffCheckTime = (persistedCheckTime >= 0 && persistedCheckTime <= now)
                ? persistedCheckTime : 0;
    }

    /**
     * Resets transient state on server disconnect, reloading the persisted
     * staff flag so the next world join starts with accurate state.
     */
    static void reset() {
        staffRankSuppressUntil = 0;
        isStaff = VetsConfig.get(VetsConfig.VETS_IS_STAFF);
        long persistedCheckTime = VetsConfig.getLong(VetsConfig.VETS_LAST_STAFF_CHECK);
        long now = System.currentTimeMillis();
        lastStaffCheckTime = (persistedCheckTime >= 0 && persistedCheckTime <= now)
                ? persistedCheckTime : 0;
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankRequestTime = 0;
    }

    // ── Refresh logic ──────────────────────────────────────────────────

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
        boolean hasRecentCheck = lastStaffCheckTime > 0
                && (now - lastStaffCheckTime) < STAFF_CHECK_COOLDOWN_MS;
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
        } else if (isStaffRankAuthorizedResponse(safeMessage)) {
            setStaffStatus(true);
            completeCheck();
            VetsLogger.debug("Staff rank check result: is staff");
        }
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static void setStaffStatus(boolean value) {
        isStaff = value;
        VetsConfig.set(VetsConfig.VETS_IS_STAFF, value);
    }

    private static void markStaffCheckCompletedNow() {
        lastStaffCheckTime = System.currentTimeMillis();
        VetsConfig.setLong(VetsConfig.VETS_LAST_STAFF_CHECK, lastStaffCheckTime);
    }

    private static void completeCheck() {
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;
        markStaffCheckCompletedNow();
    }

    /**
     * Issues {@code /gu rank} on the render thread from a background thread,
     * retrying until a response is detected or attempts are exhausted.
     */
    private static void sendStaffRankCheckCommand() {
        Minecraft minecraft = Minecraft.getInstance();

        waitingForStaffRankCheck = true;
        isModInitiatedStaffRankCheck = true;
        staffRankRequestTime = System.currentTimeMillis();

        new Thread(() -> {
            int attempts = 0;
            int maxAttempts = 20; // 5 seconds total
            long delay = 250;

            while (attempts < maxAttempts) {
                try {
                    Thread.sleep(delay);

                    CountDownLatch latch = new CountDownLatch(1);
                    boolean[] commandSent = {false};
                    minecraft.execute(() -> {
                        try {
                            if (!GuildStateManager.isWynntilsReady()) return;
                            WorldState currentState = Models.WorldState.getCurrentState();
                            if (currentState != WorldState.WORLD) return;

                            LocalPlayer player = minecraft.player;
                            if (player != null && player.connection != null) {
                                try {
                                    player.connection.sendCommand("gu rank");
                                    commandSent[0] = true;
                                } catch (Exception e) {
                                    VetsLogger.warn("Failed to send /gu rank: {}", e.getMessage());
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });

                    latch.await(2, TimeUnit.SECONDS);

                    if (commandSent[0]) {
                        int responseWait = 0;
                        while (responseWait < 8 && waitingForStaffRankCheck) {
                            Thread.sleep(250);
                            responseWait++;
                        }
                        if (!waitingForStaffRankCheck) break;
                    }

                    attempts++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    VetsLogger.warn("Staff rank check interrupted");
                    waitingForStaffRankCheck = false;
                    isModInitiatedStaffRankCheck = false;
                    break;
                }
            }

            if (attempts >= maxAttempts) {
                VetsLogger.warn("Staff rank check timed out after {} attempts", maxAttempts);
                waitingForStaffRankCheck = false;
                isModInitiatedStaffRankCheck = false;
                markStaffCheckCompletedNow();
            }
        }, "vetsmod-staff-rank-check").start();
    }

    private static boolean isStaffRankUnauthorizedResponse(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("you must be a")
                && lower.contains("captain")
                && lower.contains("to use this command");
    }

    private static boolean isStaffRankAuthorizedResponse(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("invalid arguments, try:")
                && lower.contains("rank [name] [rank]");
    }
}
