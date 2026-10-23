package org.wynnvets.items;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A one-shot, short-lived latch for a press of Wynntils' item-screenshot key.
 *
 * <p>The key press arrives on an input event, but the tooltip pass that can act on it comes on a
 * later frame, so the press has to be remembered in between. A press that no tooltip pass picks
 * up must not wait forever and fire on some unrelated later tooltip, so the latch expires
 * {@link #LIFETIME_MS} after the press, and {@link #consume(long)} clears it whether or not it
 * had expired. Time is passed in, which keeps this class free of Minecraft, Fabric and Wynntils
 * types so it can be unit tested; the caller passes a monotonic millisecond reading.</p>
 *
 * <p>Thread-safe: the press and the consume can come from different threads.</p>
 */
final class ScreenshotLatch {

    /** How long a press stays live: long enough to span the frame or two before the tooltip. */
    static final long LIFETIME_MS = 1000;

    private static final long UNARMED = Long.MIN_VALUE;

    private final AtomicLong pressedAtMs = new AtomicLong(UNARMED);

    /** Arms the latch at {@code nowMs}; a press while armed restarts the lifetime. */
    void press(long nowMs) {
        pressedAtMs.set(nowMs);
    }

    /**
     * Returns {@code true} exactly once per press, when called within {@link #LIFETIME_MS} of it.
     * Always disarms the latch, so an expired press is dropped rather than left for later.
     */
    boolean consume(long nowMs) {
        long pressedAt = pressedAtMs.getAndSet(UNARMED);
        if (pressedAt == UNARMED) {
            return false;
        }
        long age = nowMs - pressedAt;
        return age >= 0 && age <= LIFETIME_MS;
    }
}
