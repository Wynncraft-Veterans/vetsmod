package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import com.wynntils.models.worlds.type.WorldState;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Central authority for the player's guild membership, staff rank, and
 * feature-gate state.
 *
 * <p>Reads guild affiliation directly from the Wynntils {@code Models.Guild}
 * API (backed by scoreboard/character-info parsing) rather than sending
 * {@code /guild stats}. World-join triggers are provided by
 * {@link org.wynnvets.listeners.WynntilsEventListener} via
 * {@code WorldStateEvent}. Once the player's guild is confirmed as the
 * Returners guild, mod features (bridge, MOTD, staff chat, etc.) are
 * enabled. Also tracks unlock/lock state, guildless status, and the
 * player's staff rank for command permission checks.</p>
 */
public class GuildStateManager {

  private static final String RETURNERS_GUILD_NAME = "Returners";

  // Wynntils readiness gate — set to true once CLIENT_STARTED fires and
  // WynntilsEventListener has successfully registered.  All Models.* access
  // must check this first to avoid triggering Models.<clinit> before the
  // Wynntils event bus is initialised.
  private static volatile boolean wynntilsReady = false;

  // Stored state
  private static boolean waitlistUnlocked = false;
  private static boolean honouraryUnlocked = false;
  private static boolean debugForceGuildlessUnlocked = false;
  private static String playerName = StringUtils.EMPTY;

  // Password hashes for unlock command (SHA-256)
  private static final String WAITLIST_PASSWORD_HASH = "8f74db5451e8e6e74189fa5e8a2d31efbb1853629fb80b30461bafc8a97fe07e";
  private static final String HONOURARY_PASSWORD_HASH = "4fe3af27e525245e9f3f3764e06b4eb5997ac09d553b4fdf59f9c9420caebca4";

  // Unlock persistence: codes persist for 1 week before requiring re-entry
  private static final long UNLOCK_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L;
  private static final long MAX_EXPIRY_WARNINGS = 3;

  // State tracking for staff detection via /gu rank
  private static boolean isStaff = false;
  private static long lastStaffCheckTime = 0;
  private static boolean waitingForStaffRankCheck = false;
  private static boolean isModInitiatedStaffRankCheck = false;
  private static long staffRankRequestTime = 0;
  private static final long STAFF_RANK_TIMEOUT = 5000; // 5 second timeout
  private static final long STAFF_CHECK_COOLDOWN = 24 * 60 * 60 * 1000L; // once per day

  // Grace period timestamp so that duplicate addMessage calls arriving after the
  // flag is cleared are still suppressed.
  private static long staffRankSuppressUntil = 0;
  private static final long SUPPRESSION_GRACE_MS = 500;

  // State tracking for MOTD to prevent duplicate fetches
  private static long lastMotdFetchTime = 0;
  private static final long MOTD_FETCH_COOLDOWN = 1000; // 1 second cooldown

  // Delayed guild re-check for guildless users after world switch.
  // Wynntils' compass scan is asynchronous — guild info may not be
  // available when onEnteredWorld() fires.
  private static final long GUILD_RECHECK_DELAY_MS = 3000;
  private static final int GUILD_RECHECK_MAX_ATTEMPTS = 3;
  private static final long GUILD_RECHECK_INTERVAL_MS = 2000;

  // Tracks whether the guild-specific MOTD was shown this session.
  // When guild info isn't available at world-join time, the standard MOTD is
  // shown instead; this flag lets onGuildInfoUpdated() fix that once the
  // guild model is populated.
  private static boolean guildMotdDisplayedThisSession = false;

  // Track whether we have entered a world at least once since reset, so that
  // commands are not executed before initial guild info is available.
  private static boolean enteredWorld = false;

  /**
   * Check if Wynntils is fully initialised and safe to access Models.
   *
   * @return true once Wynntils event bus registration has completed
   */
  public static boolean isWynntilsReady() {
    return wynntilsReady;
  }

  /**
   * Get whether the player's guild is "Returners", read live from
   * {@code Models.Guild}.
   *
   * @return true if guild is "Returners", false otherwise
   */
  public static boolean isReturners() {
    if (!wynntilsReady) return false;
    return RETURNERS_GUILD_NAME.equals(Models.Guild.getGuildName());
  }

  /**
   * Get whether the player is not in a guild, read live from
   * {@code Models.Guild}.
   *
   * @return true if player is not in a guild, false otherwise
   */
  public static boolean isGuildless() {
    if (debugForceGuildlessUnlocked) {
      return true;
    }
    if (!wynntilsReady) return true;
    return !Models.Guild.isInGuild();
  }

  /**
   * Get whether the mod is unlocked
   *
   * @return true if mod is unlocked, false otherwise
   */
  public static boolean isUnlocked() {
    if (debugForceGuildlessUnlocked) {
      return true;
    }
    boolean unlocked = isReturners() || waitlistUnlocked || honouraryUnlocked;
    return unlocked;
  }

  /**
   * Check if the player has unlocked as a waitlist (guildless) user.
   *
   * @return true if waitlist-unlocked, false otherwise
   */
  public static boolean isWaitlistUnlocked() {
    return debugForceGuildlessUnlocked || waitlistUnlocked;
  }

  /**
   * Check if the player has unlocked as an honourary member.
   *
   * @return true if honourary-unlocked, false otherwise
   */
  public static boolean isHonouraryUnlocked() {
    return honouraryUnlocked;
  }

  /**
   * Enable/disable debug override that forces the user to be treated as guildless and unlocked.
   *
   * @param enabled true to force guildless+unlocked behavior, false to use normal state
   */
  public static void setDebugForceGuildlessUnlocked(boolean enabled) {
    debugForceGuildlessUnlocked = enabled;
    VetsLogger.debug("Debug guildless+unlocked override: {}", enabled ? "enabled" : "disabled");
  }

  /**
   * Check if debug override is active.
   *
   * @return true when guildless+unlocked override is enabled
   */
  public static boolean isDebugForceGuildlessUnlocked() {
    return debugForceGuildlessUnlocked;
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
   * Get whether the user is staff.
   *
   * @return true when staff, false otherwise
   */
  public static boolean isStaff() {
    return isStaff;
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
   * Load persisted staff and unlock state from config.
   * Unlock timestamps older than {@link #UNLOCK_EXPIRY_MS} are treated as expired.
   */
  public static void loadPersistedState() {
    isStaff = VetsConfig.get(VetsConfig.VETS_IS_STAFF);
    long persistedCheckTime = VetsConfig.getLong(VetsConfig.VETS_LAST_STAFF_CHECK);
    long now = System.currentTimeMillis();
    lastStaffCheckTime = (persistedCheckTime >= 0 && persistedCheckTime <= now) ? persistedCheckTime : 0;

    long waitlistTime = VetsConfig.getLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME);
    waitlistUnlocked = waitlistTime > 0 && (now - waitlistTime) < UNLOCK_EXPIRY_MS;

    long honouraryTime = VetsConfig.getLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME);
    honouraryUnlocked = honouraryTime > 0 && (now - honouraryTime) < UNLOCK_EXPIRY_MS;
  }

  /**
   * Update and persist staff status.
   *
   * @param value latest staff status
   */
  private static void setStaffStatus(boolean value) {
    isStaff = value;
    VetsConfig.set(VetsConfig.VETS_IS_STAFF, value);
  }

  private static void markStaffCheckCompletedNow() {
    lastStaffCheckTime = System.currentTimeMillis();
    VetsConfig.setLong(VetsConfig.VETS_LAST_STAFF_CHECK, lastStaffCheckTime);
  }

  /**
   * Check if a mod-initiated staff rank check is currently running.
   *
   * @return true if currently waiting for /gu rank response
   */
  public static boolean isCheckingStaffStatus() {
    return waitingForStaffRankCheck;
  }

  /**
   * Check if currently processing a mod-initiated staff rank check command.
   *
   * @return true if rank check was initiated by the mod
   */
  public static boolean isProcessingModStaffRankCheck() {
    if (isModInitiatedStaffRankCheck) return true;
    return staffRankSuppressUntil > 0 && System.currentTimeMillis() < staffRankSuppressUntil;
  }

  /**
   * Refresh staff status when needed.
   *
   * @param forceRefresh true to bypass daily cooldown
   * @return true when a refresh was started, false otherwise
   */
  public static synchronized boolean refreshStaffStatusIfNeeded(boolean forceRefresh) {
    if (waitingForStaffRankCheck) {
      return false;
    }

    long now = System.currentTimeMillis();
    boolean hasRecentCheck = lastStaffCheckTime > 0 && (now - lastStaffCheckTime) < STAFF_CHECK_COOLDOWN;
    if (!forceRefresh && hasRecentCheck) {
      return false;
    }

    sendStaffRankCheckCommand();
    return true;
  }

  /**
   * Process incoming chat messages to detect staff rank-check responses.
   *
   * <p>Guild name and rank detection is now handled by the Wynntils
   * {@code Models.Guild} API. This method only needs to parse the
   * {@code /gu rank} response to determine staff status.</p>
   *
   * @param component The chat message Component (with formatting)
   * @param message   The plain text chat message
   */
  public static void processMessage(Component component, String message) {
    String safeMessage = message == null ? StringUtils.EMPTY : message;

    // Capture player name on first opportunity
    if (playerName.equals(StringUtils.EMPTY)) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player != null) {
        playerName = player.getName().getString();
      }
    }

    // Check if we're waiting for staff rank check and if we've timed out
    if (waitingForStaffRankCheck && System.currentTimeMillis() - staffRankRequestTime > STAFF_RANK_TIMEOUT) {
      waitingForStaffRankCheck = false;
      isModInitiatedStaffRankCheck = false;
      markStaffCheckCompletedNow();
    }

    // Process staff rank-check responses.
    if (waitingForStaffRankCheck) {
      if (isStaffRankUnauthorizedResponse(safeMessage)) {
        setStaffStatus(false);
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;
        markStaffCheckCompletedNow();
        VetsLogger.debug("Staff rank check result: not staff");
      } else if (isStaffRankAuthorizedResponse(safeMessage)) {
        setStaffStatus(true);
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;
        markStaffCheckCompletedNow();
        VetsLogger.debug("Staff rank check result: is staff");
      }
    }
  }

  /**
   * Send /gu rank to determine if the user is staff.
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
              if (!wynntilsReady) return;
              WorldState currentState = Models.WorldState.getCurrentState();
              if (currentState != WorldState.WORLD) {
                return;
              }

              LocalPlayer player = minecraft.player;
              if (player != null && player.connection != null) {
                try {
                  player.connection.sendCommand("gu rank");
                  commandSent[0] = true;
                } catch (Exception e) {
                  VetsLogger.warn("Failed to send /gu rank staff check: {}", e.getMessage());
                }
              }
            } finally {
              latch.countDown();
            }
          });

          // Block until the render thread has executed our lambda
          latch.await(2, TimeUnit.SECONDS);

          if (commandSent[0]) {
            int responseWait = 0;
            while (responseWait < 8 && waitingForStaffRankCheck) { // up to 2 seconds
              Thread.sleep(250);
              responseWait++;
            }

            if (!waitingForStaffRankCheck) {
              break;
            }
          }

          attempts++;
        } catch (InterruptedException e) {
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
    }).start();
  }

  /**
   * Detects the unauthorized /guild rank response indicating non-staff.
   */
  private static boolean isStaffRankUnauthorizedResponse(String message) {
    if (message == null) {
      return false;
    }

    String normalized = message.toLowerCase();
    return normalized.contains("you must be a") &&
        normalized.contains("captain") &&
        normalized.contains("to use this command");
  }

  /**
   * Detects the authorized /guild rank response indicating staff.
   */
  private static boolean isStaffRankAuthorizedResponse(String message) {
    if (message == null) {
      return false;
    }

    String normalized = message.toLowerCase();
    return normalized.contains("invalid arguments, try:") &&
        normalized.contains("rank [name] [rank]");
  }

  /**
   * Checks whether a previously-persisted unlock has expired and, if so,
   * warns the player.  Warns up to {@link #MAX_EXPIRY_WARNINGS} times across
   * sessions, then silently clears the expired timestamps to stop nagging.
   */
  private static void checkAndWarnUnlockExpiry() {
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
      // Stop nagging — silently clear stale timestamps
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
    checkAndWarnUnlockExpiry();

    long currentTime = System.currentTimeMillis();
    if (currentTime - lastMotdFetchTime > MOTD_FETCH_COOLDOWN) {
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

    // When guild info is not yet available (empty name), Wynntils may still
    // be scanning the compass menu asynchronously.  Schedule a delayed
    // re-check so we don't stay stuck as "guildless" for the entire session.
    if (!debugForceGuildlessUnlocked && wynntilsReady && !Models.Guild.isInGuild()) {
      scheduleGuildRecheck();
    }
  }

  /**
   * Called by {@link org.wynnvets.listeners.WynntilsEventListener} when
   * a {@code GuildEvent.Joined} or {@code GuildEvent.Left} event fires.
   *
   * <p>Re-evaluates guild-dependent state so that feature gates update
   * immediately when the player joins or leaves a guild mid-session.</p>
   */
  public static void onGuildInfoUpdated() {
    VetsLogger.debug("onGuildInfoUpdated: guild={}, guildless={}, returners={}",
        Models.Guild.getGuildName(), isGuildless(), isReturners());

    if (isReturners()) {
      fetchAndDisplayStampMessage();

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
        MotdFetcher.fetchGuildMotd().thenAccept(guildMotdComponent -> {
          String text = guildMotdComponent.getString();
          if (text != null && !text.isEmpty()) {
            guildMotdDisplayedThisSession = true;
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
   * Attempt to unlock with a password. Checks against both the waitlist
   * and honourary password hashes.
   *
   * @param password The password to check
   * @return the type of unlock granted, or {@link UnlockType#NONE} if the
   *         password did not match
   */
  public static UnlockType tryUnlock(String password) {
    String hash = sha256(password);
    if (hash == null) {
      return UnlockType.NONE;
    }
    long now = System.currentTimeMillis();
    if (hash.equals(WAITLIST_PASSWORD_HASH)) {
      waitlistUnlocked = true;
      VetsConfig.setLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME, now);
      VetsConfig.setLong(VetsConfig.VETS_UNLOCK_EXPIRY_WARNINGS, 0L);
      VetsLogger.debug("Mod unlocked via waitlist password");
      sendRegistrationIfReady();
      return UnlockType.WAITLIST;
    }
    if (hash.equals(HONOURARY_PASSWORD_HASH)) {
      honouraryUnlocked = true;
      VetsConfig.setLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME, now);
      VetsConfig.setLong(VetsConfig.VETS_UNLOCK_EXPIRY_WARNINGS, 0L);
      VetsLogger.debug("Mod unlocked via honourary password");
      sendRegistrationIfReady();
      return UnlockType.HONOURARY;
    }
    return UnlockType.NONE;
  }

  /**
   * Compute SHA-256 hash of a string
   *
   * @param input The input string
   * @return The hex-encoded SHA-256 hash, or null on error
   */
  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      VetsLogger.error("SHA-256 algorithm not available", e);
      return null;
    }
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
        VetsLogger.debug("Guild recheck interrupted");
      }
    }, "vetsmod-guild-recheck").start();
  }

  /**
   * Force an immediate re-read of guild info from the Wynntils
   * {@code Models.Guild} API.  Reports current state to chat and triggers
   * {@link #onGuildInfoUpdated()} if guild info is present.  Also forces a
   * staff rank refresh regardless of cooldown.
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

    // Trigger guild info update path
    if (inGuild) {
      VetsLogger.info("forceGuildRecheck: triggering onGuildInfoUpdated()");
      onGuildInfoUpdated();
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
  }

  /**
   * Reset all transient state. Called on server disconnect so that the next
   * world join starts fresh.
   */
  public static void reset() {
    debugForceGuildlessUnlocked = false;
    lastMotdFetchTime = 0;
    guildMotdDisplayedThisSession = false;
    staffRankSuppressUntil = 0;
    enteredWorld = false;
    V1ApiManager.clearRegistration();
    isStaff = VetsConfig.get(VetsConfig.VETS_IS_STAFF);
    long persistedCheckTime = VetsConfig.getLong(VetsConfig.VETS_LAST_STAFF_CHECK);
    long now = System.currentTimeMillis();
    lastStaffCheckTime = (persistedCheckTime >= 0 && persistedCheckTime <= now) ? persistedCheckTime : 0;
    waitingForStaffRankCheck = false;
    isModInitiatedStaffRankCheck = false;
    staffRankRequestTime = 0;
    StaffOutboundMessenger.resetStaffChatEligibilityCache();

    // Reload persisted unlock state (respects expiry)
    long waitlistTime = VetsConfig.getLong(VetsConfig.VETS_WAITLIST_UNLOCK_TIME);
    waitlistUnlocked = waitlistTime > 0 && (now - waitlistTime) < UNLOCK_EXPIRY_MS;

    long honouraryTime = VetsConfig.getLong(VetsConfig.VETS_HONOURARY_UNLOCK_TIME);
    honouraryUnlocked = honouraryTime > 0 && (now - honouraryTime) < UNLOCK_EXPIRY_MS;
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
