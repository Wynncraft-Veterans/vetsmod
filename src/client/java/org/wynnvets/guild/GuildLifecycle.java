package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.utils.mc.McUtils;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.player.LocalPlayer;
import org.apache.commons.lang3.StringUtils;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.dispatcher.DispatchCoordinator;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.mode.AnniModeManager;
import org.wynnvets.util.TickDelays;

/**
 * The world-join flow and the disconnect reset: what runs when the player enters a Wynncraft world,
 * when Wynntils or vetsmod's own {@code /gu stats} check reports the player's guild afterwards, and
 * when the player leaves the server.
 *
 * <p>It reads the player's guild through {@link GuildStateManager}'s predicates, except in the
 * world-join recheck gate and the recheck poll, which read Wynntils' {@code Models.Guild} directly
 * because they wait on Wynntils' own scan, and in {@link #onGuildCheckCompleted()}, which reads
 * {@link GuildChecker}'s stored result when a {@code /gu stats} check ends; its debug lines log
 * {@code Models.Guild}'s guild name. It calls {@link GuildChecker}, {@link StaffRankChecker} and
 * {@link UnlockManager} directly. A world join can start the guild recheck poll and the delayed
 * {@code /gu stats} check, both run on the client thread a few seconds later through
 * {@link TickDelays}; each gives up if a disconnect has cleared {@link #enteredWorld} by the time
 * it looks, or if a later world join has scheduled a newer one of its kind (latest wins). The
 * session-auth warning it schedules does not check the flag. A Returners join
 * mid-session also hands off to {@link ReturnersReauth}, which re-sends the auth frame so the
 * server's session picks up the member tier.</p>
 */
public final class GuildLifecycle {

    // Delayed guild re-check, scheduled by onEnteredWorld() when Wynntils
    // reports no guild yet (its character-menu scan can land after world join).
    private static final long GUILD_RECHECK_DELAY_MS = 3_000L;
    private static final int GUILD_RECHECK_MAX_ATTEMPTS = 3;
    private static final long GUILD_RECHECK_INTERVAL_MS = 2_000L;

    /** Delay before running our /gu stats check after world join, giving
     *  Wynntils time to finish its own world-join commands. */
    private static final long GUILD_CHECK_WORLD_JOIN_DELAY_MS = 5_000L;

    // Whether the player has entered a world at least once since reset.
    // Package-private for GuildStateManager.canExecuteCommands, which reports it.
    static volatile boolean enteredWorld = false;

    // Bumped each time a recheck poll or a delayed /gu stats check is scheduled; a task that
    // finds a newer number stops, so a quick world hop runs only the latest join's.
    private static final AtomicInteger recheckGeneration = new AtomicInteger();
    private static final AtomicInteger delayedCheckGeneration = new AtomicInteger();

    private GuildLifecycle() {}

    /**
     * Called by {@link org.wynnvets.listeners.WynntilsEventListener} when the
     * player enters a Wynncraft world ({@code WorldStateEvent} with
     * {@code newState == WORLD}).
     *
     * <p>Replaces the old "Welcome to Wynncraft!" chat-message detection. Starts
     * vetsmod's world-join work: the MOTD and (for Returners) the anni stamp, a staff-rank
     * refresh when due, presence registration, the follow-up guild checks, the
     * session-auth warning and the default anni mode. The body is the authority on
     * order.</p>
     */
    public static void onEnteredWorld() {
        enteredWorld = true;
        DispatchCoordinator.resetStaffChatEligibilityCache();

        // Capture player name
        LocalPlayer player = McUtils.player();
        if (player != null && GuildStateManager.playerName.equals(StringUtils.EMPTY)) {
            GuildStateManager.playerName = player.getName().getString();
        }

        // Once-per-session warning when our auth state diverges from what the
        // user probably expects (key present but unverified, or no key at all
        // in Returners). We schedule it
        // shortly after world join so the WS reconnect + auth round-trip has
        // a chance to land first.
        SessionAuthWarning.scheduleOnceForThisSession();

        long currentTime = System.currentTimeMillis();
        if (currentTime - WorldJoinAnnouncer.lastMotdFetchTime
                > WorldJoinAnnouncer.MOTD_FETCH_COOLDOWN_MS) {
            WorldJoinAnnouncer.lastMotdFetchTime = currentTime;
            WorldJoinAnnouncer.fetchAndDisplayMotd();
        }

        if (GuildStateManager.isReturners()) {
            WorldJoinAnnouncer.fetchAndDisplayStampMessage();
        }

        GuildStateManager.refreshStaffStatusIfNeeded(false);

        // Send presence registration to the server.
        GuildStateManager.sendRegistrationIfReady();

        VetsLogger.debug(
                "onEnteredWorld: guild={}, guildless={}, returners={}",
                Models.Guild.getGuildName(),
                GuildStateManager.isGuildless(),
                GuildStateManager.isReturners());

        // When moreReliableGuildCheck is enabled, schedule our own /gu stats
        // check after a delay so Wynntils has time to finish its own
        // world-join commands and the command queue is clear.
        if (VetsConfig.get(VetsConfigKeys.MORE_RELIABLE_GUILD_CHECK)) {
            scheduleDelayedGuildCheck();
        }

        // When guild info is not yet available (empty name), Wynntils may still
        // be scanning the compass menu asynchronously.  Schedule a delayed
        // re-check so we don't stay stuck as "guildless" for the entire session.
        if (GuildStateManager.wynntilsReady && !Models.Guild.isInGuild()) {
            scheduleGuildRecheck();
        }

        // Apply the eligibility-based default anni mode (PASSIVE for
        // enrichment-eligible users, SILENT otherwise) if the user hasn't
        // explicitly picked a mode. No-op once VETS_ANNI_MODE_USER_SET is
        // true. onGuildInfoUpdated(), onGuildCheckCompleted() and an ok auth ack
        // (UnlockManager) re-run it, so a mid-session eligibility flip still moves a
        // still-unset user to the new default. An auth failure or a newly stored key
        // waits for the next of those, so a transient failure can't flicker the bar.
        AnniModeManager.applyStartupDefaultIfNeeded();
    }

    /**
     * Called by {@link #onGuildJoined()} for {@code GuildEvent.Joined}, on the client thread
     * ({@link #onGuildLeft()} does the same work one tick late).
     *
     * <p>A guild change makes the mod's own guild check result stale, so it is cleared, with
     * Wynntils' guild data treated as authoritative; then guild-dependent state is
     * re-evaluated. The recheck poll re-evaluates too, but clears the result only when it
     * disagrees with Wynntils ({@link #recheckAttempt}).</p>
     */
    public static void onGuildInfoUpdated() {
        // Wynntils' guild data is treated as authoritative — clear our override
        GuildChecker.clearResult();
        reevaluateGuildState();
    }

    /** What a guild change or a late Wynntils guild scan re-runs: the stamp and MOTD for a
     *  Returners member still owed them, the presence registration, and the default anni
     *  mode. Client thread. */
    private static void reevaluateGuildState() {
        VetsLogger.debug(
                "onGuildInfoUpdated: guild={}, guildless={}, returners={}",
                Models.Guild.getGuildName(),
                GuildStateManager.isGuildless(),
                GuildStateManager.isReturners());

        if (GuildStateManager.isReturners()) {
            if (!WorldJoinAnnouncer.stampDisplayedThisSession) {
                WorldJoinAnnouncer.fetchAndDisplayStampMessage();
            }

            // Reached only when WorldJoinAnnouncer.fetchAndDisplayMotd() found no
            // local player on this connection's world join: it sets the flag when it
            // starts a fetch and when its toggles are off, so turning printMOTD on
            // mid-session waits for the next world entry. It sets the flag for the
            // standard MOTD too, deliberately, so a guild confirmation that lands
            // after world join does not print a second MOTD.
            if (enteredWorld && !WorldJoinAnnouncer.guildMotdDisplayedThisSession) {
                VetsLogger.debug("Guild info now available — re-fetching guild MOTD");
                WorldJoinAnnouncer.fetchAndDisplayMotd();
            }

            // Re-register now that guild membership is confirmed.
            GuildStateManager.sendRegistrationIfReady();
        }

        // Eligibility may have just flipped (a guild join or leave, or Wynntils'
        // guild scan landing after world join, seen by the recheck poll);
        // re-evaluate the default anni mode for still-unset users.
        AnniModeManager.applyStartupDefaultIfNeeded();
    }

    /**
     * Called by {@link org.wynnvets.listeners.WynntilsEventListener} on the client thread when
     * Wynntils posts {@code GuildEvent.Joined}, which it does only for the "You have joined"
     * chat line, after recording the new guild. Re-evaluates guild state as
     * {@link #onGuildInfoUpdated()} does, then, if the guild is Returners, starts
     * {@link ReturnersReauth}'s re-auth. Last, it retracts a registration the player no longer
     * qualifies for ({@link GuildStateManager#reconcileRegistration}): a waitlister who joined
     * another guild.
     */
    public static void onGuildJoined() {
        onGuildInfoUpdated();
        if (GuildStateManager.isReturners()) {
            ReturnersReauth.onJoinedReturners();
        }
        GuildStateManager.reconcileRegistration(true, "joined");
    }

    /**
     * Called by {@link org.wynnvets.listeners.WynntilsEventListener} on the client thread when
     * Wynntils posts {@code GuildEvent.Left} (a leave, or a kick seen while online).
     *
     * <p>Wynntils posts the event <i>before</i> it clears its guild name, in the same call, so
     * re-reading the guild now would still see Returners (and even re-send a {@code guild}
     * registration). The mod's cached state is dropped at once (the guild check result, and a
     * cached staff flag through {@link StaffRankChecker#clearForGuildChange()}), and the
     * re-evaluation waits one
     * tick ({@code TickDelays.runAfter(0, ...)} runs on the next tick pass), by which time the
     * name is gone. It is followed by {@link GuildStateManager#reconcileRegistration}, which
     * retracts the presence registration. A disconnect in between leaves the cache empty, so
     * that does nothing.</p>
     */
    public static void onGuildLeft() {
        GuildChecker.clearResult();
        // Per-guild cached state that must not outlive the membership is dropped here.
        StaffRankChecker.clearForGuildChange();
        TickDelays.runAfter(
                0,
                "Guild left",
                () -> {
                    reevaluateGuildState();
                    GuildStateManager.reconcileRegistration(true, "left");
                });
    }

    /**
     * Schedules a delayed guild info re-check.  Wynntils' compass scan runs
     * asynchronously after world join, so guild info may not be populated yet.
     *
     * <p>Polls {@code Models.Guild} on the client thread through {@link TickDelays}: about
     * {@link #GUILD_RECHECK_DELAY_MS} after the call, then up to
     * {@link #GUILD_RECHECK_MAX_ATTEMPTS} times in all, about {@link #GUILD_RECHECK_INTERVAL_MS}
     * apart, each attempt scheduling the next. The first time it reports a guild, it clears
     * {@link GuildChecker}'s cached result only if the result contradicts Wynntils
     * ({@link GuildChecker#clearResultIfContradicted}), so a {@code /gu stats} answer that
     * landed first survives, and re-evaluates guild state. Gives up if {@link #reset()} (a
     * disconnect) has cleared the world-entered flag by the time it polls, or if a later world
     * join has started a newer poll: the latest wins. A later join that finds a guild schedules
     * no poll, so an earlier one still runs out its attempts.</p>
     */
    private static void scheduleGuildRecheck() {
        int generation = recheckGeneration.incrementAndGet();
        TickDelays.runAfter(
                GUILD_RECHECK_DELAY_MS, "Guild recheck", () -> recheckAttempt(generation, 1));
    }

    /**
     * One attempt of the poll {@link #scheduleGuildRecheck()} starts, as its
     * {@code generation}; {@code attempt} counts from 1. Schedules the next attempt itself while
     * attempts remain.
     */
    private static void recheckAttempt(int generation, int attempt) {
        if (generation != recheckGeneration.get()) {
            VetsLogger.debug("Guild recheck superseded by a later world join's");
            return;
        }
        if (!GuildStateManager.wynntilsReady || !enteredWorld) {
            VetsLogger.debug(
                    "Guild recheck aborted: wynntilsReady={}, enteredWorld={}",
                    GuildStateManager.wynntilsReady,
                    enteredWorld);
            return;
        }

        boolean inGuild = Models.Guild.isInGuild();
        String name = Models.Guild.getGuildName();
        VetsLogger.debug(
                "Guild recheck attempt {}/{}: inGuild={}, name={}",
                attempt,
                GUILD_RECHECK_MAX_ATTEMPTS,
                inGuild,
                name);

        if (inGuild) {
            VetsLogger.info("Guild info now available after recheck: {}", name);
            GuildChecker.clearResultIfContradicted(true, name);
            reevaluateGuildState();
            return;
        }

        if (attempt < GUILD_RECHECK_MAX_ATTEMPTS) {
            TickDelays.runAfter(
                    GUILD_RECHECK_INTERVAL_MS,
                    "Guild recheck",
                    () -> recheckAttempt(generation, attempt + 1));
            return;
        }

        VetsLogger.debug("Guild recheck exhausted — player appears genuinely guildless");
    }

    /**
     * Reset per-connection state. Called on server disconnect so that the next
     * world join starts fresh. The cached player name and the Wynntils-ready flag are
     * kept.
     */
    public static void reset() {
        WorldJoinAnnouncer.lastMotdFetchTime = 0;
        WorldJoinAnnouncer.guildMotdDisplayedThisSession = false;
        WorldJoinAnnouncer.stampDisplayedThisSession = false;
        enteredWorld = false;
        V1ApiManager.clearRegistration();
        StaffRankChecker.reset();
        GuildChecker.reset();
        UnlockManager.reset();
        ReturnersReauth.reset();
        SessionAuthWarning.reset();
        DispatchCoordinator.resetStaffChatEligibilityCache();
    }

    /**
     * Called by {@link GuildChecker} when its {@code /gu stats} check completes.
     * Re-evaluates guild-dependent state and updates registration: it registers a player who
     * qualifies and retracts a {@code guild} registration the check shows the player no longer
     * qualifies for ({@link GuildStateManager#reconcileRegistration}), such as an ex-member
     * kicked while offline whose cached result still said Returners. Only a result of
     * {@link GuildChecker.GuildCheckResult#isDefinitive() OTHER_GUILD or GUILDLESS} retracts, so
     * a timeout or an unreadable answer never does; an honourary stays registered as one.
     */
    static void onGuildCheckCompleted() {
        GuildChecker.GuildCheckResult result = GuildChecker.getResult();
        VetsLogger.debug("onGuildCheckCompleted: result={}", result);

        GuildStateManager.sendRegistrationIfReady();
        GuildStateManager.reconcileRegistration(result.isDefinitive(), "guild-check");

        if (result == GuildChecker.GuildCheckResult.RETURNERS
                && !WorldJoinAnnouncer.stampDisplayedThisSession) {
            WorldJoinAnnouncer.fetchAndDisplayStampMessage();
        }

        // The /gu stats result can move eligibility either way; re-evaluate the
        // default anni mode for still-unset users.
        AnniModeManager.applyStartupDefaultIfNeeded();
    }

    /**
     * Starts a {@code /gu stats} guild check about {@link #GUILD_CHECK_WORLD_JOIN_DELAY_MS}
     * after world join, to give Wynntils' own world-join commands time to go first. Runs on the
     * client thread through {@link TickDelays}, and queues the check only if the player is still
     * on a world and no later world join has scheduled a newer one (latest wins, so the delay
     * counts from the join the player is on).
     */
    private static void scheduleDelayedGuildCheck() {
        int generation = delayedCheckGeneration.incrementAndGet();
        TickDelays.runAfter(
                GUILD_CHECK_WORLD_JOIN_DELAY_MS,
                "Delayed guild check",
                () -> {
                    if (generation != delayedCheckGeneration.get()) {
                        VetsLogger.debug("Delayed guild check superseded by a later world join's");
                        return;
                    }
                    if (!GuildStateManager.wynntilsReady || !enteredWorld) {
                        VetsLogger.debug(
                                "Delayed guild check aborted: wynntilsReady={}, enteredWorld={}",
                                GuildStateManager.wynntilsReady,
                                enteredWorld);
                        return;
                    }

                    // Verify the world is still loaded before queuing the command
                    if (!Models.WorldState.onWorld()) {
                        VetsLogger.debug("Delayed guild check aborted: not on world");
                        return;
                    }
                    GuildChecker.refreshGuildStatus();
                });
    }
}
