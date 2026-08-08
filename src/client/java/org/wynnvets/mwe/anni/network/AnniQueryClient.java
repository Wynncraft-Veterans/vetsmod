package org.wynnvets.mwe.anni.network;

import com.google.gson.JsonObject;

import org.wynnvets.api.V1ApiManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * Single-flight client for the {@code anni_query} on-demand pull.
 *
 * <p>{@link AnniWsHandler} routes every {@code anni_query_response} frame
 * to {@link #onResponse(JsonObject)}, which pops the head of a FIFO
 * callback queue — same strict-order pattern as
 * {@link V1ApiManager#staffActionCallbacks}. The protocol has no
 * per-frame correlation ID; rapidly-fired queries without an interleaving
 * wait may receive reordered callbacks, but the alternative (correlation
 * IDs) was judged not worth the wire-protocol churn for the current scale
 * — same call-out the staff-action queue makes.</p>
 *
 * <p>Successful query responses are also bounced through
 * {@link AnniSnapshotCache#update(AnniSnapshot)} so the on-demand pull
 * still drives the listener bus (lets S2's renderer hook fire whether
 * the snapshot arrives via push or pull).</p>
 */
public final class AnniQueryClient {

    /** Hard deadline before {@link #query()} completes with a synthetic
     *  failure response. The server's own fetch deadline is 3 s; allow
     *  generous slack for queue jitter + network. */
    private static final long QUERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(8);

    private static final ConcurrentLinkedDeque<CompletableFuture<AnniSnapshot>>
            pendingQueries = new ConcurrentLinkedDeque<>();

    private AnniQueryClient() {
    }

    /**
     * Issue an {@code anni_query} frame for the authenticated session's
     * own UUID and return a future that completes when the matching
     * {@code anni_query_response} arrives.
     *
     * <p>Completes with {@code null} on:</p>
     * <ul>
     *   <li>The connection being down (frame can't be sent).</li>
     *   <li>A response whose {@code snapshot} is null (server returned
     *       no data — e.g. the player isn't in vets-anni's DB).</li>
     *   <li>An 8-second hard deadline.</li>
     * </ul>
     *
     * <p>Callers MUST handle the null. The "stale" flag from the wire is
     * not surfaced in S1 — vetsmod consumers treat any non-null snapshot
     * as authoritative; S2 will add a stale indicator to the UI when the
     * renderer lands.</p>
     */
    public static CompletableFuture<AnniSnapshot> query() {
        CompletableFuture<AnniSnapshot> future = new CompletableFuture<>();
        pendingQueries.addLast(future);
        boolean dispatched = V1ApiManager.sendAnniQuery();
        if (!dispatched) {
            // The frame didn't go out — pull this future back off the
            // queue (so the next real response doesn't misroute) and
            // resolve it null.
            pendingQueries.remove(future);
            future.complete(null);
            return future;
        }
        // Deadline guard. The CompletableFuture API requires we schedule
        // the timeout ourselves; orTimeout(...) suits exactly.
        future.orTimeout(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    pendingQueries.remove(future);
                    return null;
                });
        return future;
    }

    /** Called by {@link AnniWsHandler} for every received
     *  {@code anni_query_response} frame. */
    public static void onResponse(JsonObject json) {
        CompletableFuture<AnniSnapshot> head = pendingQueries.pollFirst();
        if (head == null) {
            VetsLogger.debug(
                    "anni_query_response with empty queue: error={}",
                    json.has("error") ? json.get("error") : "n/a");
            return;
        }
        AnniSnapshot snapshot = null;
        if (json.has("snapshot") && !json.get("snapshot").isJsonNull()) {
            try {
                snapshot = AnniSnapshot.fromJson(json.getAsJsonObject("snapshot"));
            } catch (Exception e) {
                VetsLogger.warn("anni_query_response parse failed: {}",
                        e.getMessage());
                snapshot = null;
            }
        }
        if (snapshot != null) {
            // Drive the listener bus even on pull (a query that returns a
            // fresher snapshot than the cache should refresh dependent UIs).
            AnniSnapshotCache.update(snapshot);
        }
        head.complete(snapshot);
    }
}
