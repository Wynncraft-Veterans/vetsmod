package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.dispatcher.CommandDispatcher;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.ondemand.MotdFetcher;
import org.wynnvets.fetcher.ondemand.StampFetcher;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.mode.AnniModeManager;

/**
 * Central authority for the player's guild membership, staff rank, and
 * feature-gate state.
 *
 * <p>Reads guild affiliation from two sources. vetsmod's own {@code /gu stats} check
 * ({@link GuildChecker}, persisted) wins while its result is valid; otherwise it is read
 * live from Wynntils' {@code Models.Guild}, which Wynntils fills from its character-info
 * menu scan and from guild join/leave chat lines. World-join triggers are
 * provided by {@link org.wynnvets.listeners.WynntilsEventListener} via
 * {@code WorldStateEvent}. Returners membership is one route through vetsmod's feature
 * gates; a waitlist or honourary unlock ({@link #isWaitlistUnlocked()},
 * {@link #isHonouraryUnlocked()}) is another, and each feature chooses which of the
 * predicates below it gates on.</p>
 *
 * <p>Delegates staff-rank detection to {@link StaffRankChecker}, the {@code /gu stats}
 * guild check to {@link GuildChecker}, and bearer-key auth state (with the legacy unlock
 * markers) to {@link UnlockManager}. Those three are package-private, so callers outside
 * this package go through this class's facade methods.</p>
 */
public class GuildStateManager {

    private static final String RETURNERS_GUILD_NAME = "Returners";

    // Wynntils readiness gate — set to true once CLIENT_STARTED fires and
    // WynntilsEventListener has successfully registered.
    private static volatile boolean wynntilsReady = false;

    private static volatile String playerName = StringUtils.EMPTY;

    // State tracking for MOTD to prevent duplicate fetches
    private static volatile long lastMotdFetchTime = 0;
    private static final long MOTD_FETCH_COOLDOWN_MS = 1_000L;

    // Delayed guild re-check, scheduled by onEnteredWorld() when Wynntils
    // reports no guild yet (its character-menu scan can land after world join).
    private static final long GUILD_RECHECK_DELAY_MS = 3_000L;
    private static final int GUILD_RECHECK_MAX_ATTEMPTS = 3;
    private static final long GUILD_RECHECK_INTERVAL_MS = 2_000L;

    // Set when fetchAndDisplayMotd() starts either MOTD fetch (guild or
    // standard); blocks onGuildInfoUpdated()'s re-fetch for the session.
    private static volatile boolean guildMotdDisplayedThisSession = false;

    // Set when a stamp fetch starts (whether or not anything is then shown);
    // consulted only by onGuildInfoUpdated() and onGuildCheckCompleted().
    private static volatile boolean stampDisplayedThisSession = false;

    // Whether the player has entered a world at least once since reset.
    private static volatile boolean enteredWorld = false;

    /**
     * Check if Wynntils is fully initialised and safe to access Models.
     *
     * @return true once Wynntils event bus registration has completed
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
     * {@link #scheduleDelayedGuildCheck()} runs a few seconds after world join while
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
     * {@link AnniModeManager}. {@code /wv help} also uses it as the vet gate.</p>
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
     * Mark Wynntils as fully initialised.  Called once from
     * {@link org.wynnvets.listeners.WynntilsEventListener#register()} after
     * the event bus is available.
     */
    public static void setWynntilsReady() {
        wynntilsReady = true;
    }

    /**
     * Whether {@link #onEnteredWorld()} has started since the last reset. This does not
     * mean Wynntils' guild info is available yet: that can lag world join (see
     * {@link #scheduleGuildRecheck()}).
     *
     * @return true once the first world-join has been processed
     */
    public static boolean canExecuteCommands() {
        return enteredWorld;
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
        CommandDispatcher.resetStaffChatEligibilityCache();

        // Capture player name
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != null && playerName.equals(StringUtils.EMPTY)) {
            playerName = player.getName().getString();
        }

        // Once-per-session warning when our auth state diverges from what the
        // user probably expects (key present but unverified, or no key at all
        // with legacy unlock markers / Returners membership). We schedule it
        // shortly after world join so the WS reconnect + auth round-trip has
        // a chance to land first.
        SessionAuthWarning.scheduleOnceForThisSession();

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastMotdFetchTime > MOTD_FETCH_COOLDOWN_MS) {
            lastMotdFetchTime = currentTime;
            fetchAndDisplayMotd();
        }

        if (isReturners()) {
            fetchAndDisplayStampMessage();
        }

        refreshStaffStatusIfNeeded(false);

        // Send presence registration to the server.
        sendRegistrationIfReady();

        VetsLogger.debug(
                "onEnteredWorld: guild={}, guildless={}, returners={}",
                Models.Guild.getGuildName(),
                isGuildless(),
                isReturners());

        // When moreReliableGuildCheck is enabled, schedule our own /gu stats
        // check after a delay so Wynntils has time to finish its own
        // world-join commands and the command queue is clear.
        if (VetsConfig.get(VetsConfig.MORE_RELIABLE_GUILD_CHECK)) {
            scheduleDelayedGuildCheck();
        }

        // When guild info is not yet available (empty name), Wynntils may still
        // be scanning the compass menu asynchronously.  Schedule a delayed
        // re-check so we don't stay stuck as "guildless" for the entire session.
        if (!UnlockManager.isDebugForceGuildlessUnlocked()
                && wynntilsReady
                && !Models.Guild.isInGuild()) {
            scheduleGuildRecheck();
        }

        // Apply the eligibility-based default anni mode (PASSIVE for
        // enrichment-eligible users, SILENT otherwise) if the user hasn't
        // explicitly picked a mode. No-op once VETS_ANNI_MODE_USER_SET is
        // true. onGuildInfoUpdated() re-runs it so that a mid-session eligibility
        // flip still moves a still-unset user to the new default. Today only flips
        // that reach that method (a Wynntils guild join/leave event, or
        // scheduleGuildRecheck()'s poll) do; flips that arrive another way (an auth
        // ack or failure, a newly stored key, or the /gu stats check completing)
        // wait for the next world join (anni-default-not-reapplied-on-auth-tier-flip).
        AnniModeManager.applyStartupDefaultIfNeeded();
    }

    /**
     * Called by {@link org.wynnvets.listeners.WynntilsEventListener} when a
     * {@code GuildEvent.Joined} or {@code GuildEvent.Left} event fires, and by
     * {@link #scheduleGuildRecheck()}'s poll (on its own thread) once Wynntils reports a
     * guild after world join, where no event fired.
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
                isGuildless(),
                isReturners());

        if (isReturners()) {
            if (!stampDisplayedThisSession) {
                fetchAndDisplayStampMessage();
            }

            // Only reached if onEnteredWorld's MOTD fetch never ran (e.g. no local
            // player yet). fetchAndDisplayMotd() sets guildMotdDisplayedThisSession for
            // the standard MOTD too, deliberately, so a guild confirmation that lands
            // after world join does not print a second MOTD.
            if (enteredWorld && !guildMotdDisplayedThisSession) {
                VetsLogger.debug("Guild info now available — re-fetching guild MOTD");
                fetchAndDisplayMotd();
            }

            // Re-register now that guild membership is confirmed.
            sendRegistrationIfReady();
        }

        // Eligibility may have just flipped (a guild join or leave, or Wynntils'
        // guild scan landing after world join, seen by the recheck poll);
        // re-evaluate the default anni mode for still-unset users.
        AnniModeManager.applyStartupDefaultIfNeeded();
    }

    private static void fetchAndDisplayMotd() {
        // Check if auto-messages are enabled (global gate)
        if (!VetsConfig.get(VetsConfig.VETS_AUTOMESSAGE)) {
            VetsLogger.debug("Auto-messages disabled, skipping MOTD");
            return;
        }

        // Check if MOTD printing is enabled (user toggle)
        if (!VetsConfig.get(VetsConfig.PRINT_MOTD)) {
            VetsLogger.debug("printMOTD disabled, skipping MOTD");
            return;
        }

        VetsLogger.debug("Fetching MOTD");
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player != null) {
            // Mark MOTD as shown now — prevents onGuildInfoUpdated() from re-fetching
            // if guild info arrives after WorldStateEvent.WORLD (race condition).
            guildMotdDisplayedThisSession = true;

            // Use guild MOTD for eligible users (Returners, waitlist-unlocked, honourary-unlocked)
            boolean useGuildMotd =
                    isReturners()
                            || (isGuildless() && isWaitlistUnlocked())
                            || isHonouraryUnlocked();

            // Use NewBlock so the motd gets a fresh full [VETSMOD] badge and stays
            // visually separate from the anni-motd block rather than collapsing into
            // one compact-badged run. The two are fetched independently and print in
            // completion order (world-join-motd-and-anni-motd-print-in-completion-order).
            if (useGuildMotd) {
                MotdFetcher.fetchGuildMotd()
                        .thenAccept(
                                guildMotdComponent -> {
                                    String text = guildMotdComponent.getString();
                                    if (text != null && !text.isEmpty()) {
                                        ChatUtils.sendLocalMessageNewBlock(guildMotdComponent);
                                    } else {
                                        // Fall back to standard MOTD if guild MOTD is empty
                                        MotdFetcher.fetchMotd()
                                                .thenAccept(
                                                        motdComponent -> {
                                                            ChatUtils.sendLocalMessageNewBlock(
                                                                    motdComponent);
                                                        });
                                    }
                                });
            } else {
                MotdFetcher.fetchMotd()
                        .thenAccept(
                                motdComponent -> {
                                    ChatUtils.sendLocalMessageNewBlock(motdComponent);
                                });
            }
        }
    }

    /**
     * Fetch and display the annihilation stamp message (if applicable)
     */
    private static void fetchAndDisplayStampMessage() {
        // Check if auto-messages are enabled (global gate)
        if (!VetsConfig.get(VetsConfig.VETS_AUTOMESSAGE)) {
            VetsLogger.debug("Auto-messages disabled, skipping stamp");
            return;
        }

        // Check if annihilation printing is enabled (user toggle)
        if (!VetsConfig.get(VetsConfig.PRINT_ANNI)) {
            VetsLogger.debug("printANNI disabled, skipping stamp");
            return;
        }

        VetsLogger.debug("Fetching annihilation stamp");
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player != null) {
            stampDisplayedThisSession = true;
            StampFetcher.fetchStampAndCreateMessage()
                    .thenAccept(
                            stampMessage -> {
                                if (stampMessage != null) {
                                    VetsLogger.debug("Displaying annihilation countdown");
                                    // NewBlock so the anni-motd gets a fresh full [VETSMOD] badge,
                                    // separate from the motd block.
                                    ChatUtils.sendLocalMessageNewBlock(stampMessage);
                                }
                            });
        }
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

    /** Called by {@link V1ApiManager} when the server's auth-frame ack returns ok. */
    public static void onAuthSuccess(String tier) {
        UnlockManager.onAuthSuccess(tier);
    }

    /** Called by {@link V1ApiManager} when an inbound error ack is classed as an auth
     *  failure: a rejected {@code auth} frame, any other error ack that arrives while an
     *  auth ack is awaited, or (when no staff-action callback claims it) the server refusing
     *  another frame because the session is unauthenticated. V1ApiManager's inbound handler
     *  has the exact test. */
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
     *  none is standing: not yet authenticated, or cleared by an auth failure or a newly
     *  stored {@code /unlock} key. */
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
     * Schedules a delayed guild info re-check.  Wynntils' compass scan runs
     * asynchronously after world join, so guild info may not be populated yet.
     *
     * <p>Sleeps {@link #GUILD_RECHECK_DELAY_MS} on its own thread, then polls
     * {@code Models.Guild} up to {@link #GUILD_RECHECK_MAX_ATTEMPTS} times,
     * {@link #GUILD_RECHECK_INTERVAL_MS} apart, reading it off the client thread. The first
     * time it reports a guild, it calls {@link #onGuildInfoUpdated()} from that same thread,
     * which also clears {@link GuildChecker}'s cached result
     * ({@code guild-recheck-poll-clears-gu-stats-cache}). Gives up if {@link #reset()} (a
     * disconnect) has cleared the world-entered flag by the time it polls; a new world
     * entry sets the flag again, so an in-flight poll can survive a quick reconnect.</p>
     */
    private static void scheduleGuildRecheck() {
        new Thread(
                        () -> {
                            try {
                                Thread.sleep(GUILD_RECHECK_DELAY_MS);

                                for (int attempt = 1;
                                        attempt <= GUILD_RECHECK_MAX_ATTEMPTS;
                                        attempt++) {
                                    if (!wynntilsReady || !enteredWorld) {
                                        VetsLogger.debug(
                                                "Guild recheck aborted: wynntilsReady={}, enteredWorld={}",
                                                wynntilsReady,
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
                                        VetsLogger.info(
                                                "Guild info now available after recheck: {}", name);
                                        onGuildInfoUpdated();
                                        return;
                                    }

                                    if (attempt < GUILD_RECHECK_MAX_ATTEMPTS) {
                                        Thread.sleep(GUILD_RECHECK_INTERVAL_MS);
                                    }
                                }

                                VetsLogger.debug(
                                        "Guild recheck exhausted — player appears genuinely guildless");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                VetsLogger.debug("Guild recheck interrupted");
                            }
                        },
                        "vetsmod-guild-recheck")
                .start();
    }

    /**
     * Reads guild membership and rank from Wynntils' {@code Models.Guild}
     * and reports them to chat alongside vetsmod's own guild and staff
     * state.  If Wynntils reports a guild, re-evaluates dependent state
     * directly; it deliberately does not call {@link #onGuildInfoUpdated()},
     * which would clear {@code GuildChecker}.  Then forces a staff rank
     * refresh and starts our own {@code /gu stats} guild check, each
     * skipped if one is already in flight.
     *
     * <p>Intended for use from {@code /wv debug trigger forceChecks}.</p>
     */
    public static void forceGuildRecheck() {
        if (!wynntilsReady) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Wynntils is not ready yet — cannot check guild state.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        String guildName = Models.Guild.getGuildName();
        boolean inGuild = Models.Guild.isInGuild();
        GuildRank rank = Models.Guild.getGuildRank();

        ChatUtils.sendLocalMessage(
                Component.literal("Force Guild Check Results:").withStyle(ChatFormatting.GOLD));

        sendDiagLine(
                "Wynntils Guild Name",
                guildName.isEmpty() ? "(empty)" : guildName,
                guildName.isEmpty() ? ChatFormatting.RED : ChatFormatting.GREEN);
        sendDiagLine(
                "Wynntils isInGuild",
                String.valueOf(inGuild),
                inGuild ? ChatFormatting.GREEN : ChatFormatting.RED);
        sendDiagLine(
                "Wynntils Guild Rank",
                rank == null ? "(null)" : rank.name(),
                rank == null ? ChatFormatting.RED : ChatFormatting.GREEN);

        // GuildChecker state
        GuildChecker.GuildCheckResult gcResult = GuildChecker.getResult();
        boolean gcValid = GuildChecker.hasValidResult();
        long gcAge =
                GuildChecker.getLastCheckTime() > 0
                        ? (System.currentTimeMillis() - GuildChecker.getLastCheckTime()) / 1000
                        : -1;

        sendDiagLine(
                "GuildChecker result",
                gcResult.name() + (gcValid ? "" : " (expired)"),
                gcValid ? ChatFormatting.GREEN : ChatFormatting.RED);
        if (gcAge >= 0) {
            String ageStr =
                    gcAge < 3600
                            ? gcAge + "s"
                            : gcAge < 86400 ? (gcAge / 3600) + "h" : (gcAge / 86400) + "d";
            sendDiagLine("GuildChecker age", ageStr, ChatFormatting.AQUA);
        }

        sendDiagLine(
                "VetsMod isReturners",
                String.valueOf(isReturners()),
                isReturners() ? ChatFormatting.GREEN : ChatFormatting.RED);
        sendDiagLine(
                "VetsMod isGuildless",
                String.valueOf(isGuildless()),
                isGuildless() ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        sendDiagLine(
                "VetsMod isStaff",
                String.valueOf(isStaff()),
                isStaff() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        sendDiagLine(
                "VetsMod selfStaffRank",
                selfStaffRank().isEmpty() ? "(none)" : selfStaffRank(),
                ChatFormatting.AQUA);

        // Trigger guild info update path without clearing GuildChecker: only
        // onGuildInfoUpdated() clears it (Wynntils' guild events and the
        // post-world-join recheck poll), and a forced recheck is neither.
        if (inGuild) {
            VetsLogger.info("forceGuildRecheck: Wynntils has guild info, re-evaluating");
            // Don't call onGuildInfoUpdated() here as it clears GuildChecker.
            // Just re-evaluate dependent state directly.
            if (isReturners()) {
                fetchAndDisplayStampMessage();
            }
            sendRegistrationIfReady();
        }

        // Force staff rank refresh regardless of cooldown
        boolean staffRefreshStarted = refreshStaffStatusIfNeeded(true);
        sendDiagLine(
                "Staff rank refresh",
                staffRefreshStarted ? "started" : "already in progress",
                staffRefreshStarted ? ChatFormatting.GREEN : ChatFormatting.YELLOW);

        // Run our own /gu stats check whatever moreReliableGuildCheck says
        // (skipped if one is already in flight)
        boolean guildCheckStarted = GuildChecker.refreshGuildStatus();
        sendDiagLine(
                "Guild check (/gu stats)",
                guildCheckStarted ? "started" : "already in progress",
                guildCheckStarted ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    private static void sendDiagLine(String label, String value, ChatFormatting color) {
        ChatUtils.sendLocalMessage(
                Component.literal("  " + label + ": ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(value).withStyle(color)));
    }

    /**
     * Reset per-connection state. Called on server disconnect so that the next
     * world join starts fresh. The cached player name and the Wynntils-ready flag are
     * kept.
     */
    public static void reset() {
        lastMotdFetchTime = 0;
        guildMotdDisplayedThisSession = false;
        stampDisplayedThisSession = false;
        enteredWorld = false;
        V1ApiManager.clearRegistration();
        StaffRankChecker.reset();
        GuildChecker.reset();
        UnlockManager.reset();
        SessionAuthWarning.reset();
        CommandDispatcher.resetStaffChatEligibilityCache();
    }

    /**
     * Called by {@link GuildChecker} when its {@code /gu stats} check completes.
     * Re-evaluates guild-dependent state and updates registration.
     */
    static void onGuildCheckCompleted() {
        GuildChecker.GuildCheckResult result = GuildChecker.getResult();
        VetsLogger.debug("onGuildCheckCompleted: result={}", result);

        sendRegistrationIfReady();

        if (result == GuildChecker.GuildCheckResult.RETURNERS && !stampDisplayedThisSession) {
            fetchAndDisplayStampMessage();
        }
    }

    /** Delay before running our /gu stats check after world join, giving
     *  Wynntils time to finish its own world-join commands. */
    private static final long GUILD_CHECK_WORLD_JOIN_DELAY_MS = 5_000L;

    /**
     * Starts a {@code /gu stats} guild check {@link #GUILD_CHECK_WORLD_JOIN_DELAY_MS} after
     * world join, to give Wynntils' own world-join commands time to go first. Sleeps on
     * its own thread, then hops to the client thread and queues the check only if the
     * player is still on a world.
     */
    private static void scheduleDelayedGuildCheck() {
        new Thread(
                        () -> {
                            try {
                                Thread.sleep(GUILD_CHECK_WORLD_JOIN_DELAY_MS);

                                if (!wynntilsReady || !enteredWorld) {
                                    VetsLogger.debug(
                                            "Delayed guild check aborted: wynntilsReady={}, enteredWorld={}",
                                            wynntilsReady,
                                            enteredWorld);
                                    return;
                                }

                                // Verify the world is still loaded before queuing the command
                                Minecraft minecraft = Minecraft.getInstance();
                                minecraft.execute(
                                        () -> {
                                            if (!Models.WorldState.onWorld()) {
                                                VetsLogger.debug(
                                                        "Delayed guild check aborted: not on world");
                                                return;
                                            }
                                            GuildChecker.refreshGuildStatus();
                                        });
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                VetsLogger.debug("Delayed guild check interrupted");
                            }
                        },
                        "vetsmod-delayed-guild-check")
                .start();
    }

    /**
     * Send a presence registration to the server if the player is eligible.
     *
     * <p>Determines the player's tier (guild / waitlist / honourary) and sends
     * a {@code register} frame via the inbound WebSocket.  The payload is
     * cached inside {@link V1ApiManager} so it is automatically re-sent on
     * reconnect.  Called from {@link #onEnteredWorld()},
     * {@link #onGuildInfoUpdated()}, {@link #forceGuildRecheck()},
     * {@link #onGuildCheckCompleted()} and
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
