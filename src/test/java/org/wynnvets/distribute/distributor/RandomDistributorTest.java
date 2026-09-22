package org.wynnvets.distribute.distributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RandomDistributor}'s one pure half, {@code filterNames} — the
 * NoAspects opt-out applied to the wapi legacy-name roster before any picking
 * happens.
 *
 * <p>Everything else in the class is Wynntils- or menu-bound: {@code dispatch}
 * guards on {@code GuildStateManager.isWynntilsReady()} and fans out two
 * lookups (up to three requests), {@code beginPicks} shuffles and sends chat,
 * and the send loop lives in {@link DistributionQueue}. What is left is a
 * list, a set, and a predicate.</p>
 *
 * <p>The identity return is pinned deliberately. {@code filterNames} hands the
 * caller's own list back when nothing is excluded, and a later "always defensive
 * copy" tidy-up would change that silently — no signature moves, no test fails,
 * unless one asserts on the reference. {@link GraidsDistributorTest} pins the
 * same property on {@code filterIndex}.</p>
 *
 * <p>NOTE: {@link RandomDistributor} imports Wynntils {@code Managers} and the
 * mod's own {@code GuildStateManager}, but its entire static state is one
 * {@code Random} and it has no supertypes, so nothing loads during
 * class-init.</p>
 */
class RandomDistributorTest {

    private static final List<String> ROSTER = List.of("Alpha", "Bravo", "Charlie");

    // ----- The empty-exclude fast path -----

    @Test
    void anEmptyExcludeSetHandsBackTheCallerSOwnList() {
        assertSame(
                ROSTER,
                RandomDistributor.filterNames(ROSTER, Set.of()),
                "no copy is made when there is nothing to filter");
    }

    @Test
    void anExcludeSetThatMatchesNothingStillCopies() {
        List<String> out = RandomDistributor.filterNames(ROSTER, Set.of("Nobody"));

        assertEquals(ROSTER, out);
        assertNotSame(
                ROSTER, out, "the fast path is keyed on the set being empty, not on the outcome");
    }

    // ----- The filtering -----

    @Test
    void anExcludedNameIsDroppedAndTheRestKeepTheirOrder() {
        assertEquals(
                List.of("Alpha", "Charlie"),
                RandomDistributor.filterNames(ROSTER, Set.of("Bravo")));
    }

    @Test
    void everyExcludedNameIsDropped() {
        assertEquals(
                List.of("Charlie"),
                RandomDistributor.filterNames(ROSTER, Set.of("Alpha", "Bravo")));
    }

    @Test
    void matchingIsCaseSensitive() {
        // Set.contains, not a case-folded compare. NoAspectsFilter resolves its
        // UUIDs through the same wapi payload the roster comes from, so both
        // sides carry the server's own spelling and never need folding.
        assertEquals(ROSTER, RandomDistributor.filterNames(ROSTER, Set.of("bravo")));
    }

    // ----- Degenerate input -----

    @Test
    void excludingTheWholeRosterYieldsAnEmptyList() {
        assertTrue(
                RandomDistributor.filterNames(ROSTER, Set.of("Alpha", "Bravo", "Charlie"))
                        .isEmpty());
    }

    @Test
    void anEmptyRosterYieldsAnEmptyList() {
        // beginPicks' own isEmpty() check is what turns this into the
        // "Could not read guild roster from wapi" chat line.
        assertTrue(RandomDistributor.filterNames(List.of(), Set.of("Alpha")).isEmpty());
    }
}
