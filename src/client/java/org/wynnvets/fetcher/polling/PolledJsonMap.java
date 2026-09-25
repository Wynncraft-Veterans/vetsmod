package org.wynnvets.fetcher.polling;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;
import org.wynnvets.util.Json;

/**
 * A flat {@code string -> string} JSON map, fetched from the VetsMod server on a schedule and
 * published as an immutable snapshot.
 *
 * <p>{@code GuildRosterCache} and {@code WynnAliasCache} were this class twice over. Their
 * fetch wrappers, status gate, error policy, {@link ConcurrentHashMap} build buffer,
 * {@link Map#copyOf} publication and volatile swap were structurally identical; the only logic that
 * differed between them was whether keys were case-folded, at ingest and at lookup alike.
 * The two meanings survive as the two constants below, which is what keeps this a
 * parameterisation rather than a flattening.</p>
 *
 * <p><b>The key normalizer is applied at ingest and at lookup, from one field.</b>
 * {@link #WYNN_ALIASES} folds to {@link Locale#ROOT} on both sides, as the alias cache did;
 * {@link #GUILD_ROSTER} folds nowhere, as the roster cache did. Applying it on one side only
 * would break name resolution silently, with no failing fetch and no log line, which is the
 * single most likely way to get this class wrong. {@code PolledJsonMapTest} pins both
 * directions for both instances.</p>
 *
 * <p><b>Cold-cache contract.</b> Before the first successful parse, {@link #snapshot()} returns an
 * empty map and {@link #get(String)} returns {@code null}. The one consumer,
 * {@code OnlineMemberService.merge()}, has no cold-start branch and depends on exactly that. A
 * failed fetch, or a body that does not parse to a JSON object, never changes the published
 * snapshot, empty or not.</p>
 *
 * <p><b>{@link #snapshot()} hands back one stable reference</b>, read from the volatile field
 * once. {@code merge()} iterates it and relies on intra-loop consistency; recomputing the read
 * per access would let one merge see two generations of the roster.</p>
 *
 * <p>The {@code ConcurrentHashMap} in {@link #parse(String)} is a build buffer, not a
 * concurrency mechanism — it is filled single-threaded on the poller thread and handed
 * straight to {@code Map.copyOf}. The volatile swap is what makes the read safe.</p>
 */
public final class PolledJsonMap {

    private static final int REFRESH_INTERVAL_MINUTES = 5;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    /**
     * UUID → current username, from {@code GET /v1/outbound/roster}.
     *
     * <p>Wynncraft's {@code /v3/guild/{name}} endpoint can return stale usernames for guild
     * members. The VetsMod server resolves each member's UUID against the Minecraft Services
     * API and exposes the corrected mapping here. Keys are UUIDs and are stored exactly as the
     * server spells them — no normalization, as the roster cache did.
     * {@code OnlineMemberService.merge()} relies on that spelling matching
     * {@code UUID.toString()}, which per temporary-server it does today; filed as
     * {@code online-member-service-roster-overlay-assumes-uuid-spelling}.</p>
     */
    public static final PolledJsonMap GUILD_ROSTER =
            new PolledJsonMap(
                    VetsApi.ROSTER,
                    UnaryOperator.identity(),
                    "VetsMod-GuildRosterCache",
                    "guild roster",
                    "members",
                    REFRESH_INTERVAL_MINUTES,
                    REQUEST_TIMEOUT_SECONDS);

    /**
     * Stale Wynncraft tab-list username → UUID, from {@code GET /v1/outbound/aliases}.
     *
     * <p>Per temporary-server, its guild-roster poller builds this map from the {@code legacyName}
     * field of the Wynncraft v3 guild payload. That pairs the usernames Wynncraft's tab list may
     * still show after a player renames their Mojang account with their UUIDs. Keys are folded to
     * {@link Locale#ROOT} on both sides.</p>
     */
    public static final PolledJsonMap WYNN_ALIASES =
            new PolledJsonMap(
                    VetsApi.ALIASES,
                    key -> key.toLowerCase(Locale.ROOT),
                    "VetsMod-WynnAliasCache",
                    "wynn aliases",
                    "entries",
                    REFRESH_INTERVAL_MINUTES,
                    REQUEST_TIMEOUT_SECONDS);

    private final HttpRequest request;
    private final UnaryOperator<String> keyNormalizer;
    private final String label;
    private final String itemNoun;
    private final PollingService service;

    private volatile Map<String, String> values = Map.of();

    /**
     * Builds one polled map. Only the two constants above are meant to exist.
     *
     * <p>Package-private rather than private so a test can build a cold instance and observe
     * the pre-first-parse contract, which a JVM-static cannot be returned to once any case has
     * run. A cold-cache case that does not run first ends up testing the {@code @AfterEach} reset,
     * not the initialiser. See {@code PolledJsonMapTest}.</p>
     *
     * @param uri the endpoint to poll
     * @param keyNormalizer applied to every key, at ingest and at lookup alike
     * @param threadName the poller thread's name, used verbatim — these two inherited the
     *     names of the classes they replace, so they now name instances rather than classes
     * @param label what this map is called in log lines, lowercase and mid-sentence
     * @param itemNoun what one entry is called in the "loaded: N x" line
     * @param periodMinutes how often to re-fetch
     * @param timeoutSeconds per-request timeout
     */
    // Package-private for unit tests. See PolledJsonMapTest.
    PolledJsonMap(
            URI uri,
            UnaryOperator<String> keyNormalizer,
            String threadName,
            String label,
            String itemNoun,
            int periodMinutes,
            int timeoutSeconds) {
        this.request =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .GET()
                        .build();
        this.keyNormalizer = keyNormalizer;
        this.label = label;
        this.itemNoun = itemNoun;
        this.service =
                new PollingService(
                        threadName,
                        () -> {
                            try {
                                fetch();
                            } catch (Exception e) {
                                VetsLogger.warn("Error fetching {}: {}", label, e.getMessage());
                            }
                        },
                        0,
                        periodMinutes,
                        TimeUnit.MINUTES);
    }

    /** Starts the periodic fetch. Idempotent. */
    public void start() {
        service.start();
    }

    /**
     * Returns the whole published snapshot.
     *
     * @return an unmodifiable map, empty until the first successful parse. One volatile read,
     *     so the returned reference does not change under an iterating caller.
     */
    public Map<String, String> snapshot() {
        return values;
    }

    /**
     * Looks one key up, normalizing it the same way ingest did.
     *
     * @param key the raw key, in whatever spelling the caller has
     * @return the mapped value, or {@code null} if the key is absent, blank, or the map has
     *     not loaded yet
     */
    public String get(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return values.get(keyNormalizer.apply(key));
    }

    private void fetch() {
        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                parse(response.body());
            } else {
                VetsLogger.debug("{} fetch failed: {}", label, response.statusCode());
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to fetch {}: {}", label, e.getMessage());
        }
    }

    // Package-private for unit tests. See PolledJsonMapTest.
    void parse(String jsonResponse) {
        try {
            JsonObject obj = Json.GSON.fromJson(jsonResponse, JsonObject.class);
            if (obj == null) {
                return;
            }

            Map<String, String> newValues = new ConcurrentHashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    String value = entry.getValue().getAsString();
                    if (value != null && !value.isEmpty()) {
                        newValues.put(keyNormalizer.apply(entry.getKey()), value);
                    }
                }
            }

            values = Map.copyOf(newValues);
            VetsLogger.debug("{} loaded: {} {}", label, values.size(), itemNoun);
        } catch (Exception e) {
            VetsLogger.debug("Error parsing {}: {}", label, e.getMessage());
        }
    }
}
