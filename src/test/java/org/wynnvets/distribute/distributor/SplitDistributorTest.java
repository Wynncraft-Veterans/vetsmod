package org.wynnvets.distribute.distributor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SplitDistributor}'s three-way pool split.
 *
 * <p>{@code splitCount} returns an {@code int[]} of length 3 and it
 * <b>shuffles</b> — the remainder is handed to a random subset of the three
 * pools, not to the first ones. Assertions are therefore invariants and
 * multisets, with exact array equality only where the shuffle never runs —
 * i.e. wherever {@code remainder > 0} is false, which covers the multiples of
 * three <em>and</em> every negative input, since a negative remainder fails
 * that guard too. A sweep over the whole brigadier-reachable
 * range is the real guard: it is the only thing that would catch a rewrite that
 * conserved the total for the cases someone thought to enumerate and lost it
 * elsewhere.</p>
 *
 * <p>NOTE: {@link SplitDistributor} imports Wynntils {@code Managers}, but its
 * static state is a {@code Random} and an {@code int}, so nothing loads during
 * class-init.</p>
 */
class SplitDistributorTest {

    /** Brigadier bounds the command's count argument to this range. */
    private static final int MAX_REACHABLE_COUNT = 255;

    private static int sum(int[] pools) {
        return pools[0] + pools[1] + pools[2];
    }

    private static int[] sorted(int[] pools) {
        int[] copy = pools.clone();
        Arrays.sort(copy);
        return copy;
    }

    // ----- Shape -----

    @Test
    void alwaysReturnsThreePools() {
        // One per phase: @graids, @objectives, @random, in that order.
        for (int count = 0; count <= MAX_REACHABLE_COUNT; count++) {
            assertEquals(3, SplitDistributor.splitCount(count).length, "count " + count);
        }
    }

    // ----- The two invariants that matter -----

    @Test
    void totalIsConservedAcrossTheWholeReachableRange() {
        for (int count = 0; count <= MAX_REACHABLE_COUNT; count++) {
            assertEquals(count, sum(SplitDistributor.splitCount(count)), "count " + count);
        }
    }

    @Test
    void poolsNeverDifferByMoreThanOne() {
        for (int count = 0; count <= MAX_REACHABLE_COUNT; count++) {
            int[] pools = sorted(SplitDistributor.splitCount(count));
            assertTrue(
                    pools[2] - pools[0] <= 1,
                    "spread must stay within one for count "
                            + count
                            + ": "
                            + Arrays.toString(pools));
        }
    }

    // ----- Exact results where the shuffle is unobservable -----

    @Test
    void aMultipleOfThreeSplitsEvenlyAndDeterministically() {
        assertArrayEquals(new int[] {0, 0, 0}, SplitDistributor.splitCount(0));
        assertArrayEquals(new int[] {1, 1, 1}, SplitDistributor.splitCount(3));
        assertArrayEquals(new int[] {21, 21, 21}, SplitDistributor.splitCount(63));
    }

    // ----- Multiset results where it is not -----

    @Test
    void oneLeftoverGoesToExactlyOneRandomPool() {
        assertArrayEquals(new int[] {2, 2, 3}, sorted(SplitDistributor.splitCount(7)));
    }

    @Test
    void twoLeftoversGoToTwoDistinctPools() {
        // The shuffle is over the pool indices, so the same pool cannot draw the
        // bonus twice.
        assertArrayEquals(new int[] {2, 3, 3}, sorted(SplitDistributor.splitCount(8)));
    }

    @Test
    void countsBelowThreeGiveOneEachToThatManyPools() {
        assertArrayEquals(new int[] {0, 0, 1}, sorted(SplitDistributor.splitCount(1)));
        assertArrayEquals(new int[] {0, 1, 1}, sorted(SplitDistributor.splitCount(2)));
    }

    @Test
    void theBonusReachesEveryPoolAcrossRepeatedDraws() {
        // Guards against the shuffle degenerating — a fixed-index remainder
        // would still satisfy every invariant above.
        boolean[] seen = new boolean[3];
        for (int trial = 0; trial < 200; trial++) {
            int[] pools = SplitDistributor.splitCount(1);
            for (int i = 0; i < 3; i++) {
                if (pools[i] == 1) {
                    seen[i] = true;
                }
            }
        }
        assertTrue(seen[0] && seen[1] && seen[2], "each pool must be reachable by the bonus");
    }

    // ----- Negative input: undocumented, unreachable, pinned anyway -----

    @Test
    void negativeCountsSilentlyDropTheRemainder() {
        // Java truncates integer division toward zero, so count == -1 gives
        // base 0 and remainder -1. The bonus loop is guarded by
        // `remainder > 0`, so the -1 is simply lost and the total is not
        // conserved. Brigadier bounds the argument at 1, so this is
        // unreachable in production — but it is also undocumented, and a
        // rewrite using Math.floorDiv/floorMod would change it.
        assertArrayEquals(new int[] {0, 0, 0}, SplitDistributor.splitCount(-1));
        assertEquals(0, sum(SplitDistributor.splitCount(-1)), "the -1 is dropped, not distributed");

        assertArrayEquals(new int[] {-1, -1, -1}, SplitDistributor.splitCount(-4));
        assertEquals(-3, sum(SplitDistributor.splitCount(-4)), "one short of -4, same cause");
    }

    @Test
    void negativeMultiplesOfThreeAreConservedBecauseTheyHaveNoRemainder() {
        assertArrayEquals(new int[] {-2, -2, -2}, SplitDistributor.splitCount(-6));
        assertEquals(-6, sum(SplitDistributor.splitCount(-6)));
    }
}
