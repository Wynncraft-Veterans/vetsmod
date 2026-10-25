package org.wynnvets.mwe.anni.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AnniWindows} — the anni window edges and the two predicates
 * that are genuinely shared.
 *
 * <p>The boundary table is {@code -1801, -1800, -1799, 0, 5399, 5400, 5401,
 * 7199, 7200, 7201}: one either side of each edge, plus the edge itself, plus
 * the 90-minute value that owns no predicate here. A fixture that samples only
 * the middle of a window cannot tell {@code <=} from {@code <} and pins
 * nothing.</p>
 *
 * <p>{@code now} is injected, following {@code PartyRosterListenerTest}. Nothing
 * in the subject reads a clock, so nothing here needs to fake one.</p>
 */
class AnniWindowsTest {

    /** Arbitrary — only deltas matter. */
    private static final long NOW = 1_700_000_000L;

    /** The ten boundary values, as {@code secondsUntil} (i.e. {@code stamp - now}). */
    private static final long[] BOUNDARY = {
        -1801L, -1800L, -1799L, 0L, 5399L, 5400L, 5401L, 7199L, 7200L, 7201L,
    };

    // ── The constants themselves ──────────────────────────────────────

    @Test
    void constantsHoldTheValuesTheCallSitesUsedToDeclare() {
        assertEquals(
                7200L,
                AnniWindows.HOT_WINDOW_BEFORE_SECONDS,
                "2 h open edge. Three constants hold 7200 today: this one, plus "
                        + "PartyRosterListener.ACTIVE_WINDOW_SEC (a symmetric +/-2h, a "
                        + "different shape) and AnniCommandRenderer.TWO_HOURS_SECONDS (a "
                        + "render-branch selector, not a window) — both deliberate "
                        + "non-residents");
        assertEquals(
                1800L,
                AnniWindows.HOT_WINDOW_AFTER_SECONDS,
                "30 m AFTER the stamp (T+30m, not T-30m) — the close edge. Three call "
                        + "sites declared it before the collapse; this is the only constant now");
        assertEquals(
                5400L,
                AnniWindows.BAR_WINDOW_SECONDS,
                "90 m. Two call sites declared it before the collapse; this is the only "
                        + "constant now");
    }

    /**
     * {@link AnniWindows#BAR_WINDOW_SECONDS} deliberately has no predicate, so
     * there is nothing here to assert beyond its value — pinned above.
     *
     * <p>Stated rather than tested, because a test cannot pin a comparison it
     * does not own: {@code AnniSnapshotPoller} floors its 90-minute check at
     * {@code secondsUntilAnni > 0} and {@code VetsBossBarManager} floors its at
     * a 20-second hard return earlier in the same method. Both comparisons stay
     * at their call sites. Writing a case here that looked like it covered them
     * would be worse than writing none.</p>
     */
    @Test
    void barWindowIsAConstantAndNotAPredicate() {
        assertEquals(90L * 60L, AnniWindows.BAR_WINDOW_SECONDS);
    }

    // ── inHotWindow, at every boundary value ──────────────────────────

    @Test
    void inHotWindow_openEdgeIsInclusive() {
        assertTrue(AnniWindows.inHotWindow(7199L));
        assertTrue(AnniWindows.inHotWindow(7200L), "T-2h exactly is in the window");
        assertFalse(AnniWindows.inHotWindow(7201L), "one second earlier is out");
    }

    @Test
    void inHotWindow_closeEdgeIsInclusive() {
        assertFalse(AnniWindows.inHotWindow(-1801L), "one second past T+30m is out");
        assertTrue(AnniWindows.inHotWindow(-1800L), "T+30m exactly is in the window");
        assertTrue(AnniWindows.inHotWindow(-1799L));
    }

    @Test
    void inHotWindow_acceptsEveryInteriorBoundaryValue() {
        assertTrue(AnniWindows.inHotWindow(0L), "the stamp itself");
        assertTrue(AnniWindows.inHotWindow(5399L));
        assertTrue(AnniWindows.inHotWindow(5400L), "T-90m is interior to the hot window");
        assertTrue(AnniWindows.inHotWindow(5401L));
    }

    @Test
    void inHotWindow_isFalseOnlyOutsideBothEdges() {
        for (long secondsUntil : BOUNDARY) {
            boolean expected = secondsUntil >= -1800L && secondsUntil <= 7200L;
            assertEquals(
                    expected,
                    AnniWindows.inHotWindow(secondsUntil),
                    "boundary value " + secondsUntil);
        }
    }

    // ── hotWindowClosed, the watcher's inverted arrangement ───────────

    @Test
    void hotWindowClosed_agreesWithInHotWindowsCloseEdge() {
        // The only executable evidence that collapsing three declarations of
        // 1800 into one was safe: the watcher compares now against
        // anchor + WINDOW, the tickers compare stamp - now against -WINDOW, and
        // the two arrangements have to break at the same second.
        assertFalse(AnniWindows.hotWindowClosed(NOW - 1799L, NOW), "T+29m59s — still open");
        assertFalse(AnniWindows.hotWindowClosed(NOW - 1800L, NOW), "T+30m exactly — still open");
        assertTrue(AnniWindows.hotWindowClosed(NOW - 1801L, NOW), "T+30m01s — closed");
    }

    @Test
    void hotWindowClosed_isTheComplementOfTheCloseEdgeAtEveryBoundaryValue() {
        for (long secondsUntil : BOUNDARY) {
            long anchor = NOW + secondsUntil;
            assertEquals(
                    secondsUntil < -1800L,
                    AnniWindows.hotWindowClosed(anchor, NOW),
                    "boundary value " + secondsUntil);
        }
    }

    @Test
    void hotWindowClosed_isFalseForAStampStillInTheFuture() {
        assertFalse(AnniWindows.hotWindowClosed(NOW + 7200L, NOW));
        assertFalse(AnniWindows.hotWindowClosed(NOW + 7201L, NOW), "before the window opens too");
    }
}
