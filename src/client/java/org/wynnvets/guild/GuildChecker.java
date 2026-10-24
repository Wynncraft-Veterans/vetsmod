package org.wynnvets.guild;

import com.wynntils.core.components.Handlers;
import java.util.Locale;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.TickDelays;

/**
 * Determines the player's guild membership by issuing {@code /gu stats}
 * to the Wynncraft server and parsing the multi-line response.
 *
 * <p>Unlike Wynntils' {@code Models.Guild} (which learns the guild from its character-menu
 * scan and from guild chat lines, and whose guild name can stay empty for extended
 * periods), this checker queries the server directly and caches the result for
 * {@value #GUILD_CHECK_EXPIRY_DAYS} days. While that result is valid it takes precedence
 * over Wynntils in {@link GuildStateManager#isReturners()} and
 * {@link GuildStateManager#isGuildless()}.</p>
 *
 * <p>The command is dispatched through Wynntils' command queue ({@code Handlers.Command}),
 * which spaces it from other mod-initiated commands (staff rank checks, /msg fanout,
 * etc.). A concurrent {@code /gu rank} reply can still arrive mid-check, which
 * {@link #isStaffRankCheckResponse(String)} guards against.</p>
 *
 * <p>The class, its result enum and the members the forced guild check uses are public, for
 * {@link org.wynnvets.debug.diagnostics.GuildDiagnostics GuildDiagnostics} in another package;
 * everything else is package-private or private. Within the package,
 * {@link GuildStateManager}'s facade methods and {@link GuildLifecycle} call it directly.</p>
 */
public final class GuildChecker {

    // ── Result enum ────────────────────────────────────────────────────

    // Public for GuildDiagnostics, which reports the cached result.
    public enum GuildCheckResult {
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

        /**
         * Whether this stored result disagrees with Wynntils' live guild data: {@code RETURNERS}
         * when Wynntils reports no guild or another one, {@code OTHER_GUILD} when it reports none
         * or Returners, {@code GUILDLESS} when it reports a guild. {@code UNKNOWN} disagrees with
         * nothing. {@link GuildLifecycle}'s recheck poll clears the result only on disagreement
         * ({@link #clearResultIfContradicted}).
         */
        boolean contradicts(boolean inGuild, String guildName) {
            return switch (this) {
                case UNKNOWN -> false;
                case RETURNERS -> !inGuild || !RETURNERS_GUILD_NAME.equals(guildName);
                case OTHER_GUILD -> !inGuild || RETURNERS_GUILD_NAME.equals(guildName);
                case GUILDLESS -> inGuild;
            };
        }

        /**
         * Whether this result says the player is not in Returners: {@code OTHER_GUILD} or
         * {@code GUILDLESS}. {@code RETURNERS} is not, and {@code UNKNOWN} (what a parse miss
         * stores; a timeout stores nothing, so the previous result stays) says nothing.
         * {@link GuildLifecycle#onGuildCheckCompleted()} passes it as the {@code definitive}
         * flag of {@link GuildStateManager#reconcileRegistration}, so only a check that found
         * the player outside Returners can retract a {@code guild} registration.
         */
        boolean isDefinitive() {
            return this == OTHER_GUILD || this == GUILDLESS;
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
    // Public for GuildDiagnostics, which reports whether the result has expired.
    public static boolean hasValidResult() {
        if (cachedResult == GuildCheckResult.UNKNOWN) return false;
        long elapsed = System.currentTimeMillis() - lastGuildCheckTime;
        return elapsed >= 0 && elapsed < GUILD_CHECK_EXPIRY_MS;
    }

    // Public for GuildDiagnostics, which reports the cached result.
    public static GuildCheckResult getResult() {
        return cachedResult;
    }

    // Public for GuildDiagnostics, which reports the result's age.
    public static long getLastCheckTime() {
        return lastGuildCheckTime;
    }

    /**
     * @return {@code true} during a mod-initiated guild check <em>or</em>
     *         the brief suppression grace period after one completes
     */
    static boolean isProcessingModGuildCheck() {
        if (isModInitiatedGuildCheck) return true;
        return guildCheckSuppressUntil > 0 && System.currentTimeMillis() < guildCheckSuppressUntil;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    static void loadPersistedState() {
        long persistedResult = VetsConfig.getLong(VetsConfigKeys.VETS_GUILD_CHECK_RESULT);
        cachedResult = GuildCheckResult.fromPersistedValue(persistedResult);

        long persistedTime = VetsConfig.getLong(VetsConfigKeys.VETS_LAST_GUILD_CHECK);
        long now = System.currentTimeMillis();
        lastGuildCheckTime = (persistedTime >= 0 && persistedTime <= now) ? persistedTime : 0;

        // Expire stale results on load
        if (cachedResult != GuildCheckResult.UNKNOWN) {
            long elapsed = now - lastGuildCheckTime;
            if (elapsed < 0 || elapsed >= GUILD_CHECK_EXPIRY_MS) {
                cachedResult = GuildCheckResult.UNKNOWN;
                VetsLogger.debug("Guild check result expired on load");
            }
        }

        VetsLogger.debug(
                "GuildChecker loaded: result={}, age={}s",
                cachedResult,
                lastGuildCheckTime > 0 ? (now - lastGuildCheckTime) / 1000 : "n/a");
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
     * Clears the in-memory and persisted result. Reached through
     * {@link GuildLifecycle#onGuildInfoUpdated()} on Wynntils' guild join event and
     * {@link GuildLifecycle#onGuildLeft()} on its leave event: a guild change makes the cached
     * check stale, and the real-time data is treated as authoritative.
     */
    static void clearResult() {
        forgetResult();
        VetsLogger.debug("GuildChecker result cleared (Wynntils guild event)");
    }

    /**
     * Clears the result only if it {@linkplain GuildCheckResult#contradicts contradicts}
     * Wynntils' guild data, for the post-world-join recheck poll, where no guild event fired.
     * A result that agrees, such as a {@code /gu stats} answer that landed before Wynntils' own
     * scan, is kept; one left from a guild change while disconnected is not.
     */
    static void clearResultIfContradicted(boolean inGuild, String guildName) {
        GuildCheckResult result = cachedResult;
        if (!result.contradicts(inGuild, guildName)) {
            VetsLogger.debug("GuildChecker result {} kept: Wynntils' guild data agrees", result);
            return;
        }
        forgetResult();
        VetsLogger.debug(
                "GuildChecker result {} cleared: Wynntils reports {}",
                result,
                inGuild ? guildName : "no guild");
    }

    private static void forgetResult() {
        cachedResult = GuildCheckResult.UNKNOWN;
        lastGuildCheckTime = 0;
        VetsConfig.setLong(VetsConfigKeys.VETS_GUILD_CHECK_RESULT, 0L);
        VetsConfig.setLong(VetsConfigKeys.VETS_LAST_GUILD_CHECK, 0L);
    }

    // ── Refresh logic ──────────────────────────────────────────────────

    /**
     * Starts a guild check by queuing {@code /gu stats} through the
     * Wynntils command queue.  Must be called on the render thread.
     *
     * @return {@code true} if a check was started
     */
    // Public for GuildDiagnostics, which starts a check on demand.
    public static synchronized boolean refreshGuildStatus() {
        if (waitingForGuildCheck) {
            return false;
        }

        waitingForGuildCheck = true;
        isModInitiatedGuildCheck = true;
        // A new check ends any earlier check's grace, so that grace's pending clear, which
        // compares this value, leaves the new check's flag alone.
        guildCheckSuppressUntil = 0;
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
        String lower = trimmed.toLowerCase(Locale.ROOT);

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
                    VetsLogger.debug("Guild check result: other guild ({})", lastNonEmptyCandidate);
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
        if (!isStaffRankCheckResponse(trimmed) && trimmed.length() < 60 && !trimmed.contains(":")) {
            lastNonEmptyCandidate = trimmed;
            return isModInitiatedGuildCheck;
        }

        return false;
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static void setResult(GuildCheckResult result) {
        cachedResult = result;
        lastGuildCheckTime = System.currentTimeMillis();
        VetsConfig.setLong(VetsConfigKeys.VETS_GUILD_CHECK_RESULT, result.persistedValue);
        VetsConfig.setLong(VetsConfigKeys.VETS_LAST_GUILD_CHECK, lastGuildCheckTime);
        if (result == GuildCheckResult.GUILDLESS) {
            // A cached staff flag cannot hold for a player in no guild (a kick while offline
            // sends no Left event). Not on OTHER_GUILD, which says nothing about the rank.
            StaffRankChecker.clearForGuildChange();
        }
    }

    private static void completeCheck() {
        waitingForGuildCheck = false;
        // Keep isModInitiatedGuildCheck true during grace period for suppression
        guildCheckSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;

        // Schedule clearing the mod-initiated flag after grace period
        long graceEnd = guildCheckSuppressUntil;
        TickDelays.runAfter(
                SUPPRESSION_GRACE_MS,
                "Guild check grace",
                () -> {
                    if (guildCheckSuppressUntil == graceEnd) {
                        isModInitiatedGuildCheck = false;
                    }
                });

        // Notify GuildLifecycle that the check completed
        GuildLifecycle.onGuildCheckCompleted();
    }

    private static boolean isInSuppressionGrace() {
        return guildCheckSuppressUntil > 0 && System.currentTimeMillis() < guildCheckSuppressUntil;
    }

    private static boolean isGuildStatsOutputLine(String message) {
        String lower = message.trim().toLowerCase(Locale.ROOT);
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
        String lower = message.trim().toLowerCase(Locale.ROOT);
        return (lower.contains("you must be a")
                        && lower.contains("captain")
                        && lower.contains("to use this command"))
                || (lower.contains("invalid arguments, try:")
                        && lower.contains("rank [name] [rank]"));
    }
}
