package org.wynnvets.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.chat.spoiler.SpoilerCodec;
import org.wynnvets.chat.dispatcher.CommandDispatcher;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

/**
 * Handles outbound chat-command interception for guild chat ({@code /g}),
 * honourary chat ({@code /wg}), staff chat ({@code /v}), announcements
 * ({@code /a}), and the encourage command ({@code /encourage}).
 *
 * <p>Called from {@link org.wynnvets.mixin.client.GuildChatCommandMixin}
 * to keep mixin code minimal. Each handler returns {@code true} when the
 * original command should be cancelled (intercepted), or {@code false} to
 * let it pass through to the server normally.</p>
 */
public final class GuildChatDispatcher {

  private static final int STAFF_CHAT_MAX_LENGTH = 234;
  private static final String GUILDLESS_SELF_RANK =
      "\uE010\uE056\uE040\uE048\uE053\uE04B\uE048\uE052\uE053\uE011";
  private static final String HONOURARY_SELF_RANK =
      "\uE010\uE047\uE04E\uE04D\uE04E\uE054\uE051\uE040\uE051\uE058\uE011";

  private GuildChatDispatcher() {}

  /**
   * Attempts to intercept an outbound chat command.
   *
   * @param command the command string (without leading {@code /})
   * @return {@code true} if the command was handled and should be cancelled;
   *         {@code false} to let it pass through to the server
   */
  public static boolean intercept(String command) {
    if (command.regionMatches(true, 0, "g ", 0, 2)) {
      return handleGuildChat(command.substring(2));
    }
    if (command.regionMatches(true, 0, "wg ", 0, 3)) {
      return handleHonouraryChat(command.substring(3));
    }
    if (command.regionMatches(true, 0, "v ", 0, 2)) {
      return handleStaffChat(command.substring(2).trim());
    }
    if (command.regionMatches(true, 0, "a ", 0, 2)) {
      return handleAnnounce(command.substring(2).trim());
    }
    if (command.regionMatches(true, 0, "encourage ", 0, 10)) {
      return handleEncourage(command.substring(10).trim());
    }
    if (command.regionMatches(true, 0, "wv check ", 0, 9)) {
      return handleWvCheck(command.substring(9).trim());
    }
    return false;
  }

  // ── /g — Guild chat ─────────────────────────────────────────────────

  /**
   * Handles {@code /g <message>}. For waitlist users the message is relayed
   * via WebSocket. For Returners, spoiler markers are encoded before sending.
   *
   * @return {@code true} to cancel the original command
   */
  private static boolean handleGuildChat(String message) {
    // Waitlist (guildless) relay
    if (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked()) {
      VetsLogger.debug("Intercepted /g for waitlist bridge relay");
      String outMessage = !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfig.HANDLE_SPOILERS))
          ? SpoilerCodec.encodeSpoilers(message) : message;
      relayWaitlistChat(outMessage);
      return true;
    }

    // Returners spoiler encoding
    if (GuildStateManager.isReturners()
        && !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfig.HANDLE_SPOILERS))
        && SpoilerCodec.containsPipeSpoiler(message)) {
      String encoded = SpoilerCodec.encodeSpoilers(message);
      if (encoded.length() > 253) {
        ChatUtils.sendLocalMessage(
            Component.literal("Encoding your spoilers would exceed Wynn's 253 character limit.")
                .withStyle(ChatFormatting.RED)
        );
        return true;
      }
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && minecraft.player.connection != null) {
        minecraft.player.connection.sendCommand("g " + encoded);
      }
      return true;
    }

    return false; // let normal /g pass through
  }

  // ── /wg — Honourary guild chat ──────────────────────────────────────

  private static boolean handleHonouraryChat(String message) {
    if (GuildStateManager.isHonouraryUnlocked()) {
      VetsLogger.debug("Intercepted /wg for honourary bridge relay");
      String outMessage = !Boolean.FALSE.equals(VetsConfig.getTriState(VetsConfig.HANDLE_SPOILERS))
          ? SpoilerCodec.encodeSpoilers(message) : message;
      relayHonouraryChat(outMessage);
    } else {
      ChatUtils.sendLocalMessage(
          Component.literal("You must unlock with an honourary password to use /wg.")
              .withStyle(ChatFormatting.RED)
      );
    }
    return true;
  }

  // ── /v — Staff chat ─────────────────────────────────────────────────

  private static boolean handleStaffChat(String message) {
    if (!requireStaff("/v")) return true;

    if (message.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /v <message>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    if (message.length() > STAFF_CHAT_MAX_LENGTH) {
      ChatUtils.sendLocalMessage(
          Component.literal("/v messages are limited to " + STAFF_CHAT_MAX_LENGTH + " characters.")
              .withStyle(ChatFormatting.RED)
      );
      return true;
    }

    dispatchStaffChat(message);
    return true;
  }

  // ── /a — Staff announcement ─────────────────────────────────────────

  private static boolean handleAnnounce(String message) {
    if (!requireStaff("/a")) return true;

    if (message.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /a <message>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    CommandDispatcher.executeWithStaffEligibilityGate(() -> {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && minecraft.player.connection != null) {
        minecraft.player.connection.sendCommand("g \u203C" + message);
      }
    });
    return true;
  }

  // ── /encourage — Encourage update ───────────────────────────────────

  private static boolean handleEncourage(String version) {
    if (!requireStaff("/encourage")) return true;

    if (version.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /encourage <version>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    CommandDispatcher.executeWithStaffEligibilityGate(() -> {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && minecraft.player.connection != null) {
        minecraft.player.connection.sendCommand(
            "g \u26A0\u26A0\u26A0 If you are using vetsmod, it's outdated (current version "
                + version + ") \u26A0\u26A0\u26A0");
      }
    });
    return true;
  }

  // ── /wv check — Player lookup ───────────────────────────────────────

  private static boolean handleWvCheck(String playerName) {
    if (!requireStaff("/wv check")) return true;

    if (playerName.isEmpty()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /wv check <playerName>").withStyle(ChatFormatting.RED)
      );
      return true;
    }

    UserInfoFetcher.checkUser(playerName)
        .thenAccept(userInfo -> ChatUtils.sendLocalMessage(userInfo));
    return true;
  }

  // ── Shared helpers ──────────────────────────────────────────────────

  /**
   * Common staff permission gate. Triggers a refresh if needed and warns
   * the user when permissions are still being checked.
   *
   * @param commandLabel the command name for error messages
   * @return {@code true} if the user has staff access; {@code false} if not
   */
  private static boolean requireStaff(String commandLabel) {
    boolean isCurrentlyStaff = GuildStateManager.isStaff();
    boolean refreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

    if (refreshStarted || GuildStateManager.isCheckingStaffStatus()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Checking staff permissions, please retry in a moment.")
              .withStyle(ChatFormatting.YELLOW)
      );
      return false;
    }

    if (!GuildStateManager.isStaff()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be staff to use " + commandLabel + ".")
              .withStyle(ChatFormatting.RED)
      );
      return false;
    }

    return true;
  }

  private static void relayWaitlistChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;

    String username = resolveUsername(minecraft);
    OutboundDisplayHandler.queuePendingSelfMessage(username, message);
    ChatUtils.sendGuildChatMessage(GUILDLESS_SELF_RANK, username, message);
    V1ApiManager.sendInbound("waitlist", "Waitlist", username, message);
  }

  private static void relayHonouraryChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;

    String username = resolveUsername(minecraft);
    OutboundDisplayHandler.queuePendingSelfMessage(username, message);
    ChatUtils.sendGuildChatMessage(HONOURARY_SELF_RANK, username, message);
    V1ApiManager.sendInbound("honourary", "Honourary", username, message);
  }

  private static void dispatchStaffChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;

    String username = resolveUsername(minecraft);
    String rank = StaffRanksPoller.confirmedRankFor(username)
        .orElseGet(() -> {
          String selfRank = GuildStateManager.selfStaffRank();
          return (selfRank == null || selfRank.isEmpty()) ? "captain" : selfRank;
        });

    CommandDispatcher.dispatchStaffChatWithEligibilityGate(username, message, rank);
  }

  private static String resolveUsername(Minecraft minecraft) {
    String username = GuildStateManager.playerName();
    if (username == null || username.isEmpty()) {
      username = minecraft.player.getName().getString();
    }
    return username;
  }
}
