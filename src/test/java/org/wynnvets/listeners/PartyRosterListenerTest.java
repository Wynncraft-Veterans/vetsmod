package org.wynnvets.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.wynnvets.listeners.PartyRosterListener.Snapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * Tests for {@link PartyRosterListener#shouldSend} — the pure gate that
 * decides whether an {@code anni_party_observation} frame goes on the wire.
 *
 * <p>S7 rule: send iff (a) the anni stamp is in-window AND (b) the
 * snapshot's {@code organiser_usernames} list contains the captured
 * party's leader or any member name (case-insensitive). The predicate
 * has no statics or I/O; all inputs are passed in.</p>
 */
class PartyRosterListenerTest {

    private static final long NOW = 1_700_000_000L;  // arbitrary; only deltas matter

    private static Snapshot snap(String leader, String... members) {
        return new Snapshot(leader, List.of(members));
    }

    /** Build an {@link AnniSnapshot} with just the {@code organiser_usernames}
     *  field set — that's all {@code shouldSend} reads. */
    private static AnniSnapshot anniWithOrganisers(String... usernames) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String u : usernames) {
            arr.add(u);
        }
        root.add("organiser_usernames", arr);
        return AnniSnapshot.fromJson(root);
    }

    // ── Window gating ─────────────────────────────────────────────────

    @Test
    void stampZeroSuppresses() {
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        assertFalse(PartyRosterListener.shouldSend(
                snap("Organiser", "M1"), anni, 0L, NOW));
    }

    @Test
    void stampThreeHoursInFutureSuppresses() {
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        long stamp = NOW + 3 * 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Organiser", "M1"), anni, stamp, NOW));
    }

    @Test
    void stampThreeHoursInPastSuppresses() {
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        long stamp = NOW - 3 * 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Organiser", "M1"), anni, stamp, NOW));
    }

    @Test
    void stampExactlyAtWindowEdgeAllowed() {
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        long stamp = NOW + 2 * 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("Organiser", "M1"), anni, stamp, NOW));
    }

    @Test
    void stampInTrailingWindowAllowed() {
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        long stamp = NOW - 30 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("Organiser", "M1"), anni, stamp, NOW));
    }

    // ── Snapshot presence ─────────────────────────────────────────────

    @Test
    void nullSnapshotSuppresses() {
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Organiser", "M1"), null, stamp, NOW));
    }

    @Test
    void emptyOrganisersSuppresses() {
        AnniSnapshot anni = anniWithOrganisers();  // empty
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Organiser", "M1"), anni, stamp, NOW));
    }

    // ── Organiser overlap ─────────────────────────────────────────────

    @Test
    void leaderMatchSends() {
        AnniSnapshot anni = anniWithOrganisers("Organiser", "OtherLead");
        long stamp = NOW + 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("Organiser", "M1", "M2"), anni, stamp, NOW));
    }

    @Test
    void memberMatchSends() {
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        long stamp = NOW + 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("RandomLead", "Organiser", "M2"), anni, stamp, NOW));
    }

    @Test
    void noOrganiserInPartySuppresses() {
        AnniSnapshot anni = anniWithOrganisers("Organiser", "OtherLead");
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("RandomLead", "M1", "M2"), anni, stamp, NOW));
    }

    // ── Case insensitivity ────────────────────────────────────────────

    @Test
    void leaderMatchIsCaseInsensitive() {
        AnniSnapshot anni = anniWithOrganisers("ORGANISER");
        long stamp = NOW + 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("organiser", "M1"), anni, stamp, NOW));
    }

    @Test
    void memberMatchIsCaseInsensitive() {
        AnniSnapshot anni = anniWithOrganisers("organiser");
        long stamp = NOW + 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("RandomLead", "ORGANISER"), anni, stamp, NOW));
    }

    // ── Edge cases ────────────────────────────────────────────────────

    @Test
    void emptyNamesDoNotFalseMatchEmptyOrganiser() {
        // Defensive: an empty-string entry on either side must not pair.
        AnniSnapshot anni = anniWithOrganisers("", "Organiser");
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("", "", ""), anni, stamp, NOW));
    }

    @Test
    void emptySnapshotPartyWithOrganisersSuppresses() {
        // Wynncraft has no party right now — nothing to report. The
        // wire-frame for "I left my party" is an empty observation and
        // it has no organiser to anchor on, so we silently no-op.
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                Snapshot.EMPTY, anni, stamp, NOW));
    }

    @Test
    void emptySnapshotOutsideWindowSuppressed() {
        AnniSnapshot anni = anniWithOrganisers("Organiser");
        long stamp = NOW + 4 * 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                Snapshot.EMPTY, anni, stamp, NOW));
    }
}
