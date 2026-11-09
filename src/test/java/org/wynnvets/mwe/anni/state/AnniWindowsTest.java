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

    // ── The bar window ────────────────────────────────────────────────

    /**
     * {@link AnniWindows#BAR_WINDOW_SECONDS} has no predicate, so its value is all there is to
     * pin. Its two comparisons stay at their call sites, each with a floor of its own that a
     * test here cannot reach: {@code AnniSnapshotPoller} checks {@code secondsUntilAnni > 0},
     * and {@code VetsBossBarManager} returns at 20 seconds earlier in the same method.
     */
    @Test
    void barWindowIsNinetyMinutes() {
        assertEquals(
                5400L,
                AnniWindows.BAR_WINDOW_SECONDS,
                "the boss bar and the snapshot poll start 90 m before the stamp");
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

    // ── hotWindowClosed, the watcher's inverted arrangement ───────────

    @Test
    void hotWindowClosed_agreesWithInHotWindowsCloseEdge() {
        // The watcher compares now against anchor + WINDOW, the tickers compare
        // stamp - now against -WINDOW, and the two arrangements have to break at
        // the same second.
        assertFalse(AnniWindows.hotWindowClosed(NOW - 1799L, NOW), "T+29m59s — still open");
        assertFalse(AnniWindows.hotWindowClosed(NOW - 1800L, NOW), "T+30m exactly — still open");
        assertTrue(AnniWindows.hotWindowClosed(NOW - 1801L, NOW), "T+30m01s — closed");
    }

    @Test
    void hotWindowClosed_isTheComplementOfInHotWindowOnceTheWindowHasOpened() {
        for (long secondsUntil : BOUNDARY) {
            // Before the open edge the window is neither open nor closed: it has yet to open.
            boolean opened = secondsUntil <= AnniWindows.HOT_WINDOW_BEFORE_SECONDS;
            assertEquals(
                    opened && !AnniWindows.inHotWindow(secondsUntil),
                    AnniWindows.hotWindowClosed(NOW + secondsUntil, NOW),
                    "boundary value " + secondsUntil);
        }
    }

    @Test
    void hotWindowClosed_isFalseForAStampStillInTheFuture() {
        assertFalse(AnniWindows.hotWindowClosed(NOW + 7200L, NOW));
        assertFalse(AnniWindows.hotWindowClosed(NOW + 7201L, NOW), "before the window opens too");
    }
}
