package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PendingAcks}: the shape of a request id, that each entry is handed out by exactly
 * one removal, and that the legacy eldest-first order is send order.
 */
class PendingAcksTest {

    private final PendingAcks pending = new PendingAcks();

    private static Consumer<JsonObject> noop() {
        return ack -> {};
    }

    @Test
    void aRequestIdIsEightAlphanumericCharactersAndIdsDiffer() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String req = pending.register(noop());
            assertTrue(req.matches("[A-Za-z0-9]{8}"), req);
            assertTrue(seen.add(req), "a repeated id: " + req);
        }
        assertEquals(1000, pending.size());
    }

    @Test
    void completeHandsAnEntryOutExactlyOnce() {
        Consumer<JsonObject> callback = noop();
        String req = pending.register(callback);
        PendingAcks.Entry entry = pending.complete(req);
        assertSame(callback, entry.callback());
        assertEquals(req, entry.req());
        assertNull(pending.complete(req), "a second removal finds nothing");
        assertTrue(pending.isEmpty());
    }

    @Test
    void anUnknownIdCompletesNothing() {
        pending.register(noop());
        assertNull(pending.complete("zzzzzzzz"));
        assertEquals(1, pending.size());
    }

    @Test
    void pollEldestFollowsSendOrderAndSkipsWhatWasCompleted() {
        String first = pending.register(noop());
        String second = pending.register(noop());
        String third = pending.register(noop());
        pending.complete(second);
        assertEquals(first, pending.pollEldest().req());
        assertEquals(third, pending.pollEldest().req());
        assertNull(pending.pollEldest());
    }

    @Test
    void anEntryTakenByPollEldestCannotBeCompleted() {
        String req = pending.register(noop());
        pending.pollEldest();
        assertNull(pending.complete(req));
    }

    @Test
    void drainAllEmptiesInSendOrder() {
        String first = pending.register(noop());
        String second = pending.register(noop());
        List<PendingAcks.Entry> drained = pending.drainAll();
        assertEquals(List.of(first, second), drained.stream().map(PendingAcks.Entry::req).toList());
        assertTrue(pending.isEmpty());
        assertNull(pending.complete(first));
    }
}
