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

public class GuildInfoListener {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");

  // Stored guild information
  private static boolean isReturners = false;
  private static String playerName = StringUtils.EMPTY;

  // State tracking for guild stats detection
  private static boolean waitingForGuildStats = false;
  private static long guildStatsRequestTime = 0;
  private static final long GUILD_STATS_TIMEOUT = 5000; // 5 second timeout

  // Track if the current guild stats request was initiated by the mod (not the user)
  private static boolean isModInitiatedGuildStats = false;

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
    return isModInitiatedGuildStats;
  }

  /**
   * Process incoming chat messages to detect guild information
   *
   * @param component The chat message Component (with formatting)
   * @param message   The plain text chat message
   */
  public static void processMessage(Component component, String message) {
    // Check if we're waiting for guild stats and if we've timed out
    if (waitingForGuildStats && System.currentTimeMillis() - guildStatsRequestTime > GUILD_STATS_TIMEOUT) {
      waitingForGuildStats = false;
      isModInitiatedGuildStats = false; // Clear flag on timeout
    }

    // Check for Wynncraft welcome message with gold+bold formatting
    String literalContent = component.toString();

    // Check if this is the welcome message with color=#FFAA00 (gold) and bold formatting
    // The component string contains "literal{Welcome to Wynncraft!" and "color=#FFAA00,bold"
    if (literalContent.contains("Welcome to Wynncraft!") &&
        literalContent.contains("color=#FFAA00") &&
        literalContent.contains("bold")) {
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

    // If we're waiting for guild stats, check for the guild name
    if (waitingForGuildStats) {
      // Check if this message contains the formatted "Returners" guild name
      // Looking for "Returners" with gold color and bold formatting
      // The guild name appears as: literal{Returners}[style={color=gold,bold}]
      // Use regex-like check to ensure we match "bold" not "!bold"
      boolean hasGoldColor = literalContent.contains("color=gold") || literalContent.contains("color=#FFAA00");
      boolean hasBoldStyle = (literalContent.contains(",bold") || literalContent.contains("{bold") ||
          literalContent.contains("=bold") || literalContent.contains(" bold")) &&
          !literalContent.contains("!bold");

      if (literalContent.contains("Returners") && hasGoldColor && hasBoldStyle) {
        // Found "Returners" with gold+bold formatting - this is the Returners guild
        LOGGER.info("Detected guild: Returners - enabling features");
        isReturners = true;
        // Don't set waitingForGuildStats to false yet - wait for the full stats to complete
        // Don't clear isModInitiatedGuildStats yet - guild stats output continues
      } else if (hasGoldColor && hasBoldStyle &&
          message.trim().length() > 0 &&
          !message.trim().isEmpty() &&
          !literalContent.contains("Returners") &&
          !literalContent.contains("Welcome to an Alpha version of VETSMOD")) {
        // Found a different guild name (has gold+bold formatting but isn't Returners)
        // Temporarily exclude the MOTD message which also has gold+bold formatting
        LOGGER.info("Detected different guild - features disabled");
        // Don't set waitingForGuildStats to false yet - wait for the full stats to complete
        // Don't clear isModInitiatedGuildStats yet - guild stats output continues
      }
    }

    // Check if this is the last message in guild stats output to clear the flags
    if (isModInitiatedGuildStats && message.contains("Total Members:")) {
      // This is the last line of guild stats output
      isModInitiatedGuildStats = false;
      waitingForGuildStats = false; // Signal that guild stats is complete

      // After guild stats completes, check for annihilation stamp if we're in Returners
      if (isReturners) {
        fetchAndDisplayStampMessage();
      }
    }
  }

  /**
   * Send the /guild stats command to the server
   */
  private static void sendGuildStatsCommand() {
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

          boolean[] commandSent = {false};
          minecraft.execute(() -> {
            // Check if we're in WORLD state using Wynntils API
            WorldState currentState = Models.WorldState.getCurrentState();

            if (currentState != WorldState.WORLD) {
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
          });

          // Wait a bit for the execute to complete
          Thread.sleep(50);

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
        minecraft.execute(() -> player.displayClientMessage(motdComponent, false));
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
          minecraft.execute(() -> player.displayClientMessage(stampMessage, false));
        }
      });
    }
  }

  /**
   * Reset the stored guild information
   */
  public static void reset() {
    isReturners = false;
    waitingForGuildStats = false;
    guildStatsRequestTime = 0;
    lastMotdFetchTime = 0;
    isModInitiatedGuildStats = false;
    guildStatsCompleted = false;
  }
}
