package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.utils.mc.McUtils;
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
 * it looks. The session-auth warning it schedules does not check the flag.</p>
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
     * Called by {@link org.wynnvets.listeners.WynntilsEventListener} when a
     * {@code GuildEvent.Joined} or {@code GuildEvent.Left} event fires, and by
     * {@link #scheduleGuildRecheck()}'s poll once Wynntils reports a guild after world join,
     * where no event fired. Both run on the client thread.
     *
     * <p>Clears the mod's own guild check result on both paths, treating Wynntils' guild
     * data as authoritative ({@code guild-recheck-poll-clears-gu-stats-cache}), and
     * re-evaluates guild-dependent state.</p>
     */
    public static void onGuildInfoUpdated() {
        // Wynntils' guild data is treated as authoritative — clear our override
        GuildChecker.clearResult();
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
     * Schedules a delayed guild info re-check.  Wynntils' compass scan runs
     * asynchronously after world join, so guild info may not be populated yet.
     *
     * <p>Polls {@code Models.Guild} on the client thread through {@link TickDelays}: about
     * {@link #GUILD_RECHECK_DELAY_MS} after the call, then up to
     * {@link #GUILD_RECHECK_MAX_ATTEMPTS} times in all, about {@link #GUILD_RECHECK_INTERVAL_MS}
     * apart, each attempt scheduling the next. The first time it reports a guild, it calls
     * {@link #onGuildInfoUpdated()}, which also clears {@link GuildChecker}'s cached result
     * ({@code guild-recheck-poll-clears-gu-stats-cache}). Gives up if {@link #reset()} (a
     * disconnect) has cleared the world-entered flag by the time it polls; a new world
     * entry sets the flag again, so an in-flight poll can survive a quick reconnect.</p>
     */
    private static void scheduleGuildRecheck() {
        TickDelays.runAfter(GUILD_RECHECK_DELAY_MS, "Guild recheck", () -> recheckAttempt(1));
    }

    /**
     * One attempt of the poll {@link #scheduleGuildRecheck()} starts; {@code attempt} counts
     * from 1. Schedules the next attempt itself while attempts remain.
     */
    private static void recheckAttempt(int attempt) {
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
            onGuildInfoUpdated();
            return;
        }

        if (attempt < GUILD_RECHECK_MAX_ATTEMPTS) {
            TickDelays.runAfter(
                    GUILD_RECHECK_INTERVAL_MS, "Guild recheck", () -> recheckAttempt(attempt + 1));
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
        SessionAuthWarning.reset();
        DispatchCoordinator.resetStaffChatEligibilityCache();
    }

    /**
     * Called by {@link GuildChecker} when its {@code /gu stats} check completes.
     * Re-evaluates guild-dependent state and updates registration.
     */
    static void onGuildCheckCompleted() {
        GuildChecker.GuildCheckResult result = GuildChecker.getResult();
        VetsLogger.debug("onGuildCheckCompleted: result={}", result);

        GuildStateManager.sendRegistrationIfReady();

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
     * on a world.
     */
    private static void scheduleDelayedGuildCheck() {
        TickDelays.runAfter(
                GUILD_CHECK_WORLD_JOIN_DELAY_MS,
                "Delayed guild check",
                () -> {
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
