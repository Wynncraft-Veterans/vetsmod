package org.wynnvets.fetcher.polling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically fetches confirmed staff ranks and caches them locally.
 *
 * <p>Data source: {@code /v1/outbound/staff}. Cached values are keyed by
 * lowercase username and store one of: captain/strategist/chief/owner.</p>
 */
public final class StaffRanksPoller {
  private static final int REFRESH_INTERVAL_MINUTES = 2;

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final HttpRequest STAFF_REQUEST = HttpRequest.newBuilder()
      .uri(VetsApi.STAFF)
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();

  private static final Gson GSON = new Gson();
  private static final Map<String, String> staffRanksByUsername = new ConcurrentHashMap<>();
  private static final Set<String> ALLOWED_RANKS = Set.of("captain", "strategist", "chief", "owner");

  private static ScheduledExecutorService scheduler;
  private static boolean isRunning = false;

  private StaffRanksPoller() {
  }

  /**
   * Starts periodic staff-rank refresh.
   */
  public static synchronized void start() {
    if (isRunning) {
      return;
    }

    isRunning = true;
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "VetsMod-StaffRanksFetcher");
      thread.setDaemon(true);
      return thread;
    });

    scheduler.scheduleAtFixedRate(() -> {
      try {
        fetchStaffRanks();
      } catch (Exception e) {
        VetsLogger.debug("Failed to refresh staff ranks: {}", e.getMessage());
      }
    }, 0, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
  }

  /**
   * Stops periodic staff-rank refresh.
   */
  public static synchronized void stop() {
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
   * Returns a confirmed rank for the given username when available.
   */
  public static Optional<String> confirmedRankFor(String username) {
    if (username == null || username.isEmpty()) {
      return Optional.empty();
    }

    String rank = staffRanksByUsername.get(username.toLowerCase());
    return Optional.ofNullable(rank);
  }

  private static void fetchStaffRanks() {
    try {
      HttpResponse<String> response = HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == HttpURLConnection.HTTP_OK) {
        parseStaffRanks(response.body());
      }
    } catch (Exception e) {
      VetsLogger.debug("Failed to fetch staff ranks: {}", e.getMessage());
    }
  }

  private static void parseStaffRanks(String jsonResponse) {
    try {
      JsonArray staffMembers = GSON.fromJson(jsonResponse, JsonArray.class);
      Map<String, String> newRanks = new ConcurrentHashMap<>();

      for (JsonElement element : staffMembers) {
        if (!element.isJsonObject()) {
          continue;
        }

        JsonObject staffMember = element.getAsJsonObject();
        if (!staffMember.has("username") || !staffMember.has("rank")) {
          continue;
        }

        String username = staffMember.get("username").getAsString();
        String rank = normalizeRank(staffMember.get("rank").getAsString());

        if (username == null || username.isEmpty() || rank == null) {
          continue;
        }

        newRanks.put(username.toLowerCase(), rank);
      }

      staffRanksByUsername.clear();
      staffRanksByUsername.putAll(newRanks);
    } catch (Exception e) {
      VetsLogger.debug("Failed to parse staff ranks response: {}", e.getMessage());
    }
  }

  private static String normalizeRank(String rank) {
    if (rank == null) {
      return null;
    }

    String normalized = rank.trim().toLowerCase();
    return ALLOWED_RANKS.contains(normalized) ? normalized : null;
  }
}
