package org.wynnvets.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TickDelaysTest {

    // ── ticksFor: round up, so a body never runs before its delay ─────────

    @Test
    void ticksFor_roundsUpToWholeTicks() {
        assertEquals(0, TickDelays.ticksFor(0));
        assertEquals(1, TickDelays.ticksFor(1));
        assertEquals(1, TickDelays.ticksFor(49));
        assertEquals(1, TickDelays.ticksFor(50));
        assertEquals(2, TickDelays.ticksFor(51));
    }

    @Test
    void ticksFor_theCallersDelays() {
        assertEquals(40, TickDelays.ticksFor(2_000));
        assertEquals(60, TickDelays.ticksFor(3_000));
        assertEquals(100, TickDelays.ticksFor(5_000));
    }

    @Test
    void ticksFor_negativeIsZero() {
        assertEquals(0, TickDelays.ticksFor(-1));
        assertEquals(0, TickDelays.ticksFor(Long.MIN_VALUE));
    }

    @Test
    void ticksFor_hugeIsClamped() {
        assertEquals(Integer.MAX_VALUE, TickDelays.ticksFor(Long.MAX_VALUE));
    }

    // ── guarded: a new task per call, which runs the body and swallows throws ──

    @Test
    void guarded_sameBodyAndLabel_neverEqual() {
        Runnable body = () -> {};
        Runnable a = TickDelays.guarded("label", body);
        Runnable b = TickDelays.guarded("label", body);
        assertNotSame(a, b);
        assertNotEquals(a, b);
    }

    @Test
    void guarded_runsTheBody() {
        AtomicInteger runs = new AtomicInteger();
        TickDelays.guarded("label", runs::incrementAndGet).run();
        assertEquals(1, runs.get());
    }

    @Test
    void guarded_swallowsRuntimeException() {
        AtomicInteger runs = new AtomicInteger();
        Runnable task =
                TickDelays.guarded(
                        "label",
                        () -> {
                            runs.incrementAndGet();
                            throw new IllegalStateException("boom");
                        });
        assertDoesNotThrow(task::run);
        assertEquals(1, runs.get());
    }

    @Test
    void guarded_swallowsError() {
        AtomicInteger runs = new AtomicInteger();
        Runnable task =
                TickDelays.guarded(
                        "label",
                        () -> {
                            runs.incrementAndGet();
                            throw new AssertionError("boom");
                        });
        assertDoesNotThrow(task::run);
        assertEquals(1, runs.get());
    }
}
