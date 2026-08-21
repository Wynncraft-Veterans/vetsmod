package org.wynnvets.fetcher.polling;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * Periodically fetches the stale-Wynncraft-name → UUID alias map from the
 * VetsMod server and caches it locally.
 *
 * <p>The server's alias learner correlates client-submitted tab-list
 * snapshots against the v3 guild API's per-server online list to pair
 * stale usernames (the ones Wynncraft's server-side tab list still shows
 * after a player renamed their Mojang account) with their true UUIDs.
 * The result is exposed at {@code GET /v1/outbound/aliases}.</p>
 *
 * <p>Consumed by {@link org.wynnvets.fetcher.ondemand.OnlineMemberService OnlineMemberService} to
 * resolve tab-list entries that don't match any current Wynncraft API username — covering the
 * ambiguous-per-server case that local 1:1 correlation can't handle.</p>
 */
public final class WynnAliasCache {
    private static final int REFRESH_INTERVAL_MINUTES = 5;

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private static final HttpRequest ALIASES_REQUEST =
            HttpRequest.newBuilder()
                    .uri(VetsApi.ALIASES)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

    /** Lowercase stale username → UUID (thread-safe snapshot). */
    private static volatile Map<String, String> aliasByName = Map.of();

    private static ScheduledExecutorService scheduler;
    private static boolean isRunning = false;

    private WynnAliasCache() {}

    public static void start() {
        if (isRunning) {
            return;
        }
        isRunning = true;
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "VetsMod-WynnAliasCache");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        fetchAliases();
                    } catch (Exception e) {
                        VetsLogger.warn("Error fetching wynn aliases: {}", e.getMessage());
                    }
                },
                0,
                REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES);
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
     * Returns the UUID for a stale Wynncraft tab-list username, or
     * {@code null} if no alias has been learned for that name.
     *
     * @param staleName the username as shown in the Wynncraft tab list
     */
    public static String getUuid(String staleName) {
        if (staleName == null || staleName.isEmpty()) {
            return null;
        }
        return aliasByName.get(staleName.toLowerCase(Locale.ROOT));
    }

    private static void fetchAliases() {
        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(ALIASES_REQUEST, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                parseAliases(response.body());
            } else {
                VetsLogger.debug("Aliases fetch failed: {}", response.statusCode());
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to fetch aliases: {}", e.getMessage());
        }
    }

    private static void parseAliases(String jsonResponse) {
        try {
            JsonObject obj = Json.GSON.fromJson(jsonResponse, JsonObject.class);
            if (obj == null) {
                return;
            }
            Map<String, String> newMap = new ConcurrentHashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    String uuid = entry.getValue().getAsString();
                    if (uuid != null && !uuid.isEmpty()) {
                        newMap.put(entry.getKey().toLowerCase(Locale.ROOT), uuid);
                    }
                }
            }
            aliasByName = Map.copyOf(newMap);
            VetsLogger.debug("Wynn aliases loaded: {} entries", aliasByName.size());
        } catch (Exception e) {
            VetsLogger.debug("Error parsing aliases: {}", e.getMessage());
        }
    }
}
