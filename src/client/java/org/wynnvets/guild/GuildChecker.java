package org.wynnvets.guild;

import com.wynntils.core.components.Handlers;
import com.wynntils.core.components.Models;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;

/**
 * Determines the player's guild membership by issuing {@code /gu stats}
 * to the Wynncraft server and parsing the multi-line response.
 *
 * <p>Unlike Wynntils' {@code Models.Guild} (which reads scoreboard/compass
 * data and can remain {@code null} for extended periods), this checker
 * queries the server directly and caches the result for
 * {@value #GUILD_CHECK_EXPIRY_DAYS} days.</p>
 *
 * <p>The command is dispatched through Wynntils' rate-limited command queue
 * ({@code Handlers.Command}) so it never interferes with other mod-initiated
 * commands (staff rank checks, /msg fanout, etc.).</p>
 *
 * <p>This class is package-private and accessed exclusively through
 * {@link GuildStateManager}'s facade methods.</p>
 */
final class GuildChecker {

    // ── Result enum ────────────────────────────────────────────────────

    enum GuildCheckResult {
        UNKNOWN(0),
        RETURNERS(1),
        OTHER_GUILD(2),
        GUILDLESS(3);

        final long persistedValue;

        GuildCheckResult(long persistedValue) {
            this.persistedValue = persistedValue;
        }

        static GuildCheckResult fromPersistedValue(long value) {
            for (GuildCheckResult r : values()) {
                if (r.persistedValue == value) return r;
            }
            return UNKNOWN;
        }
    }

    // ── Constants ──────────────────────────────────────────────────────

    private static final String RETURNERS_GUILD_NAME = "Returners";

    /** Maximum time to wait for the full /gu stats response. */
    private static final long GUILD_CHECK_TIMEOUT_MS = 10_000L;

    /** How long a check result stays valid before re-checking. */
    private static final int GUILD_CHECK_EXPIRY_DAYS = 3;
    private static final long GUILD_CHECK_EXPIRY_MS =
            GUILD_CHECK_EXPIRY_DAYS * 24L * 60L * 60L * 1_000L;

    /**
     * Grace period after a check completes during which trailing
     * {@code /gu stats} output lines are still suppressed.
     */
    private static final long SUPPRESSION_GRACE_MS = 2_000L;

    // ── State ──────────────────────────────────────────────────────────

    private static volatile boolean waitingForGuildCheck = false;
    private static volatile boolean isModInitiatedGuildCheck = false;
    private static volatile long guildCheckRequestTime = 0;
    private static volatile long guildCheckSuppressUntil = 0;

    /** The last unrecognised non-empty line seen during a check — the
     *  candidate guild name that precedes "Guild Since:". */
    private static volatile String lastNonEmptyCandidate = null;

    // ── Persisted state ────────────────────────────────────────────────

    private static GuildCheckResult cachedResult = GuildCheckResult.UNKNOWN;
    private static long lastGuildCheckTime = 0;

    private GuildChecker() {}

    // ── Queries ────────────────────────────────────────────────────────

    /**
     * @return {@code true} when a non-expired check result is available
     */
    static boolean hasValidResult() {
        if (cachedResult == GuildCheckResult.UNKNOWN) return false;
        long elapsed = System.currentTimeMillis() - lastGuildCheckTime;
        return elapsed >= 0 && elapsed < GUILD_CHECK_EXPIRY_MS;
    }

    static GuildCheckResult getResult() {
        return cachedResult;
    }

    static long getLastCheckTime() {
        return lastGuildCheckTime;
    }

    /**
     * @return {@code true} during a mod-initiated guild check <em>or</em>
     *         the brief suppression grace period after one completes
     */
    static boolean isProcessingModGuildCheck() {
        if (isModInitiatedGuildCheck) return true;
        return guildCheckSuppressUntil > 0
                && System.currentTimeMillis() < guildCheckSuppressUntil;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    static void loadPersistedState() {
        long persistedResult = VetsConfig.getLong(VetsConfig.VETS_GUILD_CHECK_RESULT);
        cachedResult = GuildCheckResult.fromPersistedValue(persistedResult);

        long persistedTime = VetsConfig.getLong(VetsConfig.VETS_LAST_GUILD_CHECK);
        long now = System.currentTimeMillis();
        lastGuildCheckTime = (persistedTime >= 0 && persistedTime <= now)
                ? persistedTime : 0;

        // Expire stale results on load
        if (cachedResult != GuildCheckResult.UNKNOWN) {
            long elapsed = now - lastGuildCheckTime;
            if (elapsed < 0 || elapsed >= GUILD_CHECK_EXPIRY_MS) {
                cachedResult = GuildCheckResult.UNKNOWN;
                VetsLogger.debug("Guild check result expired on load");
            }
        }

        VetsLogger.debug("GuildChecker loaded: result={}, age={}s",
                cachedResult, lastGuildCheckTime > 0
                        ? (now - lastGuildCheckTime) / 1000 : "n/a");
    }

    static void reset() {
        waitingForGuildCheck = false;
        isModInitiatedGuildCheck = false;
        guildCheckRequestTime = 0;
        guildCheckSuppressUntil = 0;
        lastNonEmptyCandidate = null;

        // Reload persisted state (result survives disconnect)
        loadPersistedState();
    }

    /**
     * Clears the in-memory and persisted result.  Called when Wynntils fires
     * a {@code GuildEvent} (join/leave), since that real-time data is
     * authoritative over our cached check.
     */
    static void clearResult() {
        cachedResult = GuildCheckResult.UNKNOWN;
        lastGuildCheckTime = 0;
        VetsConfig.setLong(VetsConfig.VETS_GUILD_CHECK_RESULT, 0L);
        VetsConfig.setLong(VetsConfig.VETS_LAST_GUILD_CHECK, 0L);
        VetsLogger.debug("GuildChecker result cleared (Wynntils guild event)");
    }

    // ── Refresh logic ──────────────────────────────────────────────────

    /**
     * Starts a guild check by queuing {@code /gu stats} through the
     * Wynntils command queue.  Must be called on the render thread.
     *
     * @return {@code true} if a check was started
     */
    static synchronized boolean refreshGuildStatus() {
        if (waitingForGuildCheck) {
            return false;
        }

        waitingForGuildCheck = true;
        isModInitiatedGuildCheck = true;
        guildCheckRequestTime = System.currentTimeMillis();
        lastNonEmptyCandidate = null;

        Handlers.Command.queueCommand("gu stats");
        VetsLogger.debug("Guild check queued via Wynntils command queue");
        return true;
    }

    // ── Response processing ────────────────────────────────────────────

    /**
     * Inspects an incoming chat message for {@code /gu stats} responses.
     * Updates guild state and returns {@code true} when the message should
     * be suppressed from display (mod-initiated check output).
     *
     * @param message the plain-text chat message
     * @return {@code true} if the message should be suppressed
     */
    static boolean processAndShouldSuppress(String message) {
        String safeMessage = message == null ? "" : message;

        // During suppression grace, suppress known stat output lines and
        // empty separator lines from the /gu stats response.
        if (!waitingForGuildCheck && isInSuppressionGrace()) {
            return isGuildStatsOutputLine(safeMessage) || safeMessage.trim().isEmpty();
        }

        if (!waitingForGuildCheck) {
            return false;
        }

        // Timeout guard
        if (System.currentTimeMillis() - guildCheckRequestTime > GUILD_CHECK_TIMEOUT_MS) {
            VetsLogger.debug("Guild check timed out");
            completeCheck();
            return false;
        }

        String trimmed = safeMessage.trim();
        String lower = trimmed.toLowerCase();

        // ── Guildless response ──────────────────────────────────────
        if (lower.contains("you must be in a guild")) {
            setResult(GuildCheckResult.GUILDLESS);
            completeCheck();
            VetsLogger.debug("Guild check result: guildless");
            return isModInitiatedGuildCheck;
        }

        // ── "Guild Since:" confirms the candidate guild name ────────
        if (lower.startsWith("guild since:")) {
            if (lastNonEmptyCandidate != null) {
                if (lastNonEmptyCandidate.equalsIgnoreCase(RETURNERS_GUILD_NAME)) {
                    setResult(GuildCheckResult.RETURNERS);
                    VetsLogger.debug("Guild check result: Returners");
                } else {
                    setResult(GuildCheckResult.OTHER_GUILD);
                    VetsLogger.debug("Guild check result: other guild ({})",
                            lastNonEmptyCandidate);
                }
            } else {
                VetsLogger.warn("Guild check: saw 'Guild Since:' without candidate name");
                setResult(GuildCheckResult.UNKNOWN);
            }
            completeCheck();
            return isModInitiatedGuildCheck;
        }

        // ── Known stat output lines ─────────────────────────────────
        if (isGuildStatsOutputLine(trimmed)) {
            return isModInitiatedGuildCheck;
        }

        // ── Empty line (part of /gu stats formatting) ───────────────
        if (trimmed.isEmpty()) {
            // Only suppress empty lines that arrive within the check window
            return isModInitiatedGuildCheck;
        }

        // ── Potential guild name (unrecognised short line) ──────────
        // Guard against staff rank check responses landing here
        if (!isStaffRankCheckResponse(trimmed)
                && trimmed.length() < 60
                && !trimmed.contains(":")) {
            lastNonEmptyCandidate = trimmed;
            return isModInitiatedGuildCheck;
        }

        return false;
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static void setResult(GuildCheckResult result) {
        cachedResult = result;
        lastGuildCheckTime = System.currentTimeMillis();
        VetsConfig.setLong(VetsConfig.VETS_GUILD_CHECK_RESULT, result.persistedValue);
        VetsConfig.setLong(VetsConfig.VETS_LAST_GUILD_CHECK, lastGuildCheckTime);
    }

    private static void completeCheck() {
        waitingForGuildCheck = false;
        // Keep isModInitiatedGuildCheck true during grace period for suppression
        guildCheckSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;

        // Schedule clearing the mod-initiated flag after grace period
        long graceEnd = guildCheckSuppressUntil;
        new Thread(() -> {
            try {
                long remaining = graceEnd - System.currentTimeMillis();
                if (remaining > 0) {
                    Thread.sleep(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (guildCheckSuppressUntil == graceEnd) {
                    isModInitiatedGuildCheck = false;
                }
            }
        }, "vetsmod-guild-check-grace").start();

        // Notify GuildStateManager that the check completed
        GuildStateManager.onGuildCheckCompleted();
    }

    private static boolean isInSuppressionGrace() {
        return guildCheckSuppressUntil > 0
                && System.currentTimeMillis() < guildCheckSuppressUntil;
    }

    private static boolean isGuildStatsOutputLine(String message) {
        String lower = message.trim().toLowerCase();
        return lower.startsWith("guild since:")
                || lower.startsWith("owner:")
                || lower.startsWith("guild level:")
                || lower.startsWith("needed xp:")
                || lower.startsWith("guild rank:")
                || lower.startsWith("total members:");
    }

    /**
     * Returns {@code true} for messages that belong to {@code /gu rank}
     * responses, preventing them from being mistakenly captured as a
     * candidate guild name during a concurrent guild check.
     */
    private static boolean isStaffRankCheckResponse(String message) {
        String lower = message.trim().toLowerCase();
        return (lower.contains("you must be a")
                    && lower.contains("captain")
                    && lower.contains("to use this command"))
                || (lower.contains("invalid arguments, try:")
                    && lower.contains("rank [name] [rank]"));
    }
}
