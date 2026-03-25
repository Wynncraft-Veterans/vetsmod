package org.wynnvets.guild;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import com.wynntils.models.worlds.type.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.Prepend;
import org.wynnvets.chat.StaffOutboundMessenger;
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
  private static final String WAITLIST_PASSWORD_HASH = "d4c4f49d09ae0fc5e88f23f47a135d3e509a0799cebc711943370e80e58e145b";
  private static final String HONOURARY_PASSWORD_HASH = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

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

  // Tracks whether the guild-specific MOTD was shown this session.
  // When guild info isn't available at world-join time, the standard MOTD is
  // shown instead; this flag lets onGuildInfoUpdated() fix that once the
  // guild model is populated.
  private static boolean guildMotdDisplayedThisSession = false;

  // Track whether we have entered a world at least once since reset, so that
  // commands are not executed before initial guild info is available.
  private static boolean enteredWorld = false;

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

    long currentTime = System.currentTimeMillis();
    if (currentTime - lastMotdFetchTime > MOTD_FETCH_COOLDOWN) {
      lastMotdFetchTime = currentTime;
      fetchAndDisplayMotd();
    }

    if (isReturners()) {
      fetchAndDisplayStampMessage();
    }

    refreshStaffStatusIfNeeded(false);

    VetsLogger.debug("onEnteredWorld: guild={}, guildless={}, returners={}",
        Models.Guild.getGuildName(), isGuildless(), isReturners());
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
    if (hash.equals(WAITLIST_PASSWORD_HASH)) {
      waitlistUnlocked = true;
      VetsLogger.debug("Mod unlocked via waitlist password");
      return UnlockType.WAITLIST;
    }
    if (hash.equals(HONOURARY_PASSWORD_HASH)) {
      honouraryUnlocked = true;
      VetsLogger.debug("Mod unlocked via honourary password");
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
   * Reset all transient state. Called on server disconnect so that the next
   * world join starts fresh.
   */
  public static void reset() {
    debugForceGuildlessUnlocked = false;
    lastMotdFetchTime = 0;
    guildMotdDisplayedThisSession = false;
    staffRankSuppressUntil = 0;
    enteredWorld = false;
    isStaff = VetsConfig.get(VetsConfig.VETS_IS_STAFF);
    long persistedCheckTime = VetsConfig.getLong(VetsConfig.VETS_LAST_STAFF_CHECK);
    long now = System.currentTimeMillis();
    lastStaffCheckTime = (persistedCheckTime >= 0 && persistedCheckTime <= now) ? persistedCheckTime : 0;
    waitingForStaffRankCheck = false;
    isModInitiatedStaffRankCheck = false;
    staffRankRequestTime = 0;
    StaffOutboundMessenger.resetStaffChatEligibilityCache();
  }
}
