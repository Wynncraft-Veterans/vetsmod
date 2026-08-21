package org.wynnvets.fetcher.polling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * Periodically fetches confirmed staff ranks and caches them locally.
 *
 * <p>Two-tier cache:
 * <ul>
 *   <li>{@code staffRanksByUsername} — entries populated by the periodic
 *       2-minute poll of {@code /v1/outbound/staff}.</li>
 *   <li>{@code liveStaffRanksByUsername} — entries pushed via
 *       {@code staff_online} outbound frames; preserved across poll
 *       cycles so a push-known staff member is never temporarily
 *       evicted by a stale poll snapshot.</li>
 * </ul>
 *
 * <p>{@link #confirmedRankFor} checks the live map first, then the poll
 * map. Both are keyed by lowercase username and store one of
 * strategist/chief/owner. Captain was retired in the 2026-07 permission
 * restructure; stray captains are silently dropped from
 * {@link #ALLOWED_RANKS} and therefore treated as non-staff Returners
 * client-side.</p>
 */
public final class StaffRanksPoller {
    private static final int REFRESH_INTERVAL_MINUTES = 2;

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    private static final HttpRequest STAFF_REQUEST =
            HttpRequest.newBuilder()
                    .uri(VetsApi.STAFF)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

    private static final Map<String, String> staffRanksByUsername = new ConcurrentHashMap<>();
    // Push-sourced overlay. Survives poll cycles so live presence
    // monotonically wins over the slower 2-minute resync.
    private static final Map<String, String> liveStaffRanksByUsername = new ConcurrentHashMap<>();
    private static final Set<String> ALLOWED_RANKS = Set.of("strategist", "chief", "owner");

    private static ScheduledExecutorService scheduler;
    private static boolean isRunning = false;

    private StaffRanksPoller() {}

    /**
     * Starts periodic staff-rank refresh.
     */
    public static synchronized void start() {
        if (isRunning) {
            return;
        }

        isRunning = true;
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread thread = new Thread(r, "VetsMod-StaffRanksFetcher");
                            thread.setDaemon(true);
                            return thread;
                        });

        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        fetchStaffRanks();
                    } catch (Exception e) {
                        VetsLogger.debug("Failed to refresh staff ranks: {}", e.getMessage());
                    }
                },
                0,
                REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES);
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
     *
     * <p>Live push-sourced state takes priority over the periodic poll
     * cache. This guarantees that a staff member announced via
     * {@code staff_online} is recognised by the recipient's chat
     * rewriters within milliseconds, even if the 2-minute poll has not
     * yet refreshed.</p>
     */
    public static Optional<String> confirmedRankFor(String username) {
        if (username == null || username.isEmpty()) {
            return Optional.empty();
        }

        String key = username.toLowerCase();
        String rank = liveStaffRanksByUsername.get(key);
        if (rank != null) {
            return Optional.of(rank);
        }
        return Optional.ofNullable(staffRanksByUsername.get(key));
    }

    /**
     * Applies a live {@code staff_online} / {@code staff_offline} delta
     * from the v1 outbound WebSocket.
     *
     * <p>When {@code online} is {@code true}, {@code rank} must be one of
     * the allowed ranks; malformed ranks are dropped silently.</p>
     *
     * <p>When {@code online} is {@code false}, the username is removed
     * from both caches eagerly -- waiting for the next poll would leave a
     * up-to-two-minute window in which the rewriter still treats the
     * stale entry as staff.</p>
     */
    public static void applyLiveStaffEvent(String username, String rank, boolean online) {
        if (username == null || username.isEmpty()) {
            return;
        }
        String key = username.toLowerCase();
        if (online) {
            String normalized = normalizeRank(rank);
            if (normalized == null) {
                VetsLogger.debug(
                        "Dropping staff_online with unrecognised rank: {} ({})", username, rank);
                return;
            }
            liveStaffRanksByUsername.put(key, normalized);
        } else {
            liveStaffRanksByUsername.remove(key);
            staffRanksByUsername.remove(key);
        }
    }

    /**
     * Triggers an off-schedule fetch of {@code /v1/outbound/staff}.
     *
     * <p>Called on inbound auth-ack to close the cold-start gap before
     * the first scheduled poll fires (the initial scheduled poll fires
     * on mod init, which may be minutes before the player joins a world
     * and authenticates).</p>
     */
    public static void refreshNow() {
        Thread t = new Thread(StaffRanksPoller::fetchStaffRanks, "VetsMod-StaffRanksRefreshNow");
        t.setDaemon(true);
        t.start();
    }

    private static void fetchStaffRanks() {
        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                parseStaffRanks(response.body());
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to fetch staff ranks: {}", e.getMessage());
        }
    }

    private static void parseStaffRanks(String jsonResponse) {
        try {
            JsonArray staffMembers = Json.GSON.fromJson(jsonResponse, JsonArray.class);
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
