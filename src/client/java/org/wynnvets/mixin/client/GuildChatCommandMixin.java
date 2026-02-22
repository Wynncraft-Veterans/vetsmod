package org.wynnvets.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.util.BridgeMessageFetcher;
import org.wynnvets.util.GuildInfoListener;
import org.wynnvets.util.chat.ChatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Mixin(ClientPacketListener.class)
public class GuildChatCommandMixin {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");
  private static final String API_ENDPOINT = "http://api.wynnvets.org/v0/inbound";
  private static final String GUILDLESS_SELF_RANK = "\uE010\uE056\uE040\uE048\uE053\uE04B\uE048\uE052\uE053\uE011";
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
  private void onSendCommand(String command, CallbackInfo ci) {
    // Check if this is a guild chat command
    if (command.regionMatches(true, 0, "g ", 0, 2)) {
      // Only hijack if user is guildless AND unlocked
      if (GuildInfoListener.isGuildless() && GuildInfoListener.isUnlocked()) {
        // Cancel the server command
        ci.cancel();

        // Extract the message (everything after "g ")
        String message = command.substring(2);

        // Handle the guild chat
        handleGuildChat(message);
      }
      // If not (guildless AND unlocked), let the command go through normally
    }
  }

  /**
   * Handle guild chat by sending to API endpoint
   *
   * @param message The message to send
   */
  private void handleGuildChat(String message) {
    Minecraft minecraft = Minecraft.getInstance();

    if (minecraft.player == null) {
      return;
    }

    String username = GuildInfoListener.playerName();
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
          LOGGER.info("Guild chat message sent successfully to API");
        } else {
          LOGGER.warn("Failed to send guild chat message. Status: {}, Response: {}", 
              response.statusCode(), response.body());
          
          ChatUtils.sendLocalMessage(
              Component.literal("Failed to send guild message (status: " + response.statusCode() + ")")
                  .withStyle(ChatFormatting.RED)
          );
        }
      } catch (Exception e) {
        LOGGER.error("Error sending guild chat message to API", e);
        
        ChatUtils.sendLocalMessage(
            Component.literal("Error sending guild message: " + e.getMessage())
                .withStyle(ChatFormatting.RED)
        );
      }
    }).start();
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
