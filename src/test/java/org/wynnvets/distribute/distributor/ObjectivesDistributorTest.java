package org.wynnvets.distribute.distributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ObjectivesDistributor}'s two pure halves —
 * {@code hasCompletedObjective}, which reads a member tile's lore, and
 * {@code buildDistribution}, which turns a completer list into a send queue.
 *
 * <p>The input is section-sign-stripped lore lines. The producer side
 * ({@code MembersListWalker}) is Wynntils-bound and untestable; the consumer
 * side is plain strings.</p>
 *
 * <p>{@code buildDistribution} shuffles, so its assertions are invariants and
 * multisets rather than positions. Every count assertion below sorts before
 * comparing, so none of them depends on which recipient drew a bonus.</p>
 *
 * <p>NOTE: {@link ObjectivesDistributor} imports Wynntils {@code Managers}, but
 * its static state is a {@code String}, a {@code Pattern} and a {@code Random},
 * so nothing loads during class-init.</p>
 */
class ObjectivesDistributorTest {

    private static final String HEADER = "Guild Objective:";

    private static List<Integer> sortedCounts(Deque<ObjectivesDistributor.Distribution> queue) {
        List<Integer> counts = new ArrayList<>();
        for (ObjectivesDistributor.Distribution d : queue) {
            counts.add(d.count());
        }
        counts.sort(null);
        return counts;
    }

    private static List<String> names(Deque<ObjectivesDistributor.Distribution> queue) {
        List<String> out = new ArrayList<>();
        for (ObjectivesDistributor.Distribution d : queue) {
            out.add(d.legacyName());
        }
        out.sort(null);
        return out;
    }

    // ----- hasCompletedObjective: the header -----

    @Test
    void header_isMatchedWithEqualsSoAnyDecorationDefeatsIt() {
        // equals(), not contains() or startsWith(). A trailing space, a colour
        // code the stripper missed, or a leading bullet all mean "no objective
        // section" and the member is silently treated as not-completed.
        assertTrue(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of(HEADER, "- Gather Ores: 200/200")));
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of(HEADER + " ", "- Gather Ores: 200/200")),
                "a trailing space defeats the header match");
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of("§7" + HEADER, "- Gather Ores: 200/200")),
                "an unstripped section-sign prefix defeats it too");
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of("Guild Objective", "- Gather Ores: 200/200")),
                "the colon is part of the literal");
    }

    @Test
    void missingHeaderIsNotCompleted() {
        // A freshly-joined recruit has no objective section at all.
        assertFalse(ObjectivesDistributor.hasCompletedObjective(List.of()));
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of("Rank: Recruit", "- Gather Ores: 200/200")),
                "the progress line alone is not enough — the scan is header-anchored");
    }

    @Test
    void headerWithNothingAfterItIsNotCompleted() {
        assertFalse(ObjectivesDistributor.hasCompletedObjective(List.of(HEADER)));
        assertFalse(ObjectivesDistributor.hasCompletedObjective(List.of(HEADER, "", "  ")));
    }

    // ----- hasCompletedObjective: what follows the header -----

    @Test
    void blankSeparatorLinesAfterTheHeaderAreSkipped() {
        assertTrue(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of(HEADER, "", "   ", "- Gather Ores: 200/200")));
    }

    @Test
    void aNonBlankNonMatchingLineAfterTheHeaderBailsImmediately() {
        // The sharp edge: the scan does not keep looking. If the tile ever puts
        // the streak line first, a genuinely completed objective on the next
        // line is never read.
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of(HEADER, "- Streak: 76", "- Gather Ores: 200/200")),
                "a real progress line below an unrecognised one is never reached");
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of(HEADER, "Objective complete!", "- Gather Ores: 200/200")));
    }

    @Test
    void theStreakLineAloneIsNotProgress() {
        // "- Streak: 76" has no slash, so it fails the pattern — which is what
        // makes the bail-out above fire rather than a false read.
        assertFalse(ObjectivesDistributor.hasCompletedObjective(List.of(HEADER, "- Streak: 76")));
    }

    // ----- hasCompletedObjective: the comparison -----

    @Test
    void completionIsGreaterOrEqualNotEqual() {
        assertTrue(ObjectivesDistributor.hasCompletedObjective(List.of(HEADER, "- Ores: 200/200")));
        assertTrue(
                ObjectivesDistributor.hasCompletedObjective(List.of(HEADER, "- Ores: 240/200")),
                "overshooting still counts as complete");
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(List.of(HEADER, "- Ores: 199/200")));
    }

    @Test
    void aZeroOfZeroObjectiveReadsAsComplete() {
        // Follows from >=; worth stating because it is the one input where
        // "complete" and "not started" are indistinguishable.
        assertTrue(ObjectivesDistributor.hasCompletedObjective(List.of(HEADER, "- Ores: 0/0")));
    }

    @Test
    void anUnparseableNumberIsSwallowedRatherThanPropagated() {
        // The pattern accepts any run of digits, so a value past Integer.MAX
        // reaches parseInt. The catch turns it into "not completed" instead of
        // throwing out of the walk callback and killing the whole distribution.
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of(HEADER, "- Ores: 99999999999/1")),
                "NumberFormatException becomes false, not an escape");
    }

    @Test
    void theFirstMatchingLineDecidesEvenIfALaterOneWouldDisagree() {
        assertFalse(
                ObjectivesDistributor.hasCompletedObjective(
                        List.of(HEADER, "- Ores: 1/200", "- Logs: 200/200")),
                "the method returns on the first line that matches the pattern");
    }

    // ----- buildDistribution -----

    @Test
    void buildDistribution_evenSplitGivesEveryoneTheSameCount() {
        Deque<ObjectivesDistributor.Distribution> queue =
                ObjectivesDistributor.buildDistribution(List.of("a", "b", "c"), 6);

        assertEquals(3, queue.size());
        assertEquals(List.of(2, 2, 2), sortedCounts(queue));
        assertEquals(
                List.of("a", "b", "c"), names(queue), "every completer is queued exactly once");
    }

    @Test
    void buildDistribution_remainderBecomesAPlusOneBonus() {
        Deque<ObjectivesDistributor.Distribution> queue =
                ObjectivesDistributor.buildDistribution(List.of("a", "b", "c"), 7);

        assertEquals(3, queue.size());
        assertEquals(
                List.of(2, 2, 3),
                sortedCounts(queue),
                "base 2 each, and the single leftover goes to one shuffled winner");
        assertEquals(List.of("a", "b", "c"), names(queue));
    }

    @Test
    void buildDistribution_recipientsWhoWouldGetZeroAreDropped() {
        // N < K: base is 0, so only the `remainder` bonus winners are queued.
        // Visiting the rest just to send them nothing would cost a menu search
        // each.
        Deque<ObjectivesDistributor.Distribution> queue =
                ObjectivesDistributor.buildDistribution(List.of("a", "b", "c", "d", "e"), 2);

        assertEquals(2, queue.size());
        assertEquals(List.of(1, 1), sortedCounts(queue));
    }

    @Test
    void buildDistribution_totalIsAlwaysConserved() {
        List<String> completers = List.of("a", "b", "c", "d", "e", "f", "g");
        for (int total = 0; total <= 64; total++) {
            Deque<ObjectivesDistributor.Distribution> queue =
                    ObjectivesDistributor.buildDistribution(completers, total);
            int sum = 0;
            for (ObjectivesDistributor.Distribution d : queue) {
                sum += d.count();
            }
            assertEquals(total, sum, "the queue must hand out exactly what it was given: " + total);
        }
    }

    @Test
    void buildDistribution_countsNeverDifferByMoreThanOneAmongQueuedRecipients() {
        List<String> completers = List.of("a", "b", "c", "d", "e", "f", "g");
        for (int total = 1; total <= 64; total++) {
            List<Integer> counts =
                    sortedCounts(ObjectivesDistributor.buildDistribution(completers, total));
            assertTrue(
                    counts.get(counts.size() - 1) - counts.get(0) <= 1,
                    "spread must stay within one for total " + total + ": " + counts);
        }
    }

    @Test
    void buildDistribution_zeroTotalYieldsAnEmptyQueue() {
        // Brigadier bounds count at 1 or above, so this is only reachable
        // defensively — but the caller checks isEmpty() precisely because of it.
        assertTrue(ObjectivesDistributor.buildDistribution(List.of("a", "b"), 0).isEmpty());
    }

    @Test
    void buildDistribution_dividesByZeroOnAnEmptyCompleterList() {
        // Unguarded. The only thing standing between this and a crash is the
        // caller's completers.isEmpty() early return — which is a caller
        // invariant, not a property of this method. Pinned so a later
        // extraction has to carry the guard rather than assume it.
        assertThrows(
                ArithmeticException.class,
                () -> ObjectivesDistributor.buildDistribution(List.of(), 5));
    }
}
