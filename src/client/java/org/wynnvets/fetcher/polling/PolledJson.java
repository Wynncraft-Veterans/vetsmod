package org.wynnvets.fetcher.polling;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.HttpClients;

/**
 * One GET endpoint polled on a schedule, its body parsed to an immutable {@code T} and published
 * as a snapshot.
 *
 * <p>It owns what every such poller shares: the {@link HttpClient}, the GET, the 200 check, the
 * error policy, the {@link PollingService} and the volatile snapshot. A caller supplies the
 * parser and keeps what is its own: how a body becomes a {@code T}, and what reads the result.
 * {@link PolledJsonMap}, {@link SupportersPoller} and {@link LaurelPoller} are built on it.</p>
 *
 * <p><b>The error policy keeps the last good snapshot.</b> A failed fetch, a non-200 answer, a
 * parser that returns {@code null} and a parser that throws all leave the published snapshot as
 * it was, so a parser rejects a body by returning {@code null} or by throwing. The next tick
 * tries again.</p>
 *
 * <p><b>{@link #snapshot()} is one volatile read</b>, so a caller that holds the result sees one
 * generation throughout. That only holds because {@code T} is immutable: the parser must hand
 * back a value nothing else can mutate, and the volatile write is what makes it safe to read.</p>
 *
 * <p><b>Not every poller in this package fits it.</b> The three below stay as they are:</p>
 *
 * <ul>
 *   <li>{@code StaffRanksPoller} reconciles in place against a live push overlay, using the
 *       fetch's start time, and has a {@code synchronized} fetch and an off-schedule
 *       {@code refreshNow()}. It does not publish a snapshot.</li>
 *   <li>{@code AnniStampPoller} reads a plain-text body, where an empty one means zero, and has a
 *       second write path, {@code updateFromExternalFetch}, which this class has no setter
 *       for.</li>
 *   <li>{@code AnniSnapshotPoller} is not HTTP: it queries over the WebSocket.</li>
 * </ul>
 *
 * <p>Package-private by design rather than as a test seam, so it carries no seam comment. See
 * {@code PolledJsonTest}.</p>
 *
 * @param <T> the immutable snapshot type
 */
final class PolledJson<T> {

    private static final HttpClient HTTP_CLIENT = HttpClients.standard();

    private final HttpRequest request;
    private final String label;
    private final Function<String, T> parser;
    private final PollingService service;

    private volatile T snapshot;

    /**
     * Builds one poller. It starts nothing; {@link #start()} does.
     *
     * @param uri the endpoint to poll
     * @param threadName the poller thread's name, used verbatim
     * @param label what this poller is called in log lines, lowercase and mid-sentence
     * @param periodMinutes how often to re-fetch
     * @param timeoutSeconds per-request timeout
     * @param initial the snapshot until the first successful parse, which may be {@code null}
     * @param parser turns a 200 body into the next snapshot; {@code null} or an exception keeps
     *     the current one
     */
    PolledJson(
            URI uri,
            String threadName,
            String label,
            int periodMinutes,
            int timeoutSeconds,
            T initial,
            Function<String, T> parser) {
        this.request =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .GET()
                        .build();
        this.label = label;
        this.parser = parser;
        this.snapshot = initial;
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
    void start() {
        service.start();
    }

    /**
     * Returns the published snapshot.
     *
     * @return the latest successfully parsed value, or the initial one before the first. One
     *     volatile read.
     */
    T snapshot() {
        return snapshot;
    }

    /**
     * Parses a body and publishes the result.
     *
     * @param body the response body
     */
    void publish(String body) {
        try {
            T parsed = parser.apply(body);
            if (parsed != null) {
                snapshot = parsed;
            }
        } catch (Exception e) {
            VetsLogger.debug("Error parsing {}: {}", label, e.getMessage());
        }
    }

    private void fetch() {
        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                publish(response.body());
            } else {
                VetsLogger.debug("{} fetch failed: {}", label, response.statusCode());
            }
        } catch (Exception e) {
            VetsLogger.debug("Failed to fetch {}: {}", label, e.getMessage());
        }
    }
}
