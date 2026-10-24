package org.wynnvets.mwe.anni.network;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

/**
 * S6 single-flight client for the {@code anni_rsvp} in-game RSVP frame.
 *
 * <p>Clone of {@link AnniScrollspotClient}: FIFO callback queue, the next
 * incoming {@code anni_rsvp_response} frame resolves the head future. The
 * V1 protocol has no per-frame correlation IDs, and temporary-server answers in
 * send order, so the FIFO mis-pairs only on a lost or late reply or when two concurrent
 * calls send in the opposite order to their enqueues. Nothing waits for the previous
 * call: {@code /wv anni rsvp} sends at once, so two RSVPs sent close together carry
 * that risk.</p>
 *
 * <p>The future resolves to an immutable {@link Ack} record with
 * {@code (ok, detail, result)}, or to {@code null} at the deadline; callers render
 * the detail on failure and use {@code result} to tell a revoke that withdrew an RSVP from
 * one that found none. The deadline is 8 s, the same as
 * {@link AnniQueryClient}'s. temp-server forwards {@code set_rsvp} and
 * {@code set_scroll_spot} to vets-anni on the anni snapshot poller's httpx
 * client, whose {@code ANNI_SNAPSHOT_REQUEST_TIMEOUT_SECONDS = 5.0} bounds
 * each phase of the request (connect, read, write, pool), not the forward as
 * a whole. 8 s leaves about 3 s for the round trip and jitter over a
 * forward that finishes within one such budget: it covers the usual case,
 * but it is not a hard bound on what the server can take.</p>
 */
public final class AnniRsvpClient {

    /** Hard deadline for the round-trip. */
    private static final long ACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(8);

    private static final ConcurrentLinkedDeque<CompletableFuture<Ack>> pending =
            new ConcurrentLinkedDeque<>();

    /**
     * Last attempted notice — for {@code rsvpDump}.
     *
     * <p>⚠️ This is the <b>outbound command</b> vocabulary — {@code "hard"} /
     * {@code "soft"} / {@code "revoke"} — and it is not the inbound notice
     * vocabulary a snapshot carries. Three sets are in play, which is why this
     * keeps being got wrong:</p>
     * <ul>
     *   <li>what this client <em>sends</em>: {@code hard}, {@code soft},
     *       {@code revoke};</li>
     *   <li>vets-anni's {@code AttendanceNotice} enum, four values —
     *       {@code attend_early}, {@code rsvp_hard}, {@code rsvp_soft},
     *       {@code attend_late} — which a snapshot's
     *       {@code attendance.notice_effective} carries raw;</li>
     *   <li>a snapshot's {@code rsvp.notice}, which the server normalises to a
     *       bare {@code hard} / {@code soft}.</li>
     * </ul>
     *
     * <p>So the outbound set overlaps the <em>normalised</em> spelling on two
     * words and the raw enum on none, and {@code revoke} is a verb this client
     * sends, never a state any snapshot reports. ⚠️ The enum is owned by
     * {@code ../vets-anni/app/constants.py} and cannot be checked from this
     * repo; do not re-derive it from a client switch's arm count, which is how
     * four earlier audits got a key space that does not exist.</p>
     */
    private static volatile String lastAttemptedNotice;

    /** Last completed ack — for {@code rsvpDump}. May be null on first run. */
    private static volatile Ack lastAck;

    private AnniRsvpClient() {}

    /** Send an {@code anni_rsvp} frame with the given notice. */
    public static CompletableFuture<Ack> send(String notice) {
        lastAttemptedNotice = notice;
        CompletableFuture<Ack> future = new CompletableFuture<>();
        pending.addLast(future);
        boolean dispatched = AnniWsSender.sendAnniRsvp(notice);
        if (!dispatched) {
            pending.remove(future);
            Ack ack = new Ack(false, "not connected");
            lastAck = ack;
            future.complete(ack);
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
     *  {@code anni_rsvp_response} frame. */
    public static void onResponse(JsonObject json) {
        CompletableFuture<Ack> head = pending.pollFirst();
        if (head == null) {
            VetsLogger.debug("anni_rsvp_response with empty queue: {}", json);
            return;
        }
        Ack ack = parseAck(json);
        lastAck = ack;
        head.complete(ack);
    }

    /**
     * Reads an {@code anni_rsvp_response} frame: {@code status} ({@code ok} or anything else,
     * a missing one counting as an error), the optional {@code detail} and the optional string
     * {@code result}. Each optional field reads as {@code null} when absent, JSON null or not
     * a string, which is what an older server sends for {@code result}.
     */
    // Package-private for unit tests. See AnniRsvpClientTest.
    static Ack parseAck(JsonObject json) {
        String status = optionalString(json, "status");
        return new Ack(
                "ok".equals(status),
                optionalString(json, "detail"),
                optionalString(json, "result"));
    }

    private static String optionalString(JsonObject json, String key) {
        return json.has(key)
                        && json.get(key).isJsonPrimitive()
                        && json.get(key).getAsJsonPrimitive().isString()
                ? json.get(key).getAsString()
                : null;
    }

    /**
     * Server ack: the {@code ok} flag, an optional human-readable {@code detail} surfaced from
     * vets-anni on failure (e.g. "RSVP is closed (within 90 min of anni)"), and an optional
     * {@code result} on success: {@code "recorded"}, {@code "withdrawn"} or
     * {@code "nothing_to_withdraw"} (a revoke with no active RSVP). {@code result} is
     * {@code null} from a server that predates the field.
     */
    public record Ack(boolean ok, String detail, String result) {
        /** An ack with no {@code result}, as an older server or a local failure gives. */
        public Ack(boolean ok, String detail) {
            this(ok, detail, null);
        }
    }

    /** Debug entry — dump current auth state, in-flight queue depth, last
     *  attempt + last ack, and the snapshot's current rsvp block. Mirrors
     *  the {@code bossBarsDump} / {@code nametagsDump} / {@code zoneLinesDump}
     *  / {@code ghostsPromptDump} family. */
    public static void debugDump() {
        VetsLogger.info("--- rsvpDump ---");
        VetsLogger.info(
                "authenticated_this_session={} pending={} last_attempt={} last_ack={}",
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
            VetsLogger.info(
                    "snapshot.rsvp.notice={} updated_at={} revoked={}",
                    r.notice(),
                    r.updatedAt(),
                    r.revoked());
        }
    }
}
