package org.wynnvets.mwe.anni.network;

import com.google.gson.JsonObject;

import org.wynnvets.api.V1ApiManager;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * S6 single-flight client for the {@code anni_rsvp} in-game RSVP frame.
 *
 * <p>Clone of {@link AnniScrollspotClient}: FIFO callback queue, the next
 * incoming {@code anni_rsvp_response} frame resolves the head future. The
 * V1 protocol has no per-frame correlation IDs, so rapid back-to-back calls
 * would interleave arbitrarily; the per-command UX deliberately blocks on
 * the previous future so this never matters in practice.</p>
 *
 * <p>The future resolves to an immutable {@link Ack} record with
 * {@code (ok, detail)}; callers render the detail on failure. A 5-second
 * deadline applies — temp-server's own forward to vets-anni uses a 3-5 s
 * timeout, so 5 s here covers the round-trip plus jitter.</p>
 *
 * <p>{@link #lastAttemptedNotice()} / {@link #lastAck()} / {@link
 * #pendingCount()} are exposed for the {@code /wv debug trigger rsvpDump}
 * diagnostic. Race-prone (static volatiles, no per-call correlation) but
 * honest enough for a debug-only view.</p>
 */
public final class AnniRsvpClient {

    /** Hard deadline for the round-trip. */
    private static final long ACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    private static final ConcurrentLinkedDeque<CompletableFuture<Ack>> pending =
            new ConcurrentLinkedDeque<>();

    /** Last attempted notice ("hard"/"soft"/"revoke") — for {@code rsvpDump}. */
    private static volatile String lastAttemptedNotice;
    /** Last completed ack — for {@code rsvpDump}. May be null on first run. */
    private static volatile Ack lastAck;

    private AnniRsvpClient() {
    }

    /** Send an {@code anni_rsvp} frame with the given notice. */
    public static CompletableFuture<Ack> send(String notice) {
        lastAttemptedNotice = notice;
        CompletableFuture<Ack> future = new CompletableFuture<>();
        pending.addLast(future);
        boolean dispatched = V1ApiManager.sendAnniRsvp(notice);
        if (!dispatched) {
            pending.remove(future);
            Ack ack = new Ack(false, "not connected");
            lastAck = ack;
            future.complete(ack);
            return future;
        }
        future.orTimeout(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    pending.remove(future);
                    return null;  // already-resolved branch swallowed
                });
        return future;
    }

    /** Called by {@link AnniWsHandler} for every received
     *  {@code anni_rsvp_response} frame. */
    public static void onResponse(JsonObject json) {
        CompletableFuture<Ack> head = pending.pollFirst();
        if (head == null) {
            VetsLogger.debug("anni_rsvp_response with empty queue: {}", json);
            return;
        }
        String status = json.has("status") && !json.get("status").isJsonNull()
                ? json.get("status").getAsString() : "error";
        String detail = json.has("detail") && !json.get("detail").isJsonNull()
                ? json.get("detail").getAsString() : null;
        Ack ack = new Ack("ok".equals(status), detail);
        lastAck = ack;
        head.complete(ack);
    }

    /** Diagnostic accessor — last notice string passed to {@link #send}. */
    public static String lastAttemptedNotice() {
        return lastAttemptedNotice;
    }

    /** Diagnostic accessor — last completed ack. */
    public static Ack lastAck() {
        return lastAck;
    }

    /** Diagnostic accessor — currently-in-flight queue depth. */
    public static int pendingCount() {
        return pending.size();
    }

    /** Server ack — {@code ok} flag + optional human-readable {@code detail}
     *  surfaced from vets-anni on failure (e.g. "RSVP is closed (within 90
     *  min of anni)"). */
    public record Ack(boolean ok, String detail) {
    }

    /** Debug entry — dump current auth state, in-flight queue depth, last
     *  attempt + last ack, and the snapshot's current rsvp block. Mirrors
     *  the {@code bossBarsDump} / {@code nametagsDump} / {@code zoneLinesDump}
     *  / {@code ghostsPromptDump} family. */
    public static void debugDump() {
        VetsLogger.info("--- rsvpDump ---");
        VetsLogger.info("authenticated_this_session={} pending={} last_attempt={} last_ack={}",
                GuildStateManager.isAuthenticatedThisSession(),
                pending.size(),
                lastAttemptedNotice,
                lastAck);
        AnniSnapshot snap = AnniSnapshotCache.latest();
        if (snap == null) {
            VetsLogger.info("snapshot=null");
            return;
        }
        AnniSnapshot.Rsvp r = snap.rsvp();
        if (r == null) {
            VetsLogger.info("snapshot.rsvp=null (no active RSVP)");
        } else {
            VetsLogger.info("snapshot.rsvp.notice={} updated_at={} revoked={}",
                    r.notice(), r.updatedAt(), r.revoked());
        }
    }
}
