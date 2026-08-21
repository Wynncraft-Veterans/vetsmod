package org.wynnvets.mwe.anni.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniSnapshots} — the four snapshot field accessors and their
 * null contract.
 *
 * <p>Fixtures come from {@link AnniSnapshot#fromJson(JsonObject)}, which is a
 * working empty-snapshot constructor and already used that way by
 * {@code AggressiveAlertDispatcher.emptySnapshot} and by
 * {@code PartyRosterListenerTest}. No mocking library is involved; the harness
 * has none.</p>
 *
 * <p>The cases that matter are the ones on {@code null} — a null snapshot is a
 * legal cache state that {@code /wv debug tree anni snapshot clear} produces on
 * demand, and one of the two source bodies this class replaced would have thrown
 * on it. Deleting the {@code snapshot == null} guard turns all four of those
 * cases red.</p>
 */
class AnniSnapshotsTest {

    /** Every field this class reads, populated. */
    private static AnniSnapshot populated() {
        JsonObject party = new JsonObject();
        party.addProperty("ordinal", 3);
        party.addProperty("world", "EU5");

        JsonObject board = new JsonObject();
        board.addProperty("role", "TANK");
        board.add("party", party);

        JsonObject rsvp = new JsonObject();
        rsvp.addProperty("notice", "hard");
        rsvp.addProperty("revoked", false);

        JsonObject root = new JsonObject();
        root.add("board", board);
        root.add("rsvp", rsvp);
        return AnniSnapshot.fromJson(root);
    }

    // ── Fully populated ───────────────────────────────────────────────

    @Test
    void allFourReadTheirFieldOffAPopulatedSnapshot() {
        AnniSnapshot snapshot = populated();

        assertEquals("TANK", AnniSnapshots.role(snapshot));
        assertEquals(Integer.valueOf(3), AnniSnapshots.partyOrdinal(snapshot));
        assertEquals("EU5", AnniSnapshots.partyWorld(snapshot));
        assertEquals("hard", AnniSnapshots.rsvpNotice(snapshot));
    }

    // ── Every nested object absent ────────────────────────────────────

    @Test
    void allFourReturnNullOnAnEmptySnapshot() {
        // fromJson({}) leaves board and rsvp null — the shape
        // AggressiveAlertDispatcher.emptySnapshot builds deliberately.
        AnniSnapshot empty = AnniSnapshot.fromJson(new JsonObject());

        assertNull(AnniSnapshots.role(empty));
        assertNull(AnniSnapshots.partyOrdinal(empty));
        assertNull(AnniSnapshots.partyWorld(empty));
        assertNull(AnniSnapshots.rsvpNotice(empty));
    }

    @Test
    void partyAccessorsReturnNullWhenTheBoardHasNoParty() {
        JsonObject board = new JsonObject();
        board.addProperty("role", "HEALER");
        JsonObject root = new JsonObject();
        root.add("board", board);
        AnniSnapshot snapshot = AnniSnapshot.fromJson(root);

        assertEquals("HEALER", AnniSnapshots.role(snapshot), "the board itself is present");
        assertNull(AnniSnapshots.partyOrdinal(snapshot));
        assertNull(AnniSnapshots.partyWorld(snapshot));
    }

    // ── A null snapshot ───────────────────────────────────────────────

    // One method per accessor rather than four assertions in one: the mutation
    // this section exists for is "delete the snapshot == null guard", and a
    // single method would throw on the first accessor and report one failure
    // whether one guard was deleted or all four.
    //
    // AnniSnapshotCache.update(null) is a supported call and
    // AnniDebugCommands.snapshotClear makes it, so latest() can hand any of
    // these a null at any point in a warm session.

    @Test
    void roleReturnsNullOnANullSnapshot() {
        assertNull(AnniSnapshots.role(null));
    }

    @Test
    void partyOrdinalReturnsNullOnANullSnapshot() {
        assertNull(AnniSnapshots.partyOrdinal(null));
    }

    @Test
    void partyWorldReturnsNullOnANullSnapshot() {
        // The one call site that can reach this: FlashTracker.updateWorldMismatch
        // passes AnniSnapshotCache.latest() straight in, every client tick.
        assertNull(AnniSnapshots.partyWorld(null));
    }

    @Test
    void rsvpNoticeReturnsNullOnANullSnapshot() {
        assertNull(AnniSnapshots.rsvpNotice(null));
    }

    // ── The two arms that look redundant and are not ──────────────────

    @Test
    void partyOrdinalReturnsNullForOrdinalZeroRatherThanZero() {
        // Party.ordinal() is a primitive int and collapses an absent JSON key to
        // 0. Without the > 0 re-check every unassigned player reads as a member
        // of party 0, and every test above still passes.
        JsonObject party = new JsonObject();
        party.addProperty("world", "EU5");
        JsonObject board = new JsonObject();
        board.add("party", party);
        JsonObject root = new JsonObject();
        root.add("board", board);
        AnniSnapshot snapshot = AnniSnapshot.fromJson(root);

        assertNull(AnniSnapshots.partyOrdinal(snapshot), "0 means unassigned, not party 0");
        assertEquals("EU5", AnniSnapshots.partyWorld(snapshot), "the party object is present");
    }

    @Test
    void rsvpNoticeReturnsNullWhenTheRsvpIsRevoked() {
        JsonObject rsvp = new JsonObject();
        rsvp.addProperty("notice", "hard");
        rsvp.addProperty("revoked", true);
        JsonObject root = new JsonObject();
        root.add("rsvp", rsvp);
        AnniSnapshot revoked = AnniSnapshot.fromJson(root);

        assertNull(AnniSnapshots.rsvpNotice(revoked), "revoked reads as absent");
        assertEquals(
                "hard",
                AnniSnapshots.rsvpNotice(populated()),
                "the two fixtures differ in exactly the revoked flag");
    }
}
