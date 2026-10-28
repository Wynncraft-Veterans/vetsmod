package org.wynnvets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * one removal, that the legacy eldest-first order is send order, which entries a lost socket
 * fails, and each frame type's kind.
 */
class PendingAcksTest {

    private final PendingAcks pending = new PendingAcks();

    private static Consumer<JsonObject> noop() {
        return ack -> {};
    }

    /** Registers a no-op callback sent on socket {@code generation}; returns its id. */
    private String sentOn(long generation) {
        return pending.register(noop(), generation, PendingAcks.Kind.READ);
    }

    private String reg() {
        return sentOn(1);
    }

    private static List<String> reqs(List<PendingAcks.Entry> entries) {
        return entries.stream().map(PendingAcks.Entry::req).toList();
    }

    @Test
    void aRequestIdIsEightAlphanumericCharactersAndIdsDiffer() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String req = reg();
            assertTrue(req.matches("[A-Za-z0-9]{8}"), req);
            assertTrue(seen.add(req), "a repeated id: " + req);
        }
        assertEquals(1000, pending.size());
    }

    @Test
    void completeHandsAnEntryOutExactlyOnce() {
        Consumer<JsonObject> callback = noop();
        String req = pending.register(callback, 3, PendingAcks.Kind.COMMIT);
        PendingAcks.Entry entry = pending.complete(req);
        assertSame(callback, entry.callback());
        assertEquals(req, entry.req());
        assertEquals(3, entry.generation());
        assertEquals(PendingAcks.Kind.COMMIT, entry.kind());
        assertNull(pending.complete(req), "a second removal finds nothing");
        assertTrue(pending.isEmpty());
    }

    @Test
    void anUnknownIdCompletesNothing() {
        reg();
        assertNull(pending.complete("zzzzzzzz"));
        assertEquals(1, pending.size());
    }

    @Test
    void theLegacyPairingFollowsSendOrderAndSkipsWhatWasCompleted() {
        String first = reg();
        String second = reg();
        String third = reg();
        pending.complete(second);
        assertEquals(first, pending.pollEldestStaff().req());
        assertEquals(third, pending.pollEldestStaff().req());
        assertNull(pending.pollEldestStaff());
    }

    @Test
    void anEntryTakenByTheLegacyPairingCannotBeCompleted() {
        String req = reg();
        pending.pollEldestStaff();
        assertNull(pending.complete(req));
    }

    @Test
    void drainAllEmptiesInSendOrder() {
        String first = reg();
        String second = reg();
        assertEquals(List.of(first, second), reqs(pending.drainAll()));
        assertTrue(pending.isEmpty());
        assertNull(pending.complete(first));
    }

    // ----- a lost socket -----

    @Test
    void aLostSocketFailsItsOwnAndOlderEntriesOnly() {
        String older = sentOn(1);
        String lost = sentOn(2);
        String newer = sentOn(3);
        assertEquals(List.of(older, lost), reqs(pending.failGeneration(2)));
        assertEquals(1, pending.size());
        assertEquals(newer, pending.complete(newer).req(), "a later socket's entry survives");
    }

    @Test
    void anEntryFailedByALostSocketCannotBeCompleted() {
        String req = sentOn(1);
        pending.failGeneration(1);
        assertNull(pending.complete(req), "its late ack finds nothing");
        assertEquals(List.of(), pending.failGeneration(1), "and a second loss fails nothing");
    }

    // ----- kinds -----

    @Test
    void readsWaitFifteenSecondsAndCommitsFortyFive() {
        assertEquals(PendingAcks.Kind.READ, PendingAcks.kindFor("caution_check"));
        assertEquals(PendingAcks.Kind.READ, PendingAcks.kindFor("check_membership"));
        assertEquals(PendingAcks.Kind.COMMIT, PendingAcks.kindFor("caution_add"));
        assertEquals(PendingAcks.Kind.COMMIT, PendingAcks.kindFor("warn_add"));
        assertEquals(PendingAcks.Kind.COMMIT, PendingAcks.kindFor("eject_add"));
        assertEquals(PendingAcks.Kind.COMMIT, PendingAcks.kindFor("laurels_set"));
        assertEquals(15_000L, PendingAcks.Kind.READ.timeoutMs);
        assertEquals(45_000L, PendingAcks.Kind.COMMIT.timeoutMs);
        assertEquals(15_000L, PendingAcks.Kind.RELAY.timeoutMs);
    }

    @Test
    void anEntrySentInEchoModeExpiresWhateverTheFlagsSayLater() {
        String echoed = pending.register(noop(), 1, PendingAcks.Kind.READ, true);
        String legacy = reg();
        assertEquals(echoed, pending.expire(echoed, false).req());
        assertNull(pending.expire(legacy, false), "without ids a late ack would mispair");
        assertEquals(1, pending.size());
        assertEquals(legacy, pending.expire(legacy, true).req(), "once the server echoes it may");
        assertNull(pending.expire(legacy, true), "and only once");
    }

    @Test
    void theLegacyPairingSkipsRelays() {
        String relay = pending.register(noop(), 1, PendingAcks.Kind.RELAY);
        assertFalse(pending.hasStaff());
        assertNull(pending.pollEldestStaff(), "a relay is never answered eldest-first");
        String staff = reg();
        assertTrue(pending.hasStaff());
        assertEquals(staff, pending.pollEldestStaff().req());
        assertEquals(relay, pending.complete(relay).req(), "the relay is still waiting by id");
    }
}
