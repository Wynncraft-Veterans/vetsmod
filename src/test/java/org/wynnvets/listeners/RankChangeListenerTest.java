package org.wynnvets.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
