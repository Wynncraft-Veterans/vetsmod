package org.wynnvets.distribute.distributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RandomDistributor}'s two pure helpers: {@code filterNames} — the
 * NoAspects opt-out applied to the wapi legacy-name roster before any picking
 * happens — and {@code distinctIgnoringCase}, which keeps each tile name once.
 *
 * <p>Everything else in the class is Wynntils- or menu-bound: {@code dispatch}
 * guards on {@code GuildStateManager.isWynntilsReady()} and fans out two
 * lookups (up to three requests on an aspects run), {@code beginPicks}
 * shuffles and sends chat,
 * and the send loop lives in {@link DistributionQueue}. What is left is a
 * list, a set, and a predicate.</p>
 *
 * <p>NOTE: {@link RandomDistributor} imports Wynntils {@code Managers} and the
 * mod's own {@code GuildStateManager}, but its entire static state is one
 * {@code Random} and it has no supertypes, so nothing loads during
 * class-init.</p>
 */
class RandomDistributorTest {

    private static final List<String> ROSTER = List.of("Metrafish", "Holidaze", "Wenweia");

    // ----- The filtering -----

    @Test
    void anEmptyExcludeSetKeepsEveryName() {
        assertEquals(ROSTER, RandomDistributor.filterNames(ROSTER, Set.of()));
    }

    @Test
    void anExcludedNameIsDroppedAndTheRestKeepTheirOrder() {
        assertEquals(
                List.of("Metrafish", "Wenweia"),
                RandomDistributor.filterNames(ROSTER, Set.of("Holidaze")));
    }

    @Test
    void everyExcludedNameIsDropped() {
        assertEquals(
                List.of("Wenweia"),
                RandomDistributor.filterNames(ROSTER, Set.of("Metrafish", "Holidaze")));
    }

    @Test
    void matchingIsCaseSensitive() {
        // Set.contains, not a case-folded compare. NoAspectsFilter resolves its
        // UUIDs through the same wapi payload the roster comes from, so both
        // sides carry the server's own spelling and never need folding.
        assertEquals(ROSTER, RandomDistributor.filterNames(ROSTER, Set.of("holidaze")));
    }

    // ----- Degenerate input -----

    @Test
    void excludingTheWholeRosterYieldsAnEmptyList() {
        assertTrue(
                RandomDistributor.filterNames(ROSTER, Set.of("Metrafish", "Holidaze", "Wenweia"))
                        .isEmpty());
    }

    @Test
    void anEmptyRosterYieldsAnEmptyList() {
        // beginPicks' own isEmpty() check is what turns this into the
        // "Could not read guild roster from wapi" chat line.
        assertTrue(RandomDistributor.filterNames(List.of(), Set.of("Metrafish")).isEmpty());
    }

    // ----- distinctIgnoringCase -----

    @Test
    void distinctIgnoringCase_keepsTheFirstSpellingOfEachTileName() {
        assertEquals(
                List.of("Nazzae", "Metrafish", "Holidaze"),
                RandomDistributor.distinctIgnoringCase(
                        List.of("Nazzae", "Metrafish", "nazzae", "Holidaze", "NAZZAE")));
    }

    @Test
    void distinctIgnoringCase_foldsAnIUnderATurkishDefaultLocale() {
        // With the JVM's default locale a Turkish default would fold "METRAFISH" to a
        // dotless i, and the two spellings would both stay in the pool.
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));
        try {
            assertEquals(
                    List.of("METRAFISH"),
                    RandomDistributor.distinctIgnoringCase(List.of("METRAFISH", "metrafish")));
        } finally {
            Locale.setDefault(original);
        }
    }
}
