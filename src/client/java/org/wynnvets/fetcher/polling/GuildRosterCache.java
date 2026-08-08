package org.wynnvets.fetcher.polling;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically fetches the guild UUID→current-username roster from the
 * VetsMod server and caches it locally.
 *
 * <p>Wynncraft's {@code /v3/guild/{name}} endpoint can return stale
 * usernames for guild members. The VetsMod server resolves each member's
 * UUID against the Minecraft Services API and exposes the corrected
 * mapping at {@code GET /v1/outbound/roster}. This class polls that
 * endpoint and provides a thread-safe lookup for the rest of the mod.</p>
 */
public final class GuildRosterCache {
  private static final int REFRESH_INTERVAL_MINUTES = 5;

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final HttpRequest ROSTER_REQUEST = HttpRequest.newBuilder()
      .uri(VetsApi.ROSTER)
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();

  private static final Gson GSON = new Gson();

  /** UUID → current username (thread-safe snapshot). */
  private static volatile Map<String, String> rosterByUuid = Map.of();

  private static ScheduledExecutorService scheduler;
  private static boolean isRunning = false;

  private GuildRosterCache() {}

  public static void start() {
    if (isRunning) {
      return;
    }

    isRunning = true;
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "VetsMod-GuildRosterCache");
      thread.setDaemon(true);
      return thread;
    });

    scheduler.scheduleAtFixedRate(() -> {
      try {
        fetchRoster();
      } catch (Exception e) {
        VetsLogger.warn("Error fetching guild roster: {}", e.getMessage());
      }
    }, 0, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
  }

  public static void stop() {
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    isRunning = false;
  }

  /**
   * Returns the current username for a guild member UUID, or {@code null}
   * if the UUID is not in the roster (or the roster hasn't loaded yet).
   *
   * @param uuid the player's UUID (hyphenated or unhyphenated)
   * @return the current Minecraft username, or {@code null}
   */
  public static String getUsername(String uuid) {
    if (uuid == null || uuid.isEmpty()) {
      return null;
    }
    return rosterByUuid.get(uuid);
  }

  /**
   * Returns the full UUID→username roster snapshot.
   *
   * @return an unmodifiable map of UUID → current username
   */
  public static Map<String, String> getRoster() {
    return rosterByUuid;
  }

  private static void fetchRoster() {
    try {
      HttpResponse<String> response = HTTP_CLIENT.send(
          ROSTER_REQUEST, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == HttpURLConnection.HTTP_OK) {
        parseRoster(response.body());
      } else {
        VetsLogger.debug("Roster fetch failed: {}", response.statusCode());
      }
    } catch (Exception e) {
      VetsLogger.debug("Failed to fetch roster: {}", e.getMessage());
    }
  }

  private static void parseRoster(String jsonResponse) {
    try {
      JsonObject obj = GSON.fromJson(jsonResponse, JsonObject.class);
      if (obj == null) {
        return;
      }

      Map<String, String> newRoster = new ConcurrentHashMap<>();
      for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
        if (entry.getValue().isJsonPrimitive()) {
          String username = entry.getValue().getAsString();
          if (username != null && !username.isEmpty()) {
            newRoster.put(entry.getKey(), username);
          }
        }
      }

      rosterByUuid = Map.copyOf(newRoster);
      VetsLogger.debug("Guild roster loaded: {} members", rosterByUuid.size());
    } catch (Exception e) {
      VetsLogger.debug("Error parsing roster: {}", e.getMessage());
    }
  }
}
