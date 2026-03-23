package org.wynnvets.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.fetcher.polling.StaffRanksFetcher;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.StaffOutboundMessenger;
import org.wynnvets.logging.VetsLogger;

/**
 * Intercepts outbound chat commands to handle guild chat ({@code /g}),
 * honourary guild chat ({@code /wg}), staff chat ({@code /v}), and
 * debug commands.
 *
 * <p>For guildless-but-unlocked (waitlist) users, {@code /g} messages are
 * relayed through the v1 WebSocket inbound endpoint. For honourary-unlocked
 * users, {@code /wg} messages are relayed as honourary type. Staff chat
 * ({@code /v}) fans out encrypted direct messages to all online staff
 * members via {@link StaffOutboundMessenger}.</p>
 */
@Mixin(ClientPacketListener.class)
public class GuildChatCommandMixin {
  private static final int STAFF_CHAT_MAX_LENGTH = 234;
  private static final String GUILDLESS_SELF_RANK = "\uE010\uE056\uE040\uE048\uE053\uE04B\uE048\uE052\uE053\uE011";
  private static final String HONOURARY_SELF_RANK = "\uE010\uE047\uE04E\uE04D\uE04E\uE056\uE051\uE040\uE051\uE058\uE011";

  @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
  private void onSendCommand(String command, CallbackInfo ci) {
    // Check if this is a guild chat command for guildless+waitlist-unlocked users
    if (command.regionMatches(true, 0, "g ", 0, 2)) {
      if (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked()) {
        VetsLogger.debug("Intercepted /g for waitlist bridge relay");
        ci.cancel();
        String message = command.substring(2);
        handleWaitlistChat(message);
      }
      return;
    }

    // Honourary guild chat command: /wg <message>
    if (command.regionMatches(true, 0, "wg ", 0, 3)) {
      if (GuildStateManager.isHonouraryUnlocked()) {
        VetsLogger.debug("Intercepted /wg for honourary bridge relay");
        ci.cancel();
        String message = command.substring(3);
        handleHonouraryChat(message);
      } else {
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("You must unlock with an honourary password to use /wg.")
                .withStyle(ChatFormatting.RED)
        );
      }
      return;
    }

    // Staff command variant: /v <message>
    if (command.regionMatches(true, 0, "v ", 0, 2)) {
      boolean isCurrentlyStaff = GuildStateManager.isStaff();
      boolean refreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

      if (refreshStarted || GuildStateManager.isCheckingStaffStatus()) {
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("Checking staff permissions, please retry in a moment.")
                .withStyle(ChatFormatting.YELLOW)
        );
        return;
      }

      if (!GuildStateManager.isStaff()) {
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("You must be staff to use /v.")
                .withStyle(ChatFormatting.RED)
        );
        return;
      }

      ci.cancel();
      String message = command.substring(2).trim();
      if (message.isEmpty()) {
        ChatUtils.sendLocalMessage(
            Component.literal("Usage: /v <message>")
                .withStyle(ChatFormatting.RED)
        );
        return;
      }

      if (message.length() > STAFF_CHAT_MAX_LENGTH) {
        ChatUtils.sendLocalMessage(
            Component.literal("/v messages are limited to " + STAFF_CHAT_MAX_LENGTH + " characters.")
                .withStyle(ChatFormatting.RED)
        );
        return;
      }

      handleStaffChat(message);
      return;
    }

    // Staff command alias: /a <message> -> /g ‼<message>
    if (command.regionMatches(true, 0, "a ", 0, 2)) {
      boolean isCurrentlyStaff = GuildStateManager.isStaff();
      boolean refreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

      if (refreshStarted || GuildStateManager.isCheckingStaffStatus()) {
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("Checking staff permissions, please retry in a moment.")
                .withStyle(ChatFormatting.YELLOW)
        );
        return;
      }

      if (!GuildStateManager.isStaff()) {
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("You must be staff to use /a.")
                .withStyle(ChatFormatting.RED)
        );
        return;
      }

      ci.cancel();
      String message = command.substring(2).trim();
      if (message.isEmpty()) {
        ChatUtils.sendLocalMessage(
            Component.literal("Usage: /a <message>")
                .withStyle(ChatFormatting.RED)
        );
        return;
      }

      StaffOutboundMessenger.executeWithStaffEligibilityGate(() -> {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
          minecraft.player.connection.sendCommand("g ‼" + message);
        }
      });
      return;
    }

    // Fallback interception for /wv check <playerName> to keep it client-side.
    if (command.regionMatches(true, 0, "wv check ", 0, 9)) {
      boolean isCurrentlyStaff = GuildStateManager.isStaff();
      boolean refreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

      if (refreshStarted || GuildStateManager.isCheckingStaffStatus()) {
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("Checking staff permissions, please retry in a moment.")
                .withStyle(ChatFormatting.YELLOW)
        );
        return;
      }

      if (!GuildStateManager.isStaff()) {
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("You must be staff to use /wv check.")
                .withStyle(ChatFormatting.RED)
        );
        return;
      }

      ci.cancel();
      String playerName = command.substring(9).trim();
      if (playerName.isEmpty()) {
        ChatUtils.sendLocalMessage(
            Component.literal("Usage: /wv check <playerName>")
                .withStyle(ChatFormatting.RED)
        );
        return;
      }

      UserInfoFetcher.checkUser(playerName)
          .thenAccept(userInfo -> ChatUtils.sendLocalMessage(userInfo));
    }
  }

  /**
   * Handle /g guild chat bridge for waitlist (guildless) users by sending
   * to the v1 inbound WebSocket.
   *
   * @param message The message to send
   */
  private void handleWaitlistChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) {
      return;
    }

    String username = GuildStateManager.playerName();
    if (username == null || username.isEmpty()) {
      username = minecraft.player.getName().getString();
    }

    ChatUtils.sendGuildChatMessage(GUILDLESS_SELF_RANK, username, message);
    V1ApiManager.sendInbound("waitlist", "Waitlist", username, message);
  }

  /**
   * Handle /wg guild chat bridge for honourary users by sending to the v1
   * inbound WebSocket.
   *
   * @param message The message to send
   */
  private void handleHonouraryChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) {
      return;
    }

    String username = GuildStateManager.playerName();
    if (username == null || username.isEmpty()) {
      username = minecraft.player.getName().getString();
    }

    ChatUtils.sendGuildChatMessage(HONOURARY_SELF_RANK, username, message);
    V1ApiManager.sendInbound("honourary", "Honourary", username, message);
  }

  /**
    * Handle /v local staff chat echo and outbound staff fanout.
   * Defaults to Captain pill when rank is unknown.
   */
  private void handleStaffChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();

    if (minecraft.player == null) {
      return;
    }

    String username = GuildStateManager.playerName();
    if (username == null || username.isEmpty()) {
      username = minecraft.player.getName().getString();
    }

    String rank = StaffRanksFetcher.confirmedRankFor(username)
        .orElseGet(() -> {
          String selfRank = GuildStateManager.selfStaffRank();
          return (selfRank == null || selfRank.isEmpty()) ? "captain" : selfRank;
        });

    StaffOutboundMessenger.dispatchStaffChatWithEligibilityGate(username, message, rank);
  }
}
