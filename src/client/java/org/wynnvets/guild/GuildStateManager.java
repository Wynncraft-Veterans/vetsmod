package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.apache.commons.lang3.StringUtils;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.Prepend;
import org.wynnvets.chat.StaffOutboundMessenger;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.fetcher.ondemand.MotdFetcher;
import org.wynnvets.fetcher.ondemand.StampFetcher;

/**
 * Central authority for the player's guild membership, staff rank, and
 * feature-gate state.
 *
 * <p>Reads guild affiliation directly from the Wynntils {@code Models.Guild}
 * API (backed by scoreboard/character-info parsing). World-join triggers are
 * provided by {@link org.wynnvets.listeners.WynntilsEventListener} via
 * {@code WorldStateEvent}. Once the player's guild is confirmed as the
 * Returners guild, mod features (bridge, MOTD, staff chat, etc.) are
 * enabled.</p>
 *
 * <p>Delegates staff-rank detection to {@link StaffRankChecker} and
 * password-based unlock management to {@link UnlockManager}. External
 * callers should use this class's facade methods rather than accessing
 * those helpers directly.</p>
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

  // Delayed guild re-check for guildless users after world switch.
  private static final long GUILD_RECHECK_DELAY_MS = 3_000L;
  private static final int GUILD_RECHECK_MAX_ATTEMPTS = 3;
  private static final long GUILD_RECHECK_INTERVAL_MS = 2_000L;

  // Tracks whether the guild MOTD was shown this session.
  private static volatile boolean guildMotdDisplayedThisSession = false;

  // Tracks whether the annihilation stamp was shown this session.
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
   * Get whether the player's guild is "Returners".
   *
   * <p>If the mod's own guild check ({@link GuildChecker}) has a valid
   * (non-expired) result, that takes precedence over Wynntils'
   * {@code Models.Guild} data, which can remain {@code null} for
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
   * Get whether the player is not in a guild.
   *
   * <p>Checks the mod's own guild check result first, falling back to
   * Wynntils' {@code Models.Guild}.</p>
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
    return isReturners() || UnlockManager.isWaitlistUnlocked() || UnlockManager.isHonouraryUnlocked();
  }

  /**
   * Check if the player has unlocked as a waitlist (guildless) user.
   *
   * @return true if waitlist-unlocked, false otherwise
   */
  public static boolean isWaitlistUnlocked() {
    return UnlockManager.isWaitlistUnlocked();
  }

  /**
   * Check if the player has unlocked as an honourary member.
   *
   * @return true if honourary-unlocked, false otherwise
   */
  public static boolean isHonouraryUnlocked() {
    return UnlockManager.isHonouraryUnlocked();
  }

  /**
   * Enable/disable debug override that forces the user to be treated as guildless and unlocked.
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
   * Check if mod features should be enabled.
   * Features are only enabled when guild is Returners.
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
   * Check if the player has entered a world at least once since the last
   * reset, meaning guild info from Wynntils should be available.
   *
   * @return true once the first world-join has been processed
   */
  public static boolean canExecuteCommands() {
    return enteredWorld;
  }

  /**
   * Get the players name.
   *
   * @return The players name.
   */
  public static String playerName() {
    return playerName;
  }

  /**
   * Get whether the user is staff (Captain+ rank in Returners).
   *
   * @return true when staff, false otherwise
   */
  public static boolean isStaff() {
    return StaffRankChecker.isStaff();
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
    switch (rank) {
      case CAPTAIN:
        return "captain";
      case STRATEGIST:
        return "strategist";
      case CHIEF:
        return "chief";
      case OWNER:
        return "owner";
      default:
        return StringUtils.EMPTY;
    }
  }

  /**
   * Load persisted staff, guild check, and unlock state from config.
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
   * @return true if rank check was initiated by the mod
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
   * Process incoming chat messages to detect staff rank-check responses.
   *
   * @param component The chat message Component (with formatting)
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
   * Check if currently processing a mod-initiated guild check.
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
   * <p>Replaces the old "Welcome to Wynncraft!" chat-message detection.
   * Reads guild info straight from {@code Models.Guild}, fetches MOTD and
   * stamp, and triggers a staff-rank refresh when needed.</p>
   */
  public static void onEnteredWorld() {
    enteredWorld = true;
    StaffOutboundMessenger.resetStaffChatEligibilityCache();

    // Capture player name
    Minecraft minecraft = Minecraft.getInstance();
    LocalPlayer player = minecraft.player;
    if (player != null && playerName.equals(StringUtils.EMPTY)) {
      playerName = player.getName().getString();
    }

    // Warn if a previously-persisted unlock has expired since last session
    UnlockManager.checkAndWarnUnlockExpiry();

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

    VetsLogger.debug("onEnteredWorld: guild={}, guildless={}, returners={}",
        Models.Guild.getGuildName(), isGuildless(), isReturners());

    // When moreReliableGuildCheck is enabled, schedule our own /gu stats
    // check after a delay so Wynntils has time to finish its own
    // world-join commands and the command queue is clear.
    if (VetsConfig.get(VetsConfig.MORE_RELIABLE_GUILD_CHECK)) {
      scheduleDelayedGuildCheck();
    }

    // When guild info is not yet available (empty name), Wynntils may still
    // be scanning the compass menu asynchronously.  Schedule a delayed
    // re-check so we don't stay stuck as "guildless" for the entire session.
    if (!UnlockManager.isDebugForceGuildlessUnlocked() && wynntilsReady && !Models.Guild.isInGuild()) {
      scheduleGuildRecheck();
    }
  }

  /**
   * Called by {@link org.wynnvets.listeners.WynntilsEventListener} when
   * a {@code GuildEvent.Joined} or {@code GuildEvent.Left} event fires.
   *
   * <p>Clears the mod's own guild check result (since the Wynntils event
   * is authoritative) and re-evaluates guild-dependent state.</p>
   */
  public static void onGuildInfoUpdated() {
    // Wynntils guild events are authoritative — clear our override
    GuildChecker.clearResult();
    VetsLogger.debug("onGuildInfoUpdated: guild={}, guildless={}, returners={}",
        Models.Guild.getGuildName(), isGuildless(), isReturners());

    if (isReturners()) {
      if (!stampDisplayedThisSession) {
        fetchAndDisplayStampMessage();
      }

      // If the MOTD was already fetched but guild info wasn't available yet
      // (race between WorldStateEvent and GuildEvent.Joined), the standard
      // MOTD was shown instead of the guild MOTD.  Re-fetch now that we know
      // the player is in Returners.
      if (enteredWorld && !guildMotdDisplayedThisSession) {
        VetsLogger.debug("Guild info now available — re-fetching guild MOTD");
        fetchAndDisplayMotd();
      }

      // Re-register now that guild membership is confirmed.
      sendRegistrationIfReady();
    }
  }

  /**
   * Fetch and display the MOTD message.
   */
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
      // Use guild MOTD for eligible users (Returners, waitlist-unlocked, honourary-unlocked)
      boolean useGuildMotd = isReturners()
          || (isGuildless() && isWaitlistUnlocked())
          || isHonouraryUnlocked();

      if (useGuildMotd) {
        guildMotdDisplayedThisSession = true;
        MotdFetcher.fetchGuildMotd().thenAccept(guildMotdComponent -> {
          String text = guildMotdComponent.getString();
          if (text != null && !text.isEmpty()) {
            ChatUtils.sendLocalMessage(guildMotdComponent, Prepend.DEFAULT);
          } else {
            // Fall back to standard MOTD if guild MOTD is empty
            MotdFetcher.fetchMotd().thenAccept(motdComponent -> {
              ChatUtils.sendLocalMessage(motdComponent, Prepend.DEFAULT);
            });
          }
        });
      } else {
        MotdFetcher.fetchMotd().thenAccept(motdComponent -> {
          ChatUtils.sendLocalMessage(motdComponent, Prepend.DEFAULT);
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
      StampFetcher.fetchStampAndCreateMessage().thenAccept(stampMessage -> {
        if (stampMessage != null) {
          VetsLogger.debug("Displaying annihilation countdown");
          ChatUtils.sendLocalMessage(stampMessage, Prepend.DEFAULT);
        }
      });
    }
  }

  /**
   * The type of unlock granted by a password.
   */
  public enum UnlockType {
    NONE,
    WAITLIST,
    HONOURARY
  }

  /**
   * Attempt to unlock with a password. Delegates to {@link UnlockManager}
   * and sends a registration if the unlock succeeds.
   *
   * @param password The password to check
   * @return the type of unlock granted, or {@link UnlockType#NONE} if the
   *         password did not match
   */
  public static UnlockType tryUnlock(String password) {
    UnlockType result = UnlockManager.tryUnlock(password);
    if (result != UnlockType.NONE) {
      sendRegistrationIfReady();
    }
    return result;
  }

  /**
   * Schedules a delayed guild info re-check.  Wynntils' compass scan runs
   * asynchronously after world join, so guild info may not be populated yet.
   * This method polls {@code Models.Guild} after an initial delay and retries
   * a few times if the guild name is still empty.
   */
  private static void scheduleGuildRecheck() {
    new Thread(() -> {
      try {
        Thread.sleep(GUILD_RECHECK_DELAY_MS);

        for (int attempt = 1; attempt <= GUILD_RECHECK_MAX_ATTEMPTS; attempt++) {
          if (!wynntilsReady || !enteredWorld) {
            VetsLogger.debug("Guild recheck aborted: wynntilsReady={}, enteredWorld={}",
                wynntilsReady, enteredWorld);
            return;
          }

          boolean inGuild = Models.Guild.isInGuild();
          String name = Models.Guild.getGuildName();
          VetsLogger.debug("Guild recheck attempt {}/{}: inGuild={}, name={}",
              attempt, GUILD_RECHECK_MAX_ATTEMPTS, inGuild, name);

          if (inGuild) {
            VetsLogger.info("Guild info now available after recheck: {}", name);
            onGuildInfoUpdated();
            return;
          }

          if (attempt < GUILD_RECHECK_MAX_ATTEMPTS) {
            Thread.sleep(GUILD_RECHECK_INTERVAL_MS);
          }
        }

        VetsLogger.debug("Guild recheck exhausted — player appears genuinely guildless");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        VetsLogger.debug("Guild recheck interrupted");
      }
    }, "vetsmod-guild-recheck").start();
  }

  /**
   * Force an immediate re-read of guild info from the Wynntils
   * {@code Models.Guild} API.  Reports current state to chat, triggers
   * {@link #onGuildInfoUpdated()} if guild info is present, forces a
   * staff rank refresh, and always runs our own {@code /gu stats}
   * guild check.
   *
   * <p>Intended for use from {@code /wv debug trigger forceChecks}.</p>
   */
  public static void forceGuildRecheck() {
    if (!wynntilsReady) {
      ChatUtils.sendLocalMessage(
          Component.literal("Wynntils is not ready yet — cannot check guild state.")
              .withStyle(ChatFormatting.RED)
      );
      return;
    }

    String guildName = Models.Guild.getGuildName();
    boolean inGuild = Models.Guild.isInGuild();
    GuildRank rank = Models.Guild.getGuildRank();

    MutableComponent header = Component.literal("Force Guild Check Results:")
        .withStyle(ChatFormatting.GOLD);
    ChatUtils.sendLocalMessage(header);

    ChatUtils.sendLocalMessage(
        Component.literal("  Wynntils Guild Name: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(guildName.isEmpty() ? "(empty)" : guildName)
                .withStyle(guildName.isEmpty()
                    ? ChatFormatting.RED
                    : ChatFormatting.GREEN))
    );

    ChatUtils.sendLocalMessage(
        Component.literal("  Wynntils isInGuild: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(inGuild))
                .withStyle(inGuild
                    ? ChatFormatting.GREEN
                    : ChatFormatting.RED))
    );

    ChatUtils.sendLocalMessage(
        Component.literal("  Wynntils Guild Rank: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(rank == null ? "(null)" : rank.name())
                .withStyle(rank == null
                    ? ChatFormatting.RED
                    : ChatFormatting.GREEN))
    );

    // GuildChecker state
    GuildChecker.GuildCheckResult gcResult = GuildChecker.getResult();
    boolean gcValid = GuildChecker.hasValidResult();
    long gcAge = GuildChecker.getLastCheckTime() > 0
        ? (System.currentTimeMillis() - GuildChecker.getLastCheckTime()) / 1000
        : -1;

    ChatUtils.sendLocalMessage(
        Component.literal("  GuildChecker result: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(gcResult.name() + (gcValid ? "" : " (expired)"))
                .withStyle(gcValid ? ChatFormatting.GREEN : ChatFormatting.RED))
    );

    if (gcAge >= 0) {
      String ageStr = gcAge < 3600 ? gcAge + "s"
          : gcAge < 86400 ? (gcAge / 3600) + "h"
          : (gcAge / 86400) + "d";
      ChatUtils.sendLocalMessage(
          Component.literal("  GuildChecker age: ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(ageStr)
                  .withStyle(ChatFormatting.AQUA))
      );
    }

    ChatUtils.sendLocalMessage(
        Component.literal("  VetsMod isReturners: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(isReturners()))
                .withStyle(isReturners()
                    ? ChatFormatting.GREEN
                    : ChatFormatting.RED))
    );

    ChatUtils.sendLocalMessage(
        Component.literal("  VetsMod isGuildless: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(isGuildless()))
                .withStyle(isGuildless()
                    ? ChatFormatting.YELLOW
                    : ChatFormatting.GREEN))
    );

    ChatUtils.sendLocalMessage(
        Component.literal("  VetsMod isStaff: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(isStaff()))
                .withStyle(isStaff()
                    ? ChatFormatting.GREEN
                    : ChatFormatting.GRAY))
    );

    ChatUtils.sendLocalMessage(
        Component.literal("  VetsMod selfStaffRank: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(selfStaffRank().isEmpty() ? "(none)" : selfStaffRank())
                .withStyle(ChatFormatting.AQUA))
    );

    // Trigger guild info update path (without clearing GuildChecker —
    // that only happens on GuildEvent from Wynntils, not on forced recheck)
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
    ChatUtils.sendLocalMessage(
        Component.literal("  Staff rank refresh: ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(staffRefreshStarted ? "started" : "already in progress")
                .withStyle(staffRefreshStarted
                    ? ChatFormatting.GREEN
                    : ChatFormatting.YELLOW))
    );

    // Always run our own /gu stats check from forceChecks
    boolean guildCheckStarted = GuildChecker.refreshGuildStatus();
    ChatUtils.sendLocalMessage(
        Component.literal("  Guild check (/gu stats): ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(guildCheckStarted ? "started" : "already in progress")
                .withStyle(guildCheckStarted
                    ? ChatFormatting.GREEN
                    : ChatFormatting.YELLOW))
    );
  }

  /**
   * Reset all transient state. Called on server disconnect so that the next
   * world join starts fresh.
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
    StaffOutboundMessenger.resetStaffChatEligibilityCache();
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
   * Schedules a {@code /gu stats} guild check to run after a delay,
   * ensuring Wynntils has finished its own world-join processing and
   * the command queue is clear.
   */
  private static void scheduleDelayedGuildCheck() {
    new Thread(() -> {
      try {
        Thread.sleep(GUILD_CHECK_WORLD_JOIN_DELAY_MS);

        if (!wynntilsReady || !enteredWorld) {
          VetsLogger.debug("Delayed guild check aborted: wynntilsReady={}, enteredWorld={}",
              wynntilsReady, enteredWorld);
          return;
        }

        // Verify the world is still loaded before queuing the command
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
          if (!Models.WorldState.onWorld()) {
            VetsLogger.debug("Delayed guild check aborted: not on world");
            return;
          }
          GuildChecker.refreshGuildStatus();
        });
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        VetsLogger.debug("Delayed guild check interrupted");
      }
    }, "vetsmod-delayed-guild-check").start();
  }

  /**
   * Send a presence registration to the server if the player is eligible.
   *
   * <p>Determines the player's tier (guild / waitlist / honourary) and sends
   * a {@code register} frame via the inbound WebSocket.  The payload is
   * cached inside {@link V1ApiManager} so it is automatically re-sent on
   * reconnect.  Called from {@link #onEnteredWorld()},
   * {@link #onGuildInfoUpdated()}, and {@link #tryUnlock}.</p>
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
