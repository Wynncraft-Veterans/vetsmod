package org.wynnvets.fetcher.polling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.wynnvets.api.VetsApi;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polling fetcher that periodically retrieves guild chat messages from the
 * VetsMod API and displays them in the player's chat HUD.
 *
 * <p>Runs on a fixed-interval scheduler, deduplicating messages by ID so that
 * previously displayed messages are not shown again. Only active when the
 * player's guild features are enabled.</p>
 */
public class ChatMessageFetcher {
  private static final int FETCH_INTERVAL_SECONDS = 3;
  private static final int MAX_CACHED_MESSAGE_IDS = 1000;

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final HttpRequest CHAT_REQUEST = HttpRequest.newBuilder()
      .uri(VetsApi.CHAT_OUTBOUND)
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();

  private static final Gson GSON = new Gson();
  private static final Set<String> displayedMessageIds = new LinkedHashSet<>();
  private static ScheduledExecutorService scheduler;
  private static boolean isRunning = false;

  /**
   * Starts the periodic fetching of chat messages
   */
  public static void start() {
    if (isRunning) {
      return;
    }

    isRunning = true;
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "VetsMod-ChatFetcher");
      thread.setDaemon(true);
      return thread;
    });

    // Schedule the fetch task to run every 3 seconds
    scheduler.scheduleAtFixedRate(() -> {
      try {
        fetchAndDisplayMessages();
      } catch (Exception e) {
        VetsLogger.warn("Error fetching chat messages: {}", e.getMessage());
      }
    }, 0, FETCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Stops the periodic fetching
   */
  public static void stop() {
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
      }
    }
    isRunning = false;
  }

  /**
   * Fetches messages from the API and displays new ones in chat
   */
  private static void fetchAndDisplayMessages() {
    // Only fetch messages if features are enabled (guild is Returners)
    if (!GuildStateManager.areFeaturesEnabled()) {
      return;
    }

    try {
      HttpResponse<String> response = HTTP_CLIENT.send(CHAT_REQUEST, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == HttpURLConnection.HTTP_OK) {
        processMessages(response.body());
      }
    } catch (Exception e) {
      VetsLogger.debug("Failed to fetch chat messages: {}", e.getMessage());
    }
  }

  /**
   * Processes the JSON response and displays new messages
   */
  private static void processMessages(String jsonResponse) {
    try {
      JsonArray messages = GSON.fromJson(jsonResponse, JsonArray.class);

      // Limit processing to prevent overwhelming the system
      int processedCount = 0;
      int maxPerBatch = 10;

      for (int i = 0; i < messages.size() && processedCount < maxPerBatch; i++) {
        JsonObject messageObj = messages.get(i).getAsJsonObject();

        String id = messageObj.get("id").getAsString();

        // Only display messages we haven't seen before
        if (!displayedMessageIds.contains(id)) {
          // Prevent unbounded growth - remove oldest entry if cache is full
          if (displayedMessageIds.size() >= MAX_CACHED_MESSAGE_IDS) {
            String firstId = displayedMessageIds.iterator().next();
            displayedMessageIds.remove(firstId);
          }
          displayedMessageIds.add(id);

          // Extract strings from JSON
          String displayName = messageObj.get("display_name").getAsString();
          String message = messageObj.get("message").getAsString();
          String rank = messageObj.get("rank").getAsString();

          if (BridgeMessageFetcher.shouldSuppressDuplicateApiMessage(displayName, message)) {
            continue;
          }

          ChatUtils.sendGuildChatMessage(rank, displayName, message);
          processedCount++;
        }
      }
    } catch (Exception e) {
      VetsLogger.warn("Error processing chat messages: {}", e.getMessage());
    }
  }

  /**
   * Clears the cache of displayed message IDs
   */
  public static void clearCache() {
    displayedMessageIds.clear();
  }
}
