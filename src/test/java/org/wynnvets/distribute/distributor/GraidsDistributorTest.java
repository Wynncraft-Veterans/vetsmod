package org.wynnvets.distribute.distributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GraidsDistributor}'s proportional share split.
 *
 * <p>Unlike its two siblings this split is <b>proportional</b>: a participant
 * with twice the guild-raid count gets twice the share, floored, with the
 * leftover handed out as a random {@code +1}. What makes it testable despite
 * the shuffle is that the shuffle only picks <em>who</em> gets a bonus — the
 * queue is emitted in the order of the sorted {@code participants} list
 * (frequency descending, then name case-insensitively), never in bonus-pool
 * order. So the name sequence is deterministic in every case where no share
 * lands on zero, and the tests assert it.</p>
 *
 * <p>{@code countGraidFrequencies} and {@code joinLore} are not covered: both
 * resolve Wynntils {@code GuildLogItem} and {@code StyledText} on their
 * executed path. Freeing those two is a refactor, not a seam, and this test
 * exists partly to make that refactor checkable — no pure helper is extracted
 * here, because extracting one is precisely the change being protected.</p>
 *
 * <p>{@code filterIndex} is a third pure, Wynntils-free method and is
 * <b>uncovered</b>. The Phase 4 target list missed it; the verify pass caught
 * that. Recorded here rather than added, since the phase is closed.</p>
 *
 * <p>NOTE: {@link GraidsDistributor} imports Wynntils, but its static state is
 * a {@code String}, a {@code Pattern} and a {@code Random}, so nothing loads
 * during class-init.</p>
 */
class GraidsDistributorTest {

    /** Insertion-ordered so the fixture reads in a fixed order — the method
     *  sorts regardless, which is part of what is being asserted. */
    private static Map<String, Integer> freq(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    private static List<String> names(Deque<GraidsDistributor.Distribution> queue) {
        List<String> out = new ArrayList<>();
        for (GraidsDistributor.Distribution d : queue) {
            out.add(d.legacyName());
        }
        return out;
    }

    private static List<Integer> counts(Deque<GraidsDistributor.Distribution> queue) {
        List<Integer> out = new ArrayList<>();
        for (GraidsDistributor.Distribution d : queue) {
            out.add(d.count());
        }
        return out;
    }

    private static int sum(Deque<GraidsDistributor.Distribution> queue) {
        int total = 0;
        for (GraidsDistributor.Distribution d : queue) {
            total += d.count();
        }
        return total;
    }

    // ----- The queue order is deterministic even though the bonus is not -----

    @Test
    void queueIsOrderedByFrequencyDescending() {
        Deque<GraidsDistributor.Distribution> queue =
                GraidsDistributor.buildDistribution(freq("low", 1, "high", 3, "mid", 2), 12);

        assertEquals(
                List.of("high", "mid", "low"),
                names(queue),
                "the visit order comes from the sorted participants list, not the bonus pool");
    }

    @Test
    void equalFrequenciesTieBreakOnNameCaseInsensitively() {
        // compareToIgnoreCase, so "Bob" sorts after "alice" rather than before
        // it as a plain compareTo would put every capitalised name first.
        Deque<GraidsDistributor.Distribution> queue =
                GraidsDistributor.buildDistribution(freq("Bob", 2, "alice", 2), 4);

        assertEquals(List.of("alice", "Bob"), names(queue));
    }

    // ----- Shares are proportional, not equal -----

    @Test
    void sharesAreProportionalToParticipation() {
        // 3:1 participation over 8 rewards is 6:2, not 4:4. This is the whole
        // difference from the objectives and split distributors.
        Deque<GraidsDistributor.Distribution> queue =
                GraidsDistributor.buildDistribution(freq("a", 3, "b", 1), 8);

        assertEquals(List.of("a", "b"), names(queue));
        assertEquals(List.of(6, 2), counts(queue));
    }

    @Test
    void anExactDivisionLeavesNoRemainderAndIsFullyDeterministic() {
        Deque<GraidsDistributor.Distribution> queue =
                GraidsDistributor.buildDistribution(freq("a", 2, "b", 1), 6);

        assertEquals(List.of("a", "b"), names(queue));
        assertEquals(List.of(4, 2), counts(queue), "no remainder, so no shuffle is observable");
    }

    // ----- The remainder -----

    @Test
    void theRemainderIsHandedOutAsAPlusOneAndTheOrderStillHolds() {
        // Equal frequencies over 4 rewards: everyone floors to 1 and one
        // participant draws the extra. Which one is random; where they appear
        // in the queue is not.
        Deque<GraidsDistributor.Distribution> queue =
                GraidsDistributor.buildDistribution(freq("c", 1, "a", 1, "b", 1), 4);

        assertEquals(
                List.of("a", "b", "c"), names(queue), "alphabetical, since all frequencies tie");
        assertEquals(4, sum(queue));

        List<Integer> sorted = new ArrayList<>(counts(queue));
        sorted.sort(null);
        assertEquals(List.of(1, 1, 2), sorted);
    }

    @Test
    void everyParticipantDrawsAtMostOneBonus() {
        // The loop indexes a shuffled pool of distinct names, so no name can be
        // picked twice however large the remainder is.
        for (int trial = 0; trial < 50; trial++) {
            Deque<GraidsDistributor.Distribution> queue =
                    GraidsDistributor.buildDistribution(freq("a", 1, "b", 1, "c", 1), 5);
            for (int count : counts(queue)) {
                assertTrue(count <= 2, "floor 1 plus at most one bonus, got " + count);
            }
            assertEquals(5, sum(queue));
        }
    }

    @Test
    void theTotalIsConservedAcrossAWideRange() {
        Map<String, Integer> participants = freq("a", 5, "b", 3, "c", 2, "d", 1);
        for (int count = 1; count <= 120; count++) {
            assertEquals(
                    count,
                    sum(GraidsDistributor.buildDistribution(participants, count)),
                    "count " + count);
        }
    }

    // ----- Zero shares are dropped -----

    @Test
    void participantsWhoFloorToZeroAndMissTheBonusAreNotQueued() {
        // 1 reward across 11 participations: every floor is 0, so only the
        // single bonus winner is queued. Visiting the rest would cost a menu
        // search each to send them nothing.
        Deque<GraidsDistributor.Distribution> queue =
                GraidsDistributor.buildDistribution(freq("a", 10, "b", 1), 1);

        assertEquals(1, queue.size());
        assertEquals(1, sum(queue));
        assertTrue(
                names(queue).equals(List.of("a")) || names(queue).equals(List.of("b")),
                "which of the two draws the bonus is random: " + names(queue));
    }

    @Test
    void aParticipantWithAZeroShareDisappearsWhileTheRestKeepTheirOrder() {
        // 3 rewards over 12 participations: a floors to 2, b to 0, and the one
        // leftover goes to a random one of the two.
        Deque<GraidsDistributor.Distribution> queue =
                GraidsDistributor.buildDistribution(freq("a", 11, "b", 1), 3);

        assertEquals(3, sum(queue));
        assertEquals("a", names(queue).get(0), "the high-frequency participant is always first");
    }

    // ----- Degenerate input -----

    @Test
    void anEmptyFrequencyMapYieldsAnEmptyQueue() {
        // Guarded here, unlike ObjectivesDistributor.buildDistribution, which
        // divides by zero on the same shape.
        assertTrue(GraidsDistributor.buildDistribution(freq(), 5).isEmpty());
    }

    @Test
    void allZeroFrequenciesYieldAnEmptyQueue() {
        assertTrue(GraidsDistributor.buildDistribution(freq("a", 0, "b", 0), 5).isEmpty());
    }

    @Test
    void zeroRewardsYieldAnEmptyQueue() {
        // Brigadier bounds the count at 1, so this is defensive — every share
        // floors to 0 and there is no remainder to rescue any of them.
        assertTrue(GraidsDistributor.buildDistribution(freq("a", 3, "b", 1), 0).isEmpty());
    }
}
