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
import java.util.concurrent.TimeUnit;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * Periodically fetches the server's list of confirmed staff who are currently online
 * (per temporary-server, {@code /v1/outbound/staff} lists online staff only) and caches it
 * locally.
 *
 * <p>Two-tier cache:
 * <ul>
 *   <li>{@code staffRanksByUsername} — entries populated by each fetch of
 *       {@code /v1/outbound/staff} (the periodic 2-minute poll and {@link #refreshNow()}),
 *       and removed early by a {@code staff_offline} frame. The poll swaps it by
 *       {@code clear()} then {@code putAll()}, which is not atomic
 *       ({@code staff-ranks-poll-swap-not-atomic}).</li>
 *   <li>{@code liveStaffRanksByUsername} — entries pushed via
 *       {@code staff_online} outbound frames; preserved across poll
 *       cycles so a push-known staff member is never temporarily
 *       evicted by a stale poll snapshot.</li>
 * </ul>
 *
 * <p>Today the live tier stays empty: the server pushes those frames only to an
 * authenticated outbound socket, and vetsmod never authenticates that socket
 * ({@code outbound-socket-never-authenticated}). The poll is the only source that
 * works.</p>
 *
 * <p>{@link #confirmedRankFor} checks the live map first, then the poll
 * map. Both are keyed by lowercase username and store one of
 * strategist/chief/owner. Captain was retired in the 2026-07 permission
 * restructure; a stray captain fails the {@link #ALLOWED_RANKS} check and is dropped
 * (silently from a poll, with a debug log line from a push), so client-side it is
 * treated as a non-staff Returner.</p>
 */
public final class StaffRanksPoller {
    private static final int REFRESH_INTERVAL_MINUTES = 2;

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

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

    private static final PollingService SERVICE =
            new PollingService(
                    "VetsMod-StaffRanksFetcher",
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

    private StaffRanksPoller() {}

    /**
     * Starts periodic staff-rank refresh.
     */
    public static void start() {
        SERVICE.start();
    }

    /**
     * Returns a confirmed rank for the given username when available.
     *
     * <p>Live push-sourced state takes priority over the periodic poll
     * cache. This guarantees that a staff member announced via
     * {@code staff_online} is recognised by the recipient's chat
     * rewriters within milliseconds, even if the 2-minute poll has not
     * yet refreshed.</p>
     *
     * <p>That holds once {@code staff_online} frames arrive. Today none does
     * ({@code outbound-socket-never-authenticated}), so only the poll map answers.</p>
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
     * the allowed ranks; an unrecognised rank is dropped with a debug log line.</p>
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
     * <p>Called on each successful inbound auth ack, so the cache need not wait up to
     * two minutes for the next scheduled poll. The first scheduled poll ran at mod
     * init, which may be minutes before the player joins a world and
     * authenticates.</p>
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
