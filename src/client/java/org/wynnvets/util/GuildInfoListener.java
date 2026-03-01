package org.wynnvets.util;

import com.wynntils.core.components.Models;
import com.wynntils.models.worlds.type.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.util.chat.ChatUtils;
import org.wynnvets.util.chat.Prepend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class GuildInfoListener {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");
  private static final Pattern LEGACY_FORMAT_CODE_PATTERN = Pattern.compile("(?i)(?:§|&)[0-9A-FK-OR]");
  private static final Pattern GUILD_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_ ]+$");

  // Stored guild information
  private static boolean isReturners = false;
  private static boolean isGuildless = false;
  private static boolean passwordUnlocked = false;
  private static boolean debugForceGuildlessUnlocked = false;
  private static String playerName = StringUtils.EMPTY;

  // Password hash for unlock command
  private static final String UNLOCK_PASSWORD_HASH = "d4c4f49d09ae0fc5e88f23f47a135d3e509a0799cebc711943370e80e58e145b";

  // State tracking for guild stats detection
  private static boolean waitingForGuildStats = false;
  private static long guildStatsRequestTime = 0;
  private static final long GUILD_STATS_TIMEOUT = 5000; // 5 second timeout

  // State tracking for staff detection via /gu rank
  private static boolean isStaff = false;
  private static long lastStaffCheckTime = 0;
  private static boolean waitingForStaffRankCheck = false;
  private static boolean isModInitiatedStaffRankCheck = false;
  private static long staffRankRequestTime = 0;
  private static final long STAFF_RANK_TIMEOUT = 5000; // 5 second timeout
  private static final long STAFF_CHECK_COOLDOWN = 24 * 60 * 60 * 1000L; // once per day
  private static String selfStaffRank = StringUtils.EMPTY;

  // Track if the current guild stats request was initiated by the mod (not the user)
  private static boolean isModInitiatedGuildStats = false;

  // Grace period timestamps so that duplicate addMessage calls arriving after the
  // flags are cleared are still suppressed.
  private static long guildStatsSuppressUntil = 0;
  private static long staffRankSuppressUntil = 0;
  private static final long SUPPRESSION_GRACE_MS = 500;

  // State tracking for MOTD to prevent duplicate fetches
  private static long lastMotdFetchTime = 0;
  private static final long MOTD_FETCH_COOLDOWN = 1000; // 1 second cooldown

  // Track whether initial guild stats check has completed after joining world
  private static boolean guildStatsCompleted = false;

  /**
   * Get whether the guild is "Returners"
   *
   * @return true if guild is "Returners", false otherwise
   */
  public static boolean isReturners() {
    return isReturners;
  }

  /**
   * Get whether the player is not in a guild
   *
   * @return true if player is not in a guild, false otherwise
   */
  public static boolean isGuildless() {
    if (debugForceGuildlessUnlocked) {
      return true;
    }
    return isGuildless;
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
    boolean unlocked = isReturners || passwordUnlocked;
    return unlocked;
  }

  /**
   * Enable/disable debug override that forces the user to be treated as guildless and unlocked.
   *
   * @param enabled true to force guildless+unlocked behavior, false to use normal state
   */
  public static void setDebugForceGuildlessUnlocked(boolean enabled) {
    debugForceGuildlessUnlocked = enabled;
    LOGGER.info("Debug guildless+unlocked override: {}", enabled ? "enabled" : "disabled");
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
   * Check if mod features should be enabled
   * Features are only enabled when guild is Returners
   *
   * @return true if features should be enabled, false otherwise
   */
  public static boolean areFeaturesEnabled() {
    return isReturners;
  }

  /**
   * Check if initial guild stats check has completed after joining the world
   *
   * @return true if guild stats has completed, false otherwise
   */
  public static boolean canExecuteCommands() {
    return guildStatsCompleted;
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
   * Check if currently processing a mod-initiated guild stats command
   *
   * @return true if guild stats was initiated by the mod, false if by user
   */
  public static boolean isProcessingModGuildStats() {
    if (isModInitiatedGuildStats) return true;
    // Keep suppressing for a short grace period after the flag is cleared so that
    // a second addMessage call for the same server message is still caught.
    return guildStatsSuppressUntil > 0 && System.currentTimeMillis() < guildStatsSuppressUntil;
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
   * Gets the known self staff rank for pill display.
   *
   * @return one of captain/strategist/chief/owner when known, otherwise empty
   */
  public static String selfStaffRank() {
    return selfStaffRank;
  }

  /**
   * Load persisted staff state from config.
   */
  public static void loadPersistedState() {
    isStaff = VetsConfig.get(VetsConfig.VETS_IS_STAFF);
    long persistedCheckTime = VetsConfig.getLong(VetsConfig.VETS_LAST_STAFF_CHECK);
    long now = System.currentTimeMillis();
    lastStaffCheckTime = (persistedCheckTime >= 0 && persistedCheckTime <= now) ? persistedCheckTime : 0;
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
   * Process incoming chat messages to detect guild information
   *
   * @param component The chat message Component (with formatting)
   * @param message   The plain text chat message
   */
  public static void processMessage(Component component, String message) {
    String safeMessage = message == null ? StringUtils.EMPTY : message;
    String strippedMessage = stripLegacyFormatting(safeMessage);

    updateSelfStaffRankFromGuildStatsMessage(message);

    // Check if we're waiting for guild stats and if we've timed out
    if (waitingForGuildStats && System.currentTimeMillis() - guildStatsRequestTime > GUILD_STATS_TIMEOUT) {
      waitingForGuildStats = false;
      isModInitiatedGuildStats = false; // Clear flag on timeout
    }

    // Check if we're waiting for staff rank check and if we've timed out
    if (waitingForStaffRankCheck && System.currentTimeMillis() - staffRankRequestTime > STAFF_RANK_TIMEOUT) {
      waitingForStaffRankCheck = false;
      isModInitiatedStaffRankCheck = false;
      markStaffCheckCompletedNow();
    }

    // Check for Wynncraft welcome message with gold+bold formatting
    String literalContent = component.toString();

    // Accept both old and new chat formats for the welcome trigger.
    if (isWelcomeMessage(literalContent, safeMessage, strippedMessage)) {
      // Welcome message detected, send /guild stats command and fetch MOTD
      // Only process if we haven't recently processed a welcome message
      long currentTime = System.currentTimeMillis();
      if (currentTime - lastMotdFetchTime > MOTD_FETCH_COOLDOWN) {
        lastMotdFetchTime = currentTime;
        guildStatsCompleted = false; // Reset flag for new world join
        sendGuildStatsCommand();
        fetchAndDisplayMotd();
      }
      return;
    }

    // If we're waiting for guild stats, check for the guild name or error message
    if (waitingForGuildStats) {
      // Check for the "not in a guild" error message
      if (message.contains("You must be in a guild to use this")) {
        isGuildless = true;
        isReturners = false;
        selfStaffRank = StringUtils.EMPTY;
        waitingForGuildStats = false;
        isModInitiatedGuildStats = false;
        guildStatsCompleted = true; // Mark as completed since we got a response
        return; // Exit early, no need to check further
      }

      // Check if this message contains the formatted "Returners" guild name
      // Looking for "Returners" with gold color and bold formatting
      // The guild name appears as: literal{Returners}[style={color=gold,bold}]
      // Use regex-like check to ensure we match "bold" not "!bold"
      boolean hasComponentGoldColor = literalContent.contains("color=gold") || literalContent.contains("color=#FFAA00");
      boolean hasComponentBoldStyle = (literalContent.contains(",bold") || literalContent.contains("{bold") ||
          literalContent.contains("=bold") || literalContent.contains(" bold")) &&
          !literalContent.contains("!bold");
      boolean hasLegacyGoldBold = safeMessage.contains("§6§l") || safeMessage.contains("&6&l");
      boolean hasGoldBoldGuildStyle = (hasComponentGoldColor && hasComponentBoldStyle) || hasLegacyGoldBold;
      boolean containsReturners = literalContent.contains("Returners") || strippedMessage.contains("Returners");
      boolean isGuildNameLine = isLikelyGuildNameLine(strippedMessage);

      if (containsReturners && hasGoldBoldGuildStyle) {
        // Found "Returners" with gold+bold formatting - this is the Returners guild
        isReturners = true;
        passwordUnlocked = false;
        isGuildless = false;
        // Don't set waitingForGuildStats to false yet - wait for the full stats to complete
        // Don't clear isModInitiatedGuildStats yet - guild stats output continues
      } else if (hasGoldBoldGuildStyle && isGuildNameLine && !containsReturners) {
        // Found a different guild name (has gold+bold formatting but isn't Returners)
        // Temporarily exclude the MOTD message which also has gold+bold formatting
        isReturners = false;
        passwordUnlocked = false;
        isGuildless = false;
        // Don't set waitingForGuildStats to false yet - wait for the full stats to complete
        // Don't clear isModInitiatedGuildStats yet - guild stats output continues
      }
    }

    // Check if this is the last message in guild stats output to clear the flags
    if (isModInitiatedGuildStats && message.contains("Total Members:")) {
      // This is the last line of guild stats output
      isModInitiatedGuildStats = false;
      guildStatsSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;
      waitingForGuildStats = false; // Signal that guild stats is complete
      guildStatsCompleted = true;

      // After guild stats completes, check for annihilation stamp if we're in Returners
      if (isReturners) {
        fetchAndDisplayStampMessage();
      }
    }

    // Process staff rank-check responses.
    if (waitingForStaffRankCheck) {
      if (isStaffRankUnauthorizedResponse(message)) {
        setStaffStatus(false);
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;
        markStaffCheckCompletedNow();
        LOGGER.info("Staff check complete: user is not staff");
      } else if (isStaffRankAuthorizedResponse(message)) {
        setStaffStatus(true);
        waitingForStaffRankCheck = false;
        isModInitiatedStaffRankCheck = false;
        staffRankSuppressUntil = System.currentTimeMillis() + SUPPRESSION_GRACE_MS;
        markStaffCheckCompletedNow();
        LOGGER.info("Staff check complete: user is staff");
      }
    }
  }

  private static String stripLegacyFormatting(String text) {
    if (text == null) {
      return StringUtils.EMPTY;
    }
    return LEGACY_FORMAT_CODE_PATTERN.matcher(text).replaceAll(StringUtils.EMPTY);
  }

  private static boolean isLikelyGuildNameLine(String strippedMessage) {
    if (strippedMessage == null) {
      return false;
    }
    String trimmed = strippedMessage.trim();
    if (trimmed.isEmpty() || trimmed.length() >= 50) {
      return false;
    }
    if (trimmed.contains(":")) {
      return false;
    }
    if (trimmed.contains("Welcome") || trimmed.contains("VETSMOD")) {
      return false;
    }
    return GUILD_NAME_PATTERN.matcher(trimmed).matches();
  }

  private static boolean isWelcomeMessage(String literalContent, String rawMessage, String strippedMessage) {
    boolean containsWelcomeText = strippedMessage.contains("Welcome to Wynncraft!");
    boolean hasComponentWelcomeStyle = literalContent.contains("Welcome to Wynncraft!") &&
        literalContent.contains("color=#FFAA00") &&
        literalContent.contains("bold");
    boolean hasLegacyWelcomeStyle = rawMessage.contains("§6§lWelcome to Wynncraft!") ||
        rawMessage.contains("&6&lWelcome to Wynncraft!");

    return containsWelcomeText && (hasComponentWelcomeStyle || hasLegacyWelcomeStyle);
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
                  LOGGER.warn("Failed to send /gu rank staff check command: {}", e.getMessage());
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
          LOGGER.warn("Staff rank check command interrupted");
          waitingForStaffRankCheck = false;
          isModInitiatedStaffRankCheck = false;
          break;
        }
      }

      if (attempts >= maxAttempts) {
        LOGGER.warn("Failed to send /gu rank staff check after {} attempts", maxAttempts);
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

  private static void updateSelfStaffRankFromGuildStatsMessage(String message) {
    if (message == null) {
      return;
    }

    String trimmed = message.trim();
    if (!trimmed.regionMatches(true, 0, "Guild Rank:", 0, "Guild Rank:".length())) {
      return;
    }

    String rank = trimmed.substring("Guild Rank:".length()).trim().toLowerCase();
    switch (rank) {
      case "captain":
      case "strategist":
      case "chief":
      case "owner":
        selfStaffRank = rank;
        break;
      default:
        selfStaffRank = StringUtils.EMPTY;
        break;
    }
  }

  /**
   * Send the /guild stats command to the server
   */
  private static void sendGuildStatsCommand() {
    sendGuildStatsCommand(false);
  }

  /**
   * TEMP: Force /guild stats check regardless of world state due to Wynntils alpha prototype
   * not updating world state reliably.
   */
  public static synchronized void forceGuildStatsCheckTemp() {
    if (waitingForGuildStats) {
      return;
    }
    sendGuildStatsCommand(true);
  }

  private static void sendGuildStatsCommand(boolean ignoreWorldState) {
    Minecraft minecraft = Minecraft.getInstance();

    // Mark that we're waiting for guild stats response
    waitingForGuildStats = true;
    guildStatsRequestTime = System.currentTimeMillis();
    isModInitiatedGuildStats = true; // Mark as mod-initiated

    // Schedule command to wait for proper world state
    new Thread(() -> {
      int attempts = 0;
      int maxAttempts = 20; // 20 attempts * 250ms = 5 second timeout
      long delay = 250; // Check every 250ms

      while (attempts < maxAttempts) {
        try {
          Thread.sleep(delay);

          CountDownLatch latch = new CountDownLatch(1);
          boolean[] commandSent = {false};
          minecraft.execute(() -> {
            try {
              // Check if we're in WORLD state using Wynntils API
              WorldState currentState = Models.WorldState.getCurrentState();

              if (!ignoreWorldState && currentState != WorldState.WORLD) {
                return;
              }

              // Re-fetch player instance to avoid stale references during world transfers
              LocalPlayer player = minecraft.player;
              if (player != null && player.connection != null) {
                // Get the player name.
                if (playerName.equals(StringUtils.EMPTY)) {
                  playerName = player.getName().getString();
                }

                try {
                  player.connection.sendCommand("guild stats");
                  commandSent[0] = true;
                } catch (Exception e) {
                  LOGGER.warn("Failed to send guild stats command: {}", e.getMessage());
                }
              }
            } finally {
              latch.countDown();
            }
          });

          // Block until the render thread has executed our lambda
          latch.await(2, TimeUnit.SECONDS);

          if (commandSent[0]) {
            // Wait up to 2 seconds for server response
            int responseWait = 0;
            while (responseWait < 8 && waitingForGuildStats) { // 8 * 250ms = 2 seconds
              Thread.sleep(250);
              responseWait++;
            }

            // If we got a response (waitingForGuildStats became false), we're done
            if (!waitingForGuildStats) {
              guildStatsCompleted = true; // Mark as ready for user commands
              break;
            }
          }

          attempts++;

        } catch (InterruptedException e) {
          LOGGER.warn("Guild stats command interrupted");
          waitingForGuildStats = false;
          isModInitiatedGuildStats = false;
          break;
        }
      }

      if (attempts >= maxAttempts) {
        LOGGER.warn("Failed to send guild stats command after {} attempts", maxAttempts);
        waitingForGuildStats = false;
        isModInitiatedGuildStats = false;
      }
    }).start();
  }

  /**
   * Fetch and display the MOTD message
   */
  private static void fetchAndDisplayMotd() {
    // Check if auto-messages are enabled
    if (!VetsConfig.get(VetsConfig.VETS_AUTOMESSAGE)) {
      LOGGER.info("Auto-messages disabled, skipping MOTD");
      return;
    }

    LOGGER.info("Auto-messages enabled, fetching MOTD");
    Minecraft minecraft = Minecraft.getInstance();
    LocalPlayer player = minecraft.player;

    if (player != null) {
      MotdFetcher.fetchMotd().thenAccept(motdComponent -> {
        // Send the MOTD to the player's chat
        ChatUtils.sendLocalMessage(motdComponent, Prepend.DEFAULT);
      });
    }
  }

  /**
   * Fetch and display the annihilation stamp message (if applicable)
   */
  private static void fetchAndDisplayStampMessage() {
    // Check if auto-messages are enabled
    if (!VetsConfig.get(VetsConfig.VETS_AUTOMESSAGE)) {
      LOGGER.info("Auto-messages disabled, skipping stamp message");
      return;
    }

    LOGGER.info("Auto-messages enabled, fetching stamp");
    Minecraft minecraft = Minecraft.getInstance();
    LocalPlayer player = minecraft.player;

    if (player != null) {
      StampFetcher.fetchStampAndCreateMessage().thenAccept(stampMessage -> {
        if (stampMessage != null) {
          LOGGER.info("Displaying annihilation countdown");
          ChatUtils.sendLocalMessage(stampMessage, Prepend.DEFAULT);
        }
      });
    }
  }

  /**
   * Attempt to unlock with a password
   *
   * @param password The password to check
   * @return true if unlock successful, false otherwise
   */
  public static boolean tryUnlock(String password) {
    String hash = sha256(password);
    if (hash != null && hash.equals(UNLOCK_PASSWORD_HASH)) {
      passwordUnlocked = true;
      LOGGER.info("Mod unlocked via password");
      return true;
    }
    return false;
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
      LOGGER.error("SHA-256 algorithm not available", e);
      return null;
    }
  }

  /**
   * Reset the stored guild information
   */
  public static void reset() {
    isReturners = false;
    isGuildless = false;
    debugForceGuildlessUnlocked = false;
    waitingForGuildStats = false;
    guildStatsRequestTime = 0;
    lastMotdFetchTime = 0;
    isModInitiatedGuildStats = false;
    guildStatsSuppressUntil = 0;
    staffRankSuppressUntil = 0;
    guildStatsCompleted = false;
    isStaff = VetsConfig.get(VetsConfig.VETS_IS_STAFF);
    long persistedCheckTime = VetsConfig.getLong(VetsConfig.VETS_LAST_STAFF_CHECK);
    long now = System.currentTimeMillis();
    lastStaffCheckTime = (persistedCheckTime >= 0 && persistedCheckTime <= now) ? persistedCheckTime : 0;
    waitingForStaffRankCheck = false;
    isModInitiatedStaffRankCheck = false;
    staffRankRequestTime = 0;
  }
}
