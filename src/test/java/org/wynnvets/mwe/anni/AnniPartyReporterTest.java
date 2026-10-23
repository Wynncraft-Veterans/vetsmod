package org.wynnvets.mwe.anni;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniPartyReporter#shouldRecapture} — the pure decision behind the reporter's
 * two triggers: the case-folded organiser set changed, or the anni window opened (it was closed
 * on the previous update and is open on this one). The method has no statics or I/O; every input
 * is passed in.
 */
class AnniPartyReporterTest {

    private static final Set<String> NONE = Set.of();
    private static final Set<String> ORGANISER = Set.of("organiser");

    // ── Organiser set ─────────────────────────────────────────────────

    @Test
    void setChangeRecapturesInAnyDirection() {
        // Empty to non-empty, content change, non-empty back to empty — all outside the window.
        assertTrue(AnniPartyReporter.shouldRecapture(NONE, ORGANISER, false, false));
        assertTrue(AnniPartyReporter.shouldRecapture(ORGANISER, Set.of("other"), false, false));
        assertTrue(
                AnniPartyReporter.shouldRecapture(
                        ORGANISER, Set.of("organiser", "other"), false, false));
        assertTrue(AnniPartyReporter.shouldRecapture(ORGANISER, NONE, false, false));
    }

    @Test
    void setChangeRecapturesInsideTheWindowToo() {
        assertTrue(AnniPartyReporter.shouldRecapture(NONE, ORGANISER, true, true));
        assertTrue(AnniPartyReporter.shouldRecapture(ORGANISER, NONE, true, true));
    }

    // ── Window edge ───────────────────────────────────────────────────

    @Test
    void windowOpeningRecapturesWithAnUnchangedSet() {
        // The case the set trigger misses: the set was complete before the window opened.
        assertTrue(AnniPartyReporter.shouldRecapture(ORGANISER, ORGANISER, false, true));
        assertTrue(AnniPartyReporter.shouldRecapture(NONE, NONE, false, true));
    }

    @Test
    void unchangedSetWithoutAnOpeningRecapturesNothing() {
        // Still outside, still inside, and the window closing: none is a trigger.
        assertFalse(AnniPartyReporter.shouldRecapture(ORGANISER, ORGANISER, false, false));
        assertFalse(AnniPartyReporter.shouldRecapture(ORGANISER, ORGANISER, true, true));
        assertFalse(AnniPartyReporter.shouldRecapture(ORGANISER, ORGANISER, true, false));
        assertFalse(AnniPartyReporter.shouldRecapture(NONE, NONE, false, false));
    }

    @Test
    void windowClosingWithAChangedSetStillRecaptures() {
        assertTrue(AnniPartyReporter.shouldRecapture(ORGANISER, NONE, true, false));
    }
}
