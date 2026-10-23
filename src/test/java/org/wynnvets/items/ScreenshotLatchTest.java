package org.wynnvets.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScreenshotLatch}: a press is consumed once, only while it is fresh, and a
 * stale press is dropped rather than left for a later tooltip. Time is a parameter, so nothing
 * here sleeps.
 */
class ScreenshotLatchTest {

    private static final long T0 = 5_000L;

    @Test
    void aFreshLatchIsNotArmed() {
        assertFalse(new ScreenshotLatch().consume(T0));
    }

    @Test
    void aPressIsConsumedExactlyOnce() {
        ScreenshotLatch latch = new ScreenshotLatch();
        latch.press(T0);

        assertTrue(latch.consume(T0 + 10));
        assertFalse(latch.consume(T0 + 20));
    }

    @Test
    void aPressIsStillLiveAtTheEndOfItsLifetime() {
        ScreenshotLatch latch = new ScreenshotLatch();
        latch.press(T0);

        assertTrue(latch.consume(T0 + ScreenshotLatch.LIFETIME_MS));
    }

    @Test
    void aPressPastItsLifetimeIsDroppedAndNeverFiresLater() {
        ScreenshotLatch latch = new ScreenshotLatch();
        latch.press(T0);

        assertFalse(latch.consume(T0 + ScreenshotLatch.LIFETIME_MS + 1));
        // The stale press was cleared by that consume, so a later look finds nothing.
        assertFalse(latch.consume(T0 + ScreenshotLatch.LIFETIME_MS + 2));
    }

    @Test
    void aSecondPressRestartsTheLifetime() {
        ScreenshotLatch latch = new ScreenshotLatch();
        latch.press(T0);
        latch.press(T0 + 900);

        assertTrue(latch.consume(T0 + 900 + ScreenshotLatch.LIFETIME_MS));
    }

    @Test
    void aPressAfterAnExpiredOneArmsAgain() {
        ScreenshotLatch latch = new ScreenshotLatch();
        latch.press(T0);
        assertFalse(latch.consume(T0 + 5_000));

        latch.press(T0 + 6_000);
        assertTrue(latch.consume(T0 + 6_100));
    }
}
