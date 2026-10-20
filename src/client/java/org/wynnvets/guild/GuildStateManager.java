package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.wynnvets.api.V1ApiManager;

/**
 * The player's guild, staff and unlock status: whether they are a Returner, guildless, unlocked
 * or eligible for the enriched vets-anni experience, their staff standing and bearer-key auth
 * state, and the presence registration those decide.
 *
 * <p>Reads guild affiliation from two sources. vetsmod's own {@code /gu stats} check
 * ({@link GuildChecker}, persisted) wins while its result is valid; otherwise it is read
 * live from Wynntils' {@code Models.Guild}, which Wynntils fills from its character-info
 * menu scan and from guild join/leave chat lines. Returners membership is one route through
 * vetsmod's feature gates; a waitlist or honourary unlock ({@link #isWaitlistUnlocked()},
 * {@link #isHonouraryUnlocked()}) is another, and each feature chooses which of the
 * predicates below it gates on.</p>
 *
 * <p>Delegates staff-rank detection to {@link StaffRankChecker}, the {@code /gu stats}
 * guild check to {@link GuildChecker}, and bearer-key auth state (with the legacy unlock
 * markers) to {@link UnlockManager}. Callers outside this package reach the three through this
 * class or through {@link GuildLifecycle}, which calls them directly, except that
 * {@link org.wynnvets.debug.diagnostics.GuildDiagnostics GuildDiagnostics} calls
 * {@code GuildChecker} itself, to read its result and start a check.</p>
 *
 * <p>The world-join flow and the disconnect reset are {@link GuildLifecycle}'s, the world-join
 * MOTD and anni-stamp prints are {@link WorldJoinAnnouncer}'s, and the
 * {@code /wv debug trigger forceChecks} readout is {@code GuildDiagnostics}'.</p>
 */
public class GuildStateManager {

    private static final String RETURNERS_GUILD_NAME = "Returners";

    // Wynntils readiness gate — set to true once CLIENT_STARTED fires and
    // WynntilsEventListener has successfully registered.
    // Package-private for GuildLifecycle, whose recheck guard and background threads check it.
    static volatile boolean wynntilsReady = false;

    // Package-private for GuildLifecycle, which captures it on world join.
    static volatile String playerName = StringUtils.EMPTY;

    /**
     * Check whether Wynntils' own init has run, so its event bus and {@code Models} exist. It turns
     * true inside {@code WynntilsEventListener.register()}, once that listener and the two legacy
     * listeners are on the bus; the rest of {@code VetsmodClient.onClientStarted()}'s bus listeners
     * register just after, on the same thread.
     *
     * @return true once {@code WynntilsEventListener.register()} has run
     */
    public static boolean isWynntilsReady() {
        return wynntilsReady;
    }

    /**
     * Returns {@code true} when the local player can <em>execute</em>
     * {@code /wv distribute} &mdash; i.e. is {@code CHIEF} or {@code OWNER}
     * of some guild.
     *
     * <p>Two sources, in order: first the server-confirmed rank from the
     * vetsmod WS auth ack ({@link #confirmedStaffRank()}, which survives world joins:
     * each auth ack rewrites it, and an auth failure or leaving the server clears it),
     * then Wynntils' live {@code Models.Guild.getGuildRank()}
     * as the fallback for any chief the auth ack has not confirmed &mdash; a
     * chief of another guild, say, or a Returners chief without a confirming
     * auth ack.
     * Trying the confirmed rank first avoids the execution-time race where
     * the live rank is still {@code null} (Wynntils fills it from its character-menu
     * scan, which lands shortly after the first world join of a launch, or from a guild
     * join or rank-change chat line) and a
     * confirmed Returners chief would otherwise be refused. Called from
     * {@code /wv distribute}'s executor-time check ({@code ensureChief}), not
     * from its {@code .requires} (that is {@link #isStaffOfAnyGuild()}).</p>
     */
    public static boolean isChiefOfAnyGuild() {
        String confirmed = confirmedStaffRank();
        if ("chief".equals(confirmed) || "owner".equals(confirmed)) return true;
        if (!wynntilsReady) return false;
        GuildRank rank = Models.Guild.getGuildRank();
        return rank == GuildRank.CHIEF || rank == GuildRank.OWNER;
    }

    /**
     * Returns {@code true} when the local player is server-confirmed vets
     * staff, or at least {@code CAPTAIN} in their current guild by Wynntils'
     * live rank &mdash; the visibility tier for {@code /wv distribute}.
     * Mirrors {@link #isChiefOfAnyGuild()}'s dual-source logic with a wider
     * band: confirmed staff (strategist and up, since the 2026-07
     * permission restructure retired captain server-side) plus anyone
     * Captain&plus; in-game. The members of that band below chief can't run
     * chief-only actions but benefit from knowing the command exists.
     *
     * <p>Used by {@code /wv distribute}'s {@code .requires(...)} predicate
     * so brigadier surfaces it in autocomplete reliably for staff without
     * leaking it to non-staff.</p>
     */
    public static boolean isStaffOfAnyGuild() {
        if (isConfirmedStaff()) return true;
        if (!wynntilsReady) return false;
        GuildRank rank = Models.Guild.getGuildRank();
        if (rank == null) return false;
        return rank.ordinal() >= GuildRank.CAPTAIN.ordinal();
    }

    /**
     * Get whether the player's guild is "Returners".
     *
     * <p>If the mod's own guild check ({@link GuildChecker}) has a valid
     * (non-expired) result, that takes precedence over Wynntils'
     * {@code Models.Guild} data, whose guild name can stay empty for
     * extended periods after world join.</p>
     *
     * @return true if guild is "Returners", false otherwise
     */
    public static boolean isReturners() {
        if (GuildChecker.hasValidResult()) {
            return GuildChecker.getResult() == GuildChecker.GuildCheckResult.RETURNERS;
        }
        if (!wynntilsReady) return false;
        return RETURNERS_GUILD_NAME.equals(Models.Guild.getGuildName());
    }

    /**
     * Whether the in-game guild chat channel the local player is reading
     * belongs to VETS.
     *
     * <p>Wynncraft's guild channel only ever carries the guild you are
     * actually in, and its rank pills carry no guild identity — so this is
     * the only thing standing between vetsmod's cosmetic guild-chat
     * treatment (rank relabelling, supporter glints) and it being applied
     * to some unrelated guild's chat.</p>
     *
     * <p>Membership in Returners is the entire test, and deliberately so. An honourary member — a
     * vets community member whose in-game guild is somewhere else — fails it for the same reason
     * any other outsider does: the channel they're reading isn't ours. Genuine vets chat reaches them
     * over the WebSocket bridge instead, where {@link org.wynnvets.chat.OutboundDisplayHandler
     * OutboundDisplayHandler} keeps the full treatment. Note that {@link #isHonouraryUnlocked()} is
     * <em>not</em> usable as a faster negative signal here: it ORs in a legacy on-disk unlock marker
     * that is never cleared, so an honourary member who later joined Returners would keep failing
     * forever.</p>
     *
     * <p>{@link #isReturners()} resolves in this order: the
     * {@link GuildChecker} cache (persisted, 3-day expiry, so instant when
     * warm), then Wynntils' {@code Models.Guild} (which can stay empty for
     * minutes after world join). When neither has landed this returns
     * {@code false} and the caller leaves the server's own rendering alone —
     * a real Returner may see vanilla pills early in a session, which is the
     * harmless direction to be wrong in, and it self-heals as soon as either source
     * lands: Wynntils' guild scan, or the {@code /gu stats} check that
     * {@link GuildLifecycle#scheduleDelayedGuildCheck()} runs a few seconds after world join while
     * {@code moreReliableGuildCheck} is on (the default).</p>
     *
     * @return true when guild chat should get vetsmod's treatment
     */
    public static boolean isVetsGuildChat() {
        return isReturners();
    }

    /**
     * Get whether the player is not in a guild.
     *
     * <p>Checks the mod's own guild check result first, falling back to
     * Wynntils' {@code Models.Guild}. Until one of them reports a guild it answers
     * {@code true}, so a guild member can read as guildless early in a session: the
     * opposite default to {@link #isReturners()}.</p>
     *
     * @return true if player is not in a guild, false otherwise
     */
    public static boolean isGuildless() {
        if (UnlockManager.isDebugForceGuildlessUnlocked()) {
            return true;
        }
        if (GuildChecker.hasValidResult()) {
            return GuildChecker.getResult() == GuildChecker.GuildCheckResult.GUILDLESS;
        }
        if (!wynntilsReady) return true;
        return !Models.Guild.isInGuild();
    }

    /**
     * Get whether the mod is unlocked (Returners, waitlist, or honourary).
     *
     * @return true if mod is unlocked, false otherwise
     */
    public static boolean isUnlocked() {
        if (UnlockManager.isDebugForceGuildlessUnlocked()) {
            return true;
        }
        return isReturners()
                || UnlockManager.isWaitlistUnlocked()
                || UnlockManager.isHonouraryUnlocked();
    }

    /**
     * Whether the player counts as waitlist-unlocked: a waitlist-tier auth this session,
     * or a legacy on-disk waitlist marker. The marker arm is meant to go
     * ({@code legacy-unlock-markers-still-grant-client-unlock}). Does not check
     * guildlessness itself; callers that mean the waitlist tier pair it with
     * {@link #isGuildless()}.
     *
     * @return true if waitlist-unlocked, false otherwise
     */
    public static boolean isWaitlistUnlocked() {
        return UnlockManager.isWaitlistUnlocked();
    }

    /**
     * Whether the player counts as honourary-unlocked: an honourary-tier auth this
     * session, or a legacy on-disk honourary marker. The marker arm is meant to go
     * ({@code legacy-unlock-markers-still-grant-client-unlock}).
     *
     * @return true if honourary-unlocked, false otherwise
     */
    public static boolean isHonouraryUnlocked() {
        return UnlockManager.isHonouraryUnlocked();
    }

    /**
     * Whether the player is eligible for the enriched vets-anni experience.
     *
     * <p>Three rank-signal trip-wires (any one triggers eligibility): Returners guild membership,
     * guildless + waitlist-unlocked, or honourary-unlocked. It is the predicate behind
     * {@link org.wynnvets.mwe.anni.render.AnniCommandRenderer AnniCommandRenderer}'s "external vs
     * vets" test (enriched printout vs the legacy stamp-only fallback), and it drives the
     * eligibility-based default anni mode (PASSIVE if eligible, SILENT otherwise) in
     * {@link org.wynnvets.mwe.anni.mode.AnniModeManager AnniModeManager}. {@code /wv help}
     * also uses it as the vet gate.</p>
     *
     * @return true if eligible for enrichment, false otherwise
     */
    public static boolean isEligibleForEnrichment() {
        if (isReturners()) return true;
        if (isGuildless() && isWaitlistUnlocked()) return true;
        return isHonouraryUnlocked();
    }

    /**
     * Enable/disable debug override that forces the user to be treated as guildless and unlocked.
     * Nothing in vetsmod calls this today, so the override is never on
     * ({@code unlock-debug-flag-tier-asymmetry}).
     *
     * @param enabled true to force guildless+unlocked behavior, false to use normal state
     */
    public static void setDebugForceGuildlessUnlocked(boolean enabled) {
        UnlockManager.setDebugForceGuildlessUnlocked(enabled);
    }

    /**
     * Check if debug override is active.
     *
     * @return true when guildless+unlocked override is enabled
     */
    public static boolean isDebugForceGuildlessUnlocked() {
        return UnlockManager.isDebugForceGuildlessUnlocked();
    }

    /**
     * Whether vetsmod's Returners-only features are enabled; today this is
     * {@link #isReturners()}. Most other features gate on {@link #isUnlocked()} or the
     * tier predicates instead.
     *
     * @return true if features should be enabled, false otherwise
     */
    public static boolean areFeaturesEnabled() {
        return isReturners();
    }

    /**
     * Mark Wynntils as ready: its own init has run, and {@code WynntilsEventListener} and the two
     * legacy listeners are on its bus. Called once from
     * {@link org.wynnvets.listeners.WynntilsEventListener#register()} after the event bus is
     * available.
     */
    public static void setWynntilsReady() {
        wynntilsReady = true;
    }

    /**
     * Whether {@link GuildLifecycle#onEnteredWorld()} has started since the last reset. This
     * does not mean Wynntils' guild info is available yet: that can lag world join (see
     * {@link GuildLifecycle#scheduleGuildRecheck()}).
     *
     * @return true once the first world-join has been processed
     */
    public static boolean canExecuteCommands() {
        return GuildLifecycle.enteredWorld;
    }

    public static String playerName() {
        return playerName;
    }

    /**
     * Get whether the user is staff by the cached {@code /gu rank} probe: Captain+ in
     * whatever guild the player was in when it last answered. The probe does not check
     * which guild that is.
     *
     * <p><b>Note:</b> This reads the client-side {@link StaffRankChecker}
     * cache (refreshed daily via {@code /gu rank}). It is suitable for UX
     * gates ({@code /v}, {@code /a}, {@code /encourage}, {@code /wv list world}) but NOT
     * for high-trust actions like {@code /caution} /
     * {@code /warn} / {@code /eject}, any of whose commits can end in a real in-game
     * {@code /gu} command. Those use
     * {@link #isConfirmedStaff()} instead -- the server-side roster check
     * resolved at WS auth time.</p>
     *
     * @return true when staff, false otherwise
     */
    public static boolean isStaff() {
        return StaffRankChecker.isStaff();
    }

    /**
     * Server-confirmed staff status from the most recent successful WS
     * auth. Resolved by temporary-server against the canonical staff
     * roster (see v1_protocol.md §1.8). This is the only staff signal
     * that should gate {@code /caution} / {@code /warn} / {@code /eject},
     * because a commit from any of them can end in a real {@code /gu kick} /
     * {@code /gu rank} command (when the server's ack reports an eject).
     *
     * @return true when the most recent ok auth ack reported {@code is_staff=true};
     *         an auth failure or leaving the server clears it (a newly stored
     *         {@code /unlock} key does not, until its own ack arrives)
     */
    public static boolean isConfirmedStaff() {
        return V1ApiManager.isConfirmedStaff();
    }

    /**
     * Server-confirmed in-game guild rank for the authenticated user --
     * one of "strategist", "chief", "owner", or empty string when not
     * confirmed-staff. Captain was retired in the 2026-07 permission
     * restructure and the server simply never sends it: {@code staff_rank}
     * is only populated once the roster resolves {@code is_staff=true}.
     *
     * <p>Pure delegate to {@link V1ApiManager#confirmedStaffRank()}. The
     * only caller is {@link #isChiefOfAnyGuild()} -- nothing here feeds
     * {@code /eject}, whose kick-vs-demote branch reads the server's
     * {@code suggested_dispatch} string instead.</p>
     *
     * @return server-confirmed staff rank, or empty when not staff
     */
    public static String confirmedStaffRank() {
        return V1ApiManager.confirmedStaffRank();
    }

    /**
     * Gets the player's guild rank as a lowercase string for pill display,
     * read live from {@code Models.Guild}.
     *
     * @return one of "captain", "strategist", "chief", or "owner" when the
     *         rank is staff-level, otherwise empty
     */
    public static String selfStaffRank() {
        if (!wynntilsReady) return StringUtils.EMPTY;
        GuildRank rank = Models.Guild.getGuildRank();
        if (rank == null) {
            return StringUtils.EMPTY;
        }
        return switch (rank) {
            case CAPTAIN -> "captain";
            case STRATEGIST -> "strategist";
            case CHIEF -> "chief";
            case OWNER -> "owner";
            default -> StringUtils.EMPTY;
        };
    }

    /**
     * Restore the persisted staff-rank and guild-check results from config, and clear
     * {@link UnlockManager}'s session fields (the stored key itself is read on demand).
     */
    public static void loadPersistedState() {
        StaffRankChecker.loadPersistedState();
        GuildChecker.loadPersistedState();
        UnlockManager.loadPersistedState();
    }

    /**
     * Check if a mod-initiated staff rank check is currently running.
     *
     * @return true if currently waiting for /gu rank response
     */
    public static boolean isCheckingStaffStatus() {
        return StaffRankChecker.isCheckingStaffStatus();
    }

    /**
     * Check if currently processing a mod-initiated staff rank check command.
     *
     * @return true during a mod-initiated rank check or the brief suppression grace
     *         period after one completes
     */
    public static boolean isProcessingModStaffRankCheck() {
        return StaffRankChecker.isProcessingModStaffRankCheck();
    }

    /**
     * Refresh staff status when needed.
     *
     * @param forceRefresh true to bypass daily cooldown
     * @return true when a refresh was started, false otherwise
     */
    public static boolean refreshStaffStatusIfNeeded(boolean forceRefresh) {
        return StaffRankChecker.refreshStaffStatusIfNeeded(forceRefresh);
    }

    /**
     * Process incoming chat messages to detect staff rank-check responses, capturing the
     * local player name on the first call that has a player.
     *
     * @param component the chat message Component (currently unused)
     * @param message   The plain text chat message
     */
    public static void processMessage(Component component, String message) {
        // Capture player name on first opportunity
        if (playerName.equals(StringUtils.EMPTY)) {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            if (player != null) {
                playerName = player.getName().getString();
            }
        }

        StaffRankChecker.processMessage(message);
    }

    /**
     * Process incoming chat messages for guild check responses.
     * Returns {@code true} when the message should be suppressed from display.
     *
     * @param message the plain text chat message
     * @return {@code true} if the message was consumed by the guild checker
     */
    public static boolean processGuildCheckMessage(String message) {
        return GuildChecker.processAndShouldSuppress(message);
    }

    /**
     * Check if currently processing a mod-initiated guild check. Nothing calls it at
     * present.
     *
     * @return true if guild check is active or in suppression grace period
     */
    public static boolean isProcessingModGuildCheck() {
        return GuildChecker.isProcessingModGuildCheck();
    }

    /** Local-only outcome of running {@code /unlock &lt;key&gt;}. The eventual
     *  server verdict on the key arrives asynchronously via
     *  {@link #onAuthSuccess(String)} / {@link #onAuthFailure(String)}. */
    public enum UnlockAttemptResult {
        /** No key was provided (empty string). */
        MISSING_KEY,
        /** The key didn't pass the local shape sanity check (length / charset). */
        MALFORMED,
        /** Key passed local checks and was persisted; its auth frame has been sent, or will
         *  be on the next inbound (re)connect. */
        STORED_VERIFYING,
    }

    /**
     * Attempt to store a vetsmod auth key and verify it with the server.
     * Delegates to {@link UnlockManager}; the network outcome is reported
     * asynchronously via {@link #onAuthSuccess(String)} /
     * {@link #onAuthFailure(String)}.
     *
     * @param key The bearer key (43-char URL-safe base64 from /vetsmod)
     * @return the local-only outcome
     */
    public static UnlockAttemptResult tryUnlock(String key) {
        return UnlockManager.tryUnlock(key);
    }

    /** Called by {@link org.wynnvets.api.InboundAckRouter InboundAckRouter} when the server's
     *  auth-frame ack returns ok. */
    public static void onAuthSuccess(String tier) {
        UnlockManager.onAuthSuccess(tier);
    }

    /** Called by {@link org.wynnvets.api.InboundAckRouter InboundAckRouter} when an inbound
     *  error ack is classed as an auth failure. A staff-action-shaped ack never is; any other
     *  error ack is when it arrives while an auth ack is awaited (a rejected {@code auth} frame,
     *  typically) or, when no staff-action callback is pending, when its detail is an auth
     *  rejection or the server refusing another frame because the session is unauthenticated.
     *  {@code InboundAckRouter.route} has the exact test. */
    public static void onAuthFailure(String detail) {
        UnlockManager.onAuthFailure(detail);
    }

    /** @return {@code true} from an ok auth ack until an auth failure, a newly stored
     *  {@code /unlock} key or a disconnect clears it. */
    public static boolean isAuthenticatedThisSession() {
        return UnlockManager.isAuthenticatedThisSession();
    }

    /** @return {@code true} when a key is stored locally regardless of validation */
    public static boolean hasStoredAuthKey() {
        return UnlockManager.hasStoredKey();
    }

    /** @return {@code true} if the player has any legacy SHA-256 unlock state
     *  (waitlist or honourary) on disk from before the migration. */
    public static boolean hasLegacyPasswordUnlock() {
        return UnlockManager.legacyWaitlistMarker() || UnlockManager.legacyHonouraryMarker();
    }

    /** @return the tier from the latest ok auth ack (e.g. {@code "member"},
     *  {@code "waitlist"}, {@code "honourary"}, {@code "other"}), or empty string when
     *  none is standing: not yet authenticated, or cleared by an auth failure, a newly
     *  stored {@code /unlock} key or a disconnect. */
    public static String currentAuthTier() {
        return UnlockManager.currentTier();
    }

    /** @return the standing auth-failure reason from the server, or empty string when
     *  none is standing (a later successful auth, a newly stored {@code /unlock} key or a
     *  disconnect clears it). It can also be an "Authentication required" refusal of a non-auth
     *  frame. */
    public static String lastAuthFailureReason() {
        return UnlockManager.lastAuthFailureReason();
    }

    /**
     * Send a presence registration to the server if the player is eligible.
     *
     * <p>Determines the player's tier (guild / waitlist / honourary) and sends
     * a {@code register} frame via the inbound WebSocket.  The payload is
     * cached inside {@link V1ApiManager} so it is automatically re-sent on
     * reconnect.  Called from {@link GuildLifecycle#onEnteredWorld()},
     * {@link GuildLifecycle#onGuildInfoUpdated()},
     * {@link org.wynnvets.debug.diagnostics.GuildDiagnostics#forceGuildRecheck()
     * GuildDiagnostics#forceGuildRecheck()},
     * {@link GuildLifecycle#onGuildCheckCompleted()} and
     * {@link org.wynnvets.guild.UnlockManager#onAuthSuccess UnlockManager#onAuthSuccess}
     * &mdash; five callers. {@link #tryUnlock} is <b>not</b> one of them; it is a
     * pure delegate to {@link UnlockManager#tryUnlock(String)} and calls nothing
     * else here.</p>
     */
    public static void sendRegistrationIfReady() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        String uuid = player.getUUID().toString();
        String username = player.getName().getString();

        String tier;
        if (isReturners()) {
            tier = "guild";
        } else if (isHonouraryUnlocked()) {
            tier = "honourary";
        } else if (isGuildless() && isWaitlistUnlocked()) {
            tier = "waitlist";
        } else {
            return; // not eligible for registration
        }

        V1ApiManager.sendRegistration(uuid, username, tier);
    }
}
