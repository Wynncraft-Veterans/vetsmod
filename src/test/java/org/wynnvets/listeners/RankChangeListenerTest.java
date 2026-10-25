package org.wynnvets.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the package-private {@link RankChangeListener#classify(String, String)}
 * rules.
 *
 * <p>NOTE: {@code classify} is pure — it touches no Minecraft or Wynntils API. The
 * surrounding {@link RankChangeListener} class does import Wynntils / event-bus
 * types, but those are only resolved by the JVM, never executed, because
 * {@code classify} has no static initializer dependencies on them. If a future
 * contributor adds a class-level static initializer that loads MC/Wynntils
 * classes, this test will start failing at class-init time — the fix is to
 * extract {@code classify} into its own pure utility class.</p>
 */
class RankChangeListenerTest {

    @AfterEach
    void resetDedupState() {
        RankChangeListener.clearForTesting();
    }

    @Test
    void recruitToRecruitIsKick() {
        assertEquals("kick", RankChangeListener.classify("Recruit", "Recruit"));
    }

    @Test
    void recruiterToRecruitIsBan() {
        // Per the source comment: every non-Recruit→Recruit demotion is treated
        // as a ban request, even the routine Recruiter→Recruit case.
        assertEquals("ban", RankChangeListener.classify("Recruiter", "Recruit"));
    }

    @Test
    void captainToRecruitIsBan() {
        assertEquals("ban", RankChangeListener.classify("Captain", "Recruit"));
    }

    @Test
    void captainToCaptainIsMote() {
        // Same-rank non-Recruit self-loop is a "mote" signal.
        assertEquals("mote", RankChangeListener.classify("Captain", "Captain"));
    }

    @Test
    void recruitToRecruiterReturnsNull() {
        // Real promotion away from Recruit matches none of the rules.
        assertNull(RankChangeListener.classify("Recruit", "Recruiter"));
    }

    // ----- Dedup helpers (wasRecent / recordRecent / prune) -----

    @Test
    void wasRecent_neverSeen_returnsFalse() {
        assertFalse(RankChangeListener.wasRecent("captain", "alice", "Captain", "Captain"));
    }

    @Test
    void recordRecent_thenWasRecent_returnsTrue() {
        RankChangeListener.recordRecent("captain", "alice", "Captain", "Captain");
        assertTrue(RankChangeListener.wasRecent("captain", "alice", "Captain", "Captain"));
    }

    @Test
    void wasRecent_distinguishesByActor() {
        // The 4-tuple fingerprint is symmetric across the four fields, so we
        // only need to demonstrate the discrimination with one of them.
        RankChangeListener.recordRecent("captain", "alice", "Captain", "Captain");
        assertFalse(RankChangeListener.wasRecent("OTHER", "alice", "Captain", "Captain"));
    }

    @Test
    void prune_removesEntriesPastTtl() {
        RankChangeListener.recordRecent("captain", "alice", "Captain", "Captain");
        assertTrue(RankChangeListener.wasRecent("captain", "alice", "Captain", "Captain"));

        // Drive `now` past the TTL boundary without sleeping — prune() takes
        // `now` as a parameter precisely so we can test the expiry rule.
        long ttlMs = TimeUnit.SECONDS.toMillis(30);
        RankChangeListener.prune(System.currentTimeMillis() + ttlMs + 1_000L);

        assertFalse(RankChangeListener.wasRecent("captain", "alice", "Captain", "Captain"));
    }

    @Test
    void recordRecent_atCapacity_evictsOldest() {
        // MAX_DEDUP_ENTRIES = 50. Fill it, then add one more; the first
        // fingerprint should be evicted and the newest retained.
        for (int i = 0; i < 50; i++) {
            RankChangeListener.recordRecent("a" + i, "t", "Captain", "Captain");
        }
        assertTrue(RankChangeListener.wasRecent("a0", "t", "Captain", "Captain"));

        RankChangeListener.recordRecent("a50", "t", "Captain", "Captain");

        assertFalse(RankChangeListener.wasRecent("a0", "t", "Captain", "Captain"));
        assertTrue(RankChangeListener.wasRecent("a50", "t", "Captain", "Captain"));
        // A mid-deque entry that wasn't first-in should still be present.
        assertTrue(RankChangeListener.wasRecent("a25", "t", "Captain", "Captain"));
    }
}
