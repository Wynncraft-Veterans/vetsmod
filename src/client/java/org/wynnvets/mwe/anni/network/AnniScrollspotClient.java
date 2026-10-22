package org.wynnvets.mwe.anni.network;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import org.wynnvets.logging.VetsLogger;

/**
 * S5 single-flight client for the {@code anni_scrollspot_set} host write.
 *
 * <p>Mirrors {@link AnniQueryClient}: a FIFO callback queue, the next
 * incoming {@code anni_scrollspot_response} frame resolves the head future.
 * No correlation IDs — same trade-off the rest of the V1 protocol made.
 * temporary-server answers in send order, so concurrent
 * {@code /wv debug tree anni scrollspot} invocations mis-pair only on a lost or late reply
 * or when two send in the opposite order to their enqueues. Nothing waits for the previous
 * call: each invocation sends at once, so two sent close together carry that risk.</p>
 *
 * <p>The future resolves to an immutable {@link Ack} record with
 * {@code (ok, detail)}, or to {@code null} at the deadline; callers render
 * the detail on failure. The deadline is 8 s, the same as
 * {@link AnniQueryClient}'s. temp-server forwards {@code set_rsvp} and
 * {@code set_scroll_spot} to vets-anni on the anni snapshot poller's httpx
 * client, whose {@code ANNI_SNAPSHOT_REQUEST_TIMEOUT_SECONDS = 5.0} bounds
 * each phase of the request (connect, read, write, pool), not the forward as
 * a whole. 8 s leaves about 3 s for the round trip and jitter over a
 * forward that finishes within one such budget: it covers the usual case,
 * but it is not a hard bound on what the server can take.</p>
 */
public final class AnniScrollspotClient {

    /** Hard deadline for the round-trip. */
    private static final long ACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(8);

    private static final ConcurrentLinkedDeque<CompletableFuture<Ack>> pending =
            new ConcurrentLinkedDeque<>();

    private AnniScrollspotClient() {}

    /** Set the local user's party's scroll spot. */
    public static CompletableFuture<Ack> set(int x, int y, int z) {
        return dispatch(x, y, z);
    }

    /** Clear the local user's party's scroll spot. */
    public static CompletableFuture<Ack> clear() {
        return dispatch(null, null, null);
    }

    private static CompletableFuture<Ack> dispatch(Integer x, Integer y, Integer z) {
        CompletableFuture<Ack> future = new CompletableFuture<>();
        pending.addLast(future);
        boolean dispatched = AnniWsSender.sendAnniScrollspotSet(x, y, z);
        if (!dispatched) {
            pending.remove(future);
            future.complete(new Ack(false, "not connected"));
            return future;
        }
        // Hand back the exceptionally stage: orTimeout returns this future
        // unchanged, and the stage is what resolves the caller's future with
        // null at the deadline.
        return future.orTimeout(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(
                        ex -> {
                            pending.remove(future);
                            return null;
                        });
    }

    /** Called by {@link AnniWsHandler} for every received
     *  {@code anni_scrollspot_response} frame. */
    public static void onResponse(JsonObject json) {
        CompletableFuture<Ack> head = pending.pollFirst();
        if (head == null) {
            VetsLogger.debug("anni_scrollspot_response with empty queue: {}", json);
            return;
        }
        String status =
                json.has("status") && !json.get("status").isJsonNull()
                        ? json.get("status").getAsString()
                        : "error";
        String detail =
                json.has("detail") && !json.get("detail").isJsonNull()
                        ? json.get("detail").getAsString()
                        : null;
        head.complete(new Ack("ok".equals(status), detail));
    }

    /** Server ack — {@code ok} flag + optional human-readable {@code detail}
     *  surfaced from vets-anni on failure (e.g. "only the party host can set
     *  scroll_spot"). */
    public record Ack(boolean ok, String detail) {}
}
