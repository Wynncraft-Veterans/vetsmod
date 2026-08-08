package org.wynnvets.fetcher.polling;

import org.wynnvets.api.VetsApi;
import org.wynnvets.logging.VetsLogger;

import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background poller + last-good cache for the announced annihilation
 * timestamp ({@code GET /v1/outbound/stamp}).
 *
 * <p>Two write paths feed the same volatile cache:
 * <ul>
 *   <li>Scheduled sweep every {@value #REFRESH_INTERVAL_MINUTES} minutes
 *       (this class's own scheduler).</li>
 *   <li>{@link #updateFromExternalFetch(long)} — called by
 *       {@link org.wynnvets.fetcher.ondemand.StampFetcher} on each
 *       successful on-demand fetch (driven by {@code /wv anni} and the
 *       world-join auto-display). So whenever the user is actively
 *       interacting with the anni surfaces, the cache is fresh without
 *       the poll cadence needing to be tight.</li>
 * </ul>
 *
 * <p>Cache semantics: a successful parse stores the absolute epoch-seconds
 * value verbatim ({@code 0} reserved for "never populated / unparseable /
 * empty body"). A failed fetch leaves the previous value in place — the
 * /wv anni and anni_party_observation callsites both already tolerate a
 * stale stamp the same way they tolerate a zero one (no UI, no send).</p>
 *
 * <p>The polling interval is intentionally loose ({@value #REFRESH_INTERVAL_MINUTES} minutes). The
 * stamp rarely changes; consumers that care about edge accuracy ride the
 * {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher} on-demand cache-warming path.</p>
 */
public final class AnniStampPoller {
  private static final int REFRESH_INTERVAL_MINUTES = 5;

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private static final HttpRequest STAMP_REQUEST = HttpRequest.newBuilder()
      .uri(VetsApi.STAMP)
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build();

  /** Latest cached anni epoch-seconds. {@code 0} = never populated / empty / unparseable. */
  private static volatile long latestStamp = 0L;

  private static ScheduledExecutorService scheduler;
  private static boolean isRunning = false;

  private AnniStampPoller() {
  }

  /** Starts the periodic sweep. Idempotent; safe to call repeatedly. */
  public static void start() {
    if (isRunning) {
      return;
    }

    isRunning = true;
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "VetsMod-AnniStampPoller");
      thread.setDaemon(true);
      return thread;
    });

    scheduler.scheduleAtFixedRate(() -> {
      try {
        fetchAndStore();
      } catch (Exception e) {
        VetsLogger.warn("Error fetching anni stamp: {}", e.getMessage());
      }
    }, 0, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
  }

  /** Stops the periodic sweep, draining the scheduler. */
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

  /** @return last known anni epoch-seconds, or {@code 0} if never populated. */
  public static long getLatestStamp() {
    return latestStamp;
  }

  /**
   * Writes a value into the cache from an external caller (typically
   * {@link org.wynnvets.fetcher.ondemand.StampFetcher} after a successful
   * on-demand fetch). Idempotent and concurrency-safe — {@link #latestStamp}
   * is volatile.
   */
  public static void updateFromExternalFetch(long stamp) {
    latestStamp = stamp;
  }

  private static void fetchAndStore() {
    try {
      HttpResponse<String> response = HTTP_CLIENT.send(
          STAMP_REQUEST, HttpResponse.BodyHandlers.ofString());

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
