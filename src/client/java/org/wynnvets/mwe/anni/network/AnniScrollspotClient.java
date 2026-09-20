package org.wynnvets.mwe.anni.network;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.logging.VetsLogger;

/**
 * S5 single-flight client for the {@code anni_scrollspot_set} host write.
 *
 * <p>Mirrors {@link AnniQueryClient}: a FIFO callback queue, the next
 * incoming {@code anni_scrollspot_response} frame resolves the head future.
 * No correlation IDs — same trade-off the rest of the V1 protocol made.
 * Concurrent {@code /wv anni scrollspot} invocations would interleave
 * arbitrarily, but the per-command UX deliberately blocks on the previous
 * call so this never matters in practice.</p>
 *
 * <p>The future resolves to an immutable {@link Ack} record with
 * {@code (ok, detail)}; callers render the detail on failure. A 5-second
 * deadline applies. ⚠️ temp-server's own forward to vets-anni is a flat
 * <b>5 s</b> — {@code set_rsvp} and {@code set_scroll_spot} reuse the anni
 * snapshot poller's client, whose timeout is
 * {@code ANNI_SNAPSHOT_REQUEST_TIMEOUT_SECONDS = 5.0} — not the "3-5 s" this
 * sentence used to claim, and not the 3 s that
 * {@link AnniQueryClient}'s deadline is correctly sized against. So the 5 s
 * here has <b>zero slack</b> over the forward rather than covering
 * round-trip plus jitter; a forward that uses its full budget cannot produce
 * an ack this client is still waiting for. Filed as
 * {@code ack-clients-timeout-has-zero-slack-over-the-server-forward}.</p>
 */
public final class AnniScrollspotClient {

    /** Hard deadline for the round-trip. */
    private static final long ACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

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
        boolean dispatched = V1ApiManager.sendAnniScrollspotSet(x, y, z);
        if (!dispatched) {
            pending.remove(future);
            future.complete(new Ack(false, "not connected"));
            return future;
        }
        future.orTimeout(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(
                        ex -> {
                            pending.remove(future);
                            return null; // already-resolved branch swallowed
                        });
        return future;
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
