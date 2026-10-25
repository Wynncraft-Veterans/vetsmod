package org.wynnvets.fetcher.polling;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;

/**
 * Background poller + last-good cache for the announced annihilation
 * timestamp ({@code GET /v1/outbound/stamp}).
 *
 * <p>Two write paths feed the same volatile cache:
 * <ul>
 *   <li>Scheduled sweep every {@value #REFRESH_INTERVAL_MINUTES} minutes on
 *       this class's own {@link PollingService} instance, which builds a
 *       dedicated single-thread daemon executor in
 *       {@link PollingService#start()}; the class no longer hand-rolls it.
 *       Initial delay {@code 0}.</li>
 *   <li>{@link #updateFromExternalFetch(long)}: called by
 *       {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher}'s
 *       legacy stamp fetch after each 200 whose body parses.
 *       {@code /wv anni} and the world-join display reach that fetch only
 *       when they fall back to the legacy stamp text. With
 *       {@code vetsAnniEnabled} on and a snapshot cached, they usually
 *       render from the snapshot instead, and then only the scheduled
 *       sweep refreshes this cache.</li>
 * </ul>
 *
 * <p>Cache semantics: a successful parse stores the absolute epoch-seconds
 * value verbatim. When a 200 has an empty body, the scheduled sweep stores
 * {@code 0} (temporary-server, at ffd8c17, sends that body while it holds no stamp); today
 * {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher}'s fetch logs that body as
 * a parse failure and writes nothing
 * ({@code stamp-fetcher-empty-body-warns-and-skips-cache}). A failed fetch or an
 * unparseable body leaves the previous value in place. The only reader today is
 * {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher}'s legacy {@code /wv anni}
 * fallback, which consults this cache only when its own live fetch yields no countdown.
 * Today a zero or past value then gives the "not yet announced" line even when that
 * fetch failed, and a future value gives {@code null} rather than the cached countdown,
 * which {@code /wv anni} prints as the timer being unavailable
 * ({@code stamp-fallback-says-not-announced-on-cold-network-failure}).
 * {@code anni_party_observation} does not read this cache &mdash;
 * {@link org.wynnvets.listeners.PartyRosterListener PartyRosterListener} takes its stamp
 * from the snapshot.</p>
 *
 * <p>The polling interval is intentionally loose
 * ({@value #REFRESH_INTERVAL_MINUTES} minutes): the stamp rarely changes, and
 * {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher} fetches it live whenever it
 * prints the legacy countdown.</p>
 */
public final class AnniStampPoller {
    private static final int REFRESH_INTERVAL_MINUTES = 5;

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private static final HttpRequest STAMP_REQUEST =
            HttpRequest.newBuilder()
                    .uri(VetsApi.STAMP)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

    /**
     * Latest cached anni epoch-seconds. {@code 0} = never populated, or
     * the last write was the scheduled sweep's empty-body reset.
     */
    private static volatile long latestStamp = 0L;

    private static final PollingService SERVICE =
            new PollingService(
                    "VetsMod-AnniStampPoller",
                    () -> {
                        try {
                            fetchAndStore();
                        } catch (Exception e) {
                            VetsLogger.warn("Error fetching anni stamp: {}", e.getMessage());
                        }
                    },
                    0,
                    REFRESH_INTERVAL_MINUTES,
                    TimeUnit.MINUTES);

    private AnniStampPoller() {}

    /** Starts the periodic sweep. Idempotent; safe to call repeatedly. */
    public static void start() {
        SERVICE.start();
    }

    /**
     * @return last known anni epoch-seconds, or {@code 0} if never
     *     populated or the last write was the scheduled sweep's
     *     empty-body reset (no announced anni).
     */
    public static long getLatestStamp() {
        return latestStamp;
    }

    /**
     * Writes a value into the cache. Its only caller is
     * {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher}'s legacy stamp fetch,
     * after a 200 whose body parses. Idempotent and concurrency-safe — {@link #latestStamp}
     * is volatile.
     */
    public static void updateFromExternalFetch(long stamp) {
        latestStamp = stamp;
    }

    private static void fetchAndStore() {
        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(STAMP_REQUEST, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                VetsLogger.debug("Anni stamp fetch returned status {}", response.statusCode());
                return;
            }

            String body = response.body() == null ? "" : response.body().trim();
            if (body.isEmpty()) {
                // Empty body = no announced anni; reset the cache so consumers
                // gate accordingly.
                latestStamp = 0L;
                return;
            }

            try {
                long parsed = Long.parseLong(body);
                latestStamp = parsed;
            } catch (NumberFormatException e) {
                VetsLogger.debug("Anni stamp body unparseable: {}", body);
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to fetch anni stamp: {}", e.getMessage());
        }
    }
}
