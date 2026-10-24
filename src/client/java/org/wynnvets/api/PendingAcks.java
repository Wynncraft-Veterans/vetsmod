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
 * eldest entry ({@link #pollEldest()}), the strict-order queue the protocol had before ids.
 *
 * <p>Each callback is answered exactly once: every path that answers one first removes its entry
 * here, under this object's lock, and only the path whose removal returned the entry calls it,
 * outside the lock ({@link V1ApiManager#answer}).</p>
 */
final class PendingAcks {

    /** One callback awaiting its ack. */
    record Entry(String req, Consumer<JsonObject> callback) {}

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

    /** Registers {@code callback} under a fresh request id, which it returns. */
    synchronized String register(Consumer<JsonObject> callback) {
        String req;
        do {
            req = newReq();
        } while (byReq.containsKey(req));
        byReq.put(req, new Entry(req, callback));
        return req;
    }

    /** Removes and returns the entry registered under {@code req}, or {@code null} if it was
     *  never registered or another path has already taken it. */
    synchronized Entry complete(String req) {
        return byReq.remove(req);
    }

    /** Removes and returns the eldest entry, or {@code null} if there is none. Only for a server
     *  that does not echo {@code req}. */
    synchronized Entry pollEldest() {
        Iterator<Entry> it = byReq.values().iterator();
        if (!it.hasNext()) {
            return null;
        }
        Entry eldest = it.next();
        it.remove();
        return eldest;
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
