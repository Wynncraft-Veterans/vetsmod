package org.wynnvets.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.fetcher.polling.BridgeMessageFetcher;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.fetcher.polling.StaffRanksFetcher;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.StaffOutboundMessenger;
import org.wynnvets.logging.VetsLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Intercepts outbound chat commands to handle guild chat ({@code /g}),
 * staff chat ({@code /v}), and debug commands.
 *
 * <p>For guildless-but-unlocked users, {@code /g} messages are relayed
 * through the VetsMod API instead of the server. Staff chat ({@code /v})
 * fans out encrypted direct messages to all online staff members via
 * {@link StaffOutboundMessenger}.</p>
 */
@Mixin(ClientPacketListener.class)
public class GuildChatCommandMixin {
  private static final String API_ENDPOINT = "http://api.wynnvets.org/v0/inbound";
  private static final int STAFF_CHAT_MAX_LENGTH = 234;
  private static final String GUILDLESS_SELF_RANK = "\uE010\uE056\uE040\uE048\uE053\uE04B\uE048\uE052\uE053\uE011";
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
  private void onSendCommand(String command, CallbackInfo ci) {
    // Check if this is a guild chat command for guildless+unlocked users
    if (command.regionMatches(true, 0, "g ", 0, 2)) {
      if (GuildStateManager.isGuildless() && GuildStateManager.isUnlocked()) {
        VetsLogger.debug("Intercepted /g for guildless+unlocked bridge relay");
        ci.cancel();
        String message = command.substring(2);
        handleGuildChat(message);
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
   * Handle /g guild chat bridge by sending to API endpoint
   *
   * @param message The message to send
   */
  private void handleGuildChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();

    if (minecraft.player == null) {
      return;
    }

    String username = GuildStateManager.playerName();
    if (username == null || username.isEmpty()) {
      username = minecraft.player.getName().getString();
    }

    ChatUtils.sendGuildChatMessage(GUILDLESS_SELF_RANK, username, message);
    BridgeMessageFetcher.queuePendingSelfMessage(username, message);

    // Get current timestamp
    String timestamp = LocalDateTime.now(ZoneOffset.UTC).format(TIMESTAMP_FORMATTER);

    // Build JSON payload
    String jsonPayload = String.format(
        "{\"message\":\"%s\",\"rank\":\"Waitlist\",\"timestamp\":\"%s\",\"username\":\"%s\"}",
        escapeJson(message),
        timestamp,
        escapeJson(username)
    );

    // Send the request asynchronously
    new Thread(() -> {
      try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_ENDPOINT))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          VetsLogger.debug("Guild chat message sent to API");
        } else {
          VetsLogger.warn("Failed to send guild chat message. Status: {}, Response: {}", 
              response.statusCode(), response.body());
          
          ChatUtils.sendLocalMessage(
              Component.literal("Failed to send guild message (status: " + response.statusCode() + ")")
                  .withStyle(ChatFormatting.RED)
          );
        }
      } catch (Exception e) {
        VetsLogger.error("Error sending guild chat message to API", e);
        
        ChatUtils.sendLocalMessage(
            Component.literal("Error sending guild message: " + e.getMessage())
                .withStyle(ChatFormatting.RED)
        );
      }
    }).start();
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

  /**
   * Escape special characters for JSON
   *
   * @param input The input string
   * @return The escaped string
   */
  private String escapeJson(String input) {
    if (input == null) {
      return "";
    }
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
