package org.wynnvets.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.wynnvets.constants.WVApi;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fetches and caches the list of supporters from the API.
 *
 * <p>Supporters receive special pill styling (gradient) in guild chat.
 * The list is refreshed every {@value #REFRESH_INTERVAL_MINUTES} minutes.</p>
 */
public class SupportersFetcher {
  private static final int REFRESH_INTERVAL_MINUTES = 5;

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final HttpRequest SUPPORTERS_REQUEST = HttpRequest.newBuilder()
      .uri(WVApi.Supporters)
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();

  private static final Gson GSON = new Gson();
  private static final Set<String> supporterUsernames = ConcurrentHashMap.newKeySet();
  private static ScheduledExecutorService scheduler;
  private static boolean isRunning = false;

  /**
   * Starts periodic fetching of the supporters list.
   */
  public static void start() {
    if (isRunning) {
      return;
    }

    isRunning = true;
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "VetsMod-SupportersFetcher");
      thread.setDaemon(true);
      return thread;
    });

    scheduler.scheduleAtFixedRate(() -> {
      try {
        fetchSupporters();
      } catch (Exception e) {
        System.err.println("Error fetching supporters: " + e.getMessage());
      }
    }, 0, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
  }

  /**
   * Stops the periodic fetching.
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
   * Checks whether a username is in the supporters list.
   * Comparison is case-insensitive.
   *
   * @param username the username to check
   * @return {@code true} if the username belongs to a supporter
   */
  public static boolean isSupporter(String username) {
    if (username == null || username.isEmpty()) {
      return false;
    }

    String normalized = normalizeCandidate(username);
    if (normalized.isEmpty()) {
      return false;
    }

    if (supporterUsernames.contains(normalized)) {
      return true;
    }

    // Nickname mode may render names as "real/nick" or "nick/real".
    int slashIndex = normalized.indexOf('/');
    if (slashIndex >= 0) {
      String left = normalizeCandidate(normalized.substring(0, slashIndex));
      String right = normalizeCandidate(normalized.substring(slashIndex + 1));
      return (!left.isEmpty() && supporterUsernames.contains(left))
          || (!right.isEmpty() && supporterUsernames.contains(right));
    }

    return false;
  }

  private static String normalizeCandidate(String value) {
    if (value == null) {
      return "";
    }

    return value
        .trim()
        .replace("\u00A0", "")
        .replaceAll("(?i)<\\d+>", "")
        .toLowerCase();
  }

  private static void fetchSupporters() {
    try {
      HttpResponse<String> response = HTTP_CLIENT.send(
          SUPPORTERS_REQUEST, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == HttpURLConnection.HTTP_OK) {
        parseSupporters(response.body());
      }
    } catch (Exception e) {
      // Silently fail to avoid spamming console
    }
  }

  private static void parseSupporters(String jsonResponse) {
    try {
      JsonArray supporters = GSON.fromJson(jsonResponse, JsonArray.class);
      Set<String> newSet = ConcurrentHashMap.newKeySet();

      for (int i = 0; i < supporters.size(); i++) {
        JsonObject supporter = supporters.get(i).getAsJsonObject();
        String username = supporter.get("username").getAsString();
        if (username != null && !username.isEmpty()) {
          newSet.add(username.toLowerCase());
        }
      }

      // Atomic replacement
      supporterUsernames.clear();
      supporterUsernames.addAll(newSet);
    } catch (Exception e) {
      System.err.println("Error parsing supporters: " + e.getMessage());
    }
  }
}
