package org.wynnvets.api;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * The callbacks awaiting the reply to a frame sent with a request id ({@code req}), in send
 * order. temporary-server echoes a frame's {@code req} as the last key of its reply (its
 * {@code v1_protocol.md} §1.3), so {@link InboundAckRouter} answers the callback registered under
 * the echoed id. Against a server that does not echo, the router falls back to answering the
 * eldest staff-action entry ({@link #pollEldestStaff()}), the strict-order queue the protocol had
 * before ids.
 *
 * <p>Each entry also records the inbound socket it was sent on (the {@link WsClient#generation()}
 * read at send time), so a lost socket fails exactly its own entries ({@link #failGeneration}),
 * and its {@link Kind}, which sets how long {@link V1ApiManager} waits for the ack.</p>
 *
 * <p>Each callback is answered exactly once: every path that answers one first removes its entry
 * here, under this object's lock, and only the path whose removal returned the entry calls it,
 * outside the lock ({@link V1ApiManager#answer}).</p>
 */
final class PendingAcks {

    /** What a frame asks for, which sets how long its ack is waited for. */
    enum Kind {
        /** {@code caution_check}, {@code check_membership}: one dazebot read. */
        READ(15_000L),
        /** {@code caution_add}, {@code warn_add}, {@code eject_add}: a preflight and a commit,
         *  each a dazebot call, behind any frame the server is still answering on the socket.
         *  {@code laurels_set}: a store write, behind a name lookup. */
        COMMIT(45_000L),
        /** A waitlist, queue or honourary chat relay ({@code GuildChatDispatcher}). Never answered
         *  by the eldest-first pairing, which is for staff-action acks only. */
        RELAY(15_000L);

        final long timeoutMs;

        Kind(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** One callback awaiting its ack. {@code echoed}: registered while the server echoed request
     *  ids, so its deadline holds even if the client later stops believing it does. */
    record Entry(
            String req,
            Consumer<JsonObject> callback,
            long generation,
            Kind kind,
            boolean echoed) {}

    /** The characters of a request id: eight of them give 62^8 ids, so a clash is never seen. */
    private static final String REQ_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    static final int REQ_LENGTH = 8;

    private final LinkedHashMap<String, Entry> byReq = new LinkedHashMap<>();

    /** A fresh random request id: {@value #REQ_LENGTH} characters from {@code [A-Za-z0-9]}. */
    static String newReq() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder req = new StringBuilder(REQ_LENGTH);
        for (int i = 0; i < REQ_LENGTH; i++) {
            req.append(REQ_CHARS.charAt(random.nextInt(REQ_CHARS.length())));
        }
        return req.toString();
    }

    /** The kind of a staff-action frame type: a read for {@code caution_check} and
     *  {@code check_membership}, a commit for the rest. */
    static Kind kindFor(String frameType) {
        return "caution_check".equals(frameType) || "check_membership".equals(frameType)
                ? Kind.READ
                : Kind.COMMIT;
    }

    /** Registers {@code callback} under a fresh request id, which it returns, for a frame sent on
     *  the inbound socket of {@code generation} while the server did not echo ids. */
    synchronized String register(Consumer<JsonObject> callback, long generation, Kind kind) {
        return register(callback, generation, kind, false);
    }

    /** Registers {@code callback} under a fresh request id, which it returns, for a frame sent on
     *  the inbound socket of {@code generation}; {@code echoed} says whether the server echoed
     *  ids when it was sent. */
    synchronized String register(
            Consumer<JsonObject> callback, long generation, Kind kind, boolean echoed) {
        String req;
        do {
            req = newReq();
        } while (byReq.containsKey(req));
        byReq.put(req, new Entry(req, callback, generation, kind, echoed));
        return req;
    }

    /**
     * Removes and returns the entry under {@code req} when its deadline may be enforced: it was
     * registered while the server echoed ids, or {@code echoNow} says it echoes them now. Else, or
     * when another path took it, {@code null}, and the entry stays. Without ids a late ack would
     * be paired with the next caller instead, so an entry sent then is left to its ack.
     */
    synchronized Entry expire(String req, boolean echoNow) {
        Entry entry = byReq.get(req);
        if (entry == null || !(entry.echoed() || echoNow)) {
            return null;
        }
        return byReq.remove(req);
    }

    /** Removes and returns the entry registered under {@code req}, or {@code null} if it was
     *  never registered or another path has already taken it. */
    synchronized Entry complete(String req) {
        return byReq.remove(req);
    }

    /** Removes and returns the eldest entry that is not a {@link Kind#RELAY relay}, or
     *  {@code null} if there is none. Only for a server that does not echo {@code req}. */
    synchronized Entry pollEldestStaff() {
        Iterator<Entry> it = byReq.values().iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.kind() != Kind.RELAY) {
                it.remove();
                return entry;
            }
        }
        return null;
    }

    /** Whether a staff-action entry, one that is not a {@link Kind#RELAY relay}, is waiting. */
    synchronized boolean hasStaff() {
        for (Entry entry : byReq.values()) {
            if (entry.kind() != Kind.RELAY) {
                return true;
            }
        }
        return false;
    }

    /** Removes and returns, eldest first, every entry sent on the socket of {@code lostGeneration}
     *  or an earlier one: every socket up to the lost one is dead, so none of their acks can come.
     *  An entry tagged with an older socket than it was really sent on is failed early, which
     *  costs only a needless "outcome unknown", never a misattributed ack. */
    synchronized List<Entry> failGeneration(long lostGeneration) {
        List<Entry> failed = new ArrayList<>();
        Iterator<Entry> it = byReq.values().iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.generation() <= lostGeneration) {
                failed.add(entry);
                it.remove();
            }
        }
        return failed;
    }

    synchronized boolean isEmpty() {
        return byReq.isEmpty();
    }

    synchronized int size() {
        return byReq.size();
    }

    /** Removes and returns every entry, eldest first. */
    synchronized List<Entry> drainAll() {
        List<Entry> all = new ArrayList<>(byReq.values());
        byReq.clear();
        return all;
    }
}
