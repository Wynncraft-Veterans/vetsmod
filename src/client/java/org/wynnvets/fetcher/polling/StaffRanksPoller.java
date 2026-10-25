package org.wynnvets.fetcher.polling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
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
 *       and removed early by a {@code staff_offline} frame. Each fetch adds the response's
 *       entries, then drops the names it lacks, so a name in both the old and the new response
 *       is never briefly absent; fetches run one at a time.</li>
 *   <li>{@code liveStaffRanksByUsername} — entries pushed via {@code staff_online} outbound
 *       frames, which arrive only on an authenticated outbound socket. An entry survives a poll
 *       that started before it was pushed, so a stale snapshot never evicts a staff member who
 *       just came online, but a poll that started after the push and no longer lists the name
 *       drops it: the server's staff list covers everyone it announces, so the name's absence
 *       means a {@code staff_offline} was missed. The overlay is also cleared when the
 *       connection closes ({@link #clearLiveOverlay()}) and when the outbound socket
 *       authenticates ({@link #onOutboundAuthenticated()}), since the server replays no
 *       presence to a new socket.</li>
 * </ul>
 *
 * <p>A {@code staff_offline} may carry a null username (temporary-server's protocol §2.6), so
 * each {@code staff_online}'s uuid is remembered and an offline with no name is resolved by
 * it.</p>
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

    /** A pushed rank and when it was applied ({@link System#nanoTime()}), so a poll can tell an
     *  entry pushed after it started (kept) from one its snapshot should have listed. */
    private record LiveEntry(String rank, long appliedAtNanos) {}

    // Push-sourced overlay, keyed like the poll map. See the class Javadoc for when it drops.
    private static final Map<String, LiveEntry> liveStaffRanksByUsername =
            new ConcurrentHashMap<>();
    // Lowercase uuid -> overlay key, from staff_online frames, for a nameless staff_offline.
    private static final Map<String, String> liveKeysByUuid = new ConcurrentHashMap<>();
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
     * yet refreshed. Without an authenticated outbound socket (no key, or a non-member tier)
     * no push arrives and only the poll map answers.</p>
     */
    public static Optional<String> confirmedRankFor(String username) {
        if (username == null || username.isEmpty()) {
            return Optional.empty();
        }

        String key = username.toLowerCase(Locale.ROOT);
        LiveEntry live = liveStaffRanksByUsername.get(key);
        if (live != null) {
            return Optional.of(live.rank());
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
     * stale entry as staff. A null or empty {@code username} is resolved through the
     * {@code uuid} an earlier {@code staff_online} carried; an online frame needs a
     * username.</p>
     *
     * @param uuid the staff member's uuid from the frame, or {@code null}
     */
    public static void applyLiveStaffEvent(
            String username, String uuid, String rank, boolean online) {
        String uuidKey = uuid == null || uuid.isEmpty() ? null : uuid.toLowerCase(Locale.ROOT);
        if (online) {
            if (username == null || username.isEmpty()) {
                return;
            }
            String normalized = normalizeRank(rank);
            if (normalized == null) {
                VetsLogger.debug(
                        "Dropping staff_online with unrecognised rank: {} ({})", username, rank);
                return;
            }
            String key = username.toLowerCase(Locale.ROOT);
            liveStaffRanksByUsername.put(key, new LiveEntry(normalized, System.nanoTime()));
            if (uuidKey != null) {
                liveKeysByUuid.put(uuidKey, key);
            }
            return;
        }
        String key;
        if (username != null && !username.isEmpty()) {
            key = username.toLowerCase(Locale.ROOT);
        } else {
            key = uuidKey == null ? null : liveKeysByUuid.get(uuidKey);
        }
        if (uuidKey != null) {
            liveKeysByUuid.remove(uuidKey);
        }
        if (key == null) {
            VetsLogger.debug("Dropping staff_offline with no username and an unknown uuid");
            return;
        }
        liveStaffRanksByUsername.remove(key);
        staffRanksByUsername.remove(key);
        liveKeysByUuid.values().remove(key);
    }

    /** Empties the push overlay, leaving the poll map. Called by {@code V1ApiManager.disconnect}:
     *  presence pushed to a closed socket says nothing about the next one. */
    public static void clearLiveOverlay() {
        liveStaffRanksByUsername.clear();
        liveKeysByUuid.clear();
    }

    /**
     * Called when the outbound socket authenticates (its ack, through {@code
     * OutboundAckRouter}): the server pushes {@code staff_online} only for changes from then
     * on and replays nothing, so the overlay is cleared and the poll map refreshed at once to
     * cover staff already online.
     */
    public static void onOutboundAuthenticated() {
        clearLiveOverlay();
        refreshNow();
    }

    /**
     * Triggers an off-schedule fetch of {@code /v1/outbound/staff}.
     *
     * <p>Called on each successful inbound auth ack, so the cache need not wait up to
     * two minutes for the next scheduled poll. The first scheduled poll runs at mod init, before
     * any socket opens. The sockets connect only at a Wynncraft join, which sends a stored key
     * once the connect completes, and each later Wynncraft join opens a new pair and re-sends
     * it, at any point in the poll's cycle.</p>
     */
    public static void refreshNow() {
        Thread t = new Thread(StaffRanksPoller::fetchStaffRanks, "VetsMod-StaffRanksRefreshNow");
        t.setDaemon(true);
        t.start();
    }

    // Synchronized: the scheduled poll and refreshNow's thread both land here, and each fetch's
    // response must be applied whole before the next one's.
    private static synchronized void fetchStaffRanks() {
        try {
            long started = System.nanoTime();
            HttpResponse<String> response =
                    HTTP_CLIENT.send(STAFF_REQUEST, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                parseStaffRanks(response.body(), started);
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to fetch staff ranks: {}", e.getMessage());
        }
    }

    /** {@link #parseStaffRanks(String, Long)} for a response that reconciles no live entry. */
    // Package-private for unit tests. See StaffRanksPollerTest.
    static void parseStaffRanks(String jsonResponse) {
        parseStaffRanks(jsonResponse, null);
    }

    /**
     * Replaces the poll map with the response's names, then drops each live entry applied
     * before {@code fetchStartedNanos} whose name the response lacks: a missed
     * {@code staff_offline}. A {@code null} start reconciles nothing.
     */
    // Package-private for unit tests. See StaffRanksPollerTest.
    static void parseStaffRanks(String jsonResponse, Long fetchStartedNanos) {
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

                newRanks.put(username.toLowerCase(Locale.ROOT), rank);
            }

            // Add first, then drop the rest, so a reader never sees a still-online name missing.
            staffRanksByUsername.putAll(newRanks);
            staffRanksByUsername.keySet().retainAll(newRanks.keySet());
            if (fetchStartedNanos != null) {
                long started = fetchStartedNanos;
                liveStaffRanksByUsername
                        .entrySet()
                        .removeIf(
                                e ->
                                        e.getValue().appliedAtNanos() - started < 0
                                                && !newRanks.containsKey(e.getKey()));
                liveKeysByUuid.values().retainAll(liveStaffRanksByUsername.keySet());
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to parse staff ranks response: {}", e.getMessage());
        }
    }

    private static String normalizeRank(String rank) {
        if (rank == null) {
            return null;
        }

        String normalized = rank.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_RANKS.contains(normalized) ? normalized : null;
    }
}
