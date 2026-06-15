package org.wynnvets.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.wynnvets.listeners.PartyRosterListener.Snapshot;

/**
 * Tests for {@link PartyRosterListener#shouldSend} — the pure privacy-gating
 * predicate that decides whether a {@code party_status} frame goes on the
 * wire.
 *
 * <p>The predicate has no statics or I/O; all inputs are passed in. Surrounding
 * {@link PartyRosterListener} class does import Wynntils types, but those are
 * only resolved at use sites, not at class-init time (see
 * {@link RankChangeListenerTest} for the analogous note).</p>
 */
class PartyRosterListenerTest {

    private static final long NOW = 1_700_000_000L;  // arbitrary; only deltas matter

    private static Snapshot snap(String leader, String... members) {
        return new Snapshot(leader, List.of(members));
    }

    // ── Window gating ─────────────────────────────────────────────────

    @Test
    void stampZeroSuppressesEvenForGuildTier() {
        // No announced anni -> never send, regardless of cohort.
        assertFalse(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), 0L, NOW, "guild", Set.of()));
    }

    @Test
    void stampThreeHoursInFutureSuppresses() {
        long stamp = NOW + 3 * 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "guild", Set.of()));
    }

    @Test
    void stampThreeHoursInPastSuppresses() {
        long stamp = NOW - 3 * 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "guild", Set.of()));
    }

    @Test
    void stampExactlyAtWindowEdgeAllowed() {
        // 2h exactly is the boundary — should still pass (closed interval).
        long stamp = NOW + 2 * 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "guild", Set.of()));
    }

    @Test
    void stampInTrailingWindowAllowed() {
        // 30 min in the past — fight is still going, board still useful.
        long stamp = NOW - 30 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "guild", Set.of()));
    }

    // ── Self-tier branch ──────────────────────────────────────────────

    @Test
    void guildTierInWindowSendsRegardlessOfCache() {
        long stamp = NOW + 60 * 60;
        // Cold cache + empty party — guild tier still wins.
        assertTrue(PartyRosterListener.shouldSend(
                snap("", new String[0]), stamp, NOW, "guild", null));
    }

    @Test
    void waitlistTierAllowedInWindow() {
        long stamp = NOW + 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "waitlist", Set.of()));
    }

    @Test
    void honouraryTierAllowedInWindow() {
        long stamp = NOW + 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "honourary", Set.of()));
    }

    @Test
    void otherTierWithoutCacheSuppresses() {
        // Cold /list cache -> wrapper would refresh; predicate says no.
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "other", null));
    }

    @Test
    void unauthenticatedEmptyTierTreatedLikeOther() {
        // Cold-start: tier is "" (per UnlockManager). Expected: walk-in path.
        long stamp = NOW + 60 * 60;
        Set<String> connected = Set.of("vetsuser");
        assertTrue(PartyRosterListener.shouldSend(
                snap("VetsUser", "M1"), stamp, NOW, "", connected));
        assertFalse(PartyRosterListener.shouldSend(
                snap("Random", "M1"), stamp, NOW, "", connected));
    }

    @Test
    void nullTierTreatedLikeOther() {
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, null, Set.of()));
    }

    // ── Walk-in branch (vets-tier member in party) ────────────────────

    @Test
    void walkInWithVetsTierLeaderSends() {
        long stamp = NOW + 60 * 60;
        Set<String> connected = Set.of("organiser");
        assertTrue(PartyRosterListener.shouldSend(
                snap("Organiser", "M1", "M2"), stamp, NOW, "other", connected));
    }

    @Test
    void walkInWithVetsTierMemberSends() {
        long stamp = NOW + 60 * 60;
        Set<String> connected = Set.of("hostmember");
        assertTrue(PartyRosterListener.shouldSend(
                snap("RandomLead", "HostMember", "M2"),
                stamp, NOW, "other", connected));
    }

    @Test
    void walkInWithoutAnyVetsTierMemberSuppresses() {
        long stamp = NOW + 60 * 60;
        Set<String> connected = Set.of("someoneelse");
        assertFalse(PartyRosterListener.shouldSend(
                snap("RandomLead", "M1", "M2"), stamp, NOW, "other", connected));
    }

    @Test
    void emptyConnectedSetSuppressesForOtherTier() {
        long stamp = NOW + 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                snap("Lead", "M1"), stamp, NOW, "other", Set.of()));
    }

    // ── Name normalisation ────────────────────────────────────────────

    @Test
    void leaderMatchIsCaseInsensitive() {
        long stamp = NOW + 60 * 60;
        Set<String> connected = Set.of("wenweia");  // cache is lowercase
        assertTrue(PartyRosterListener.shouldSend(
                snap("Wenweia", "M1"), stamp, NOW, "other", connected));
    }

    @Test
    void memberMatchIsCaseInsensitive() {
        long stamp = NOW + 60 * 60;
        Set<String> connected = Set.of("wenweia");
        assertTrue(PartyRosterListener.shouldSend(
                snap("RandomLead", "WENWEIA"), stamp, NOW, "other", connected));
    }

    @Test
    void emptyMemberNamesDoNotFalseMatch() {
        long stamp = NOW + 60 * 60;
        // Defensive: empty-string entries in members list must not match
        // an empty entry in the cache (if any).
        Set<String> connected = Set.of();
        assertFalse(PartyRosterListener.shouldSend(
                snap("", "", ""), stamp, NOW, "other", connected));
    }

    // ── Empty-snapshot (left party) ───────────────────────────────────

    @Test
    void emptySnapshotInWindowVetsTierSends() {
        // The "I just left the party" empty snapshot still goes through to
        // clear server state — as long as we're in window + vets-tier.
        long stamp = NOW + 60 * 60;
        assertTrue(PartyRosterListener.shouldSend(
                Snapshot.EMPTY, stamp, NOW, "guild", Set.of()));
    }

    @Test
    void emptySnapshotOutsideWindowSuppressed() {
        long stamp = NOW + 4 * 60 * 60;
        assertFalse(PartyRosterListener.shouldSend(
                Snapshot.EMPTY, stamp, NOW, "guild", Set.of()));
    }
}
