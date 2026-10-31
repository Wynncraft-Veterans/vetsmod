package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RetroGuard} with plain code: a stopping guard runs nothing after its first
 * failure, a retrying one goes on, neither lets a failure out, and each answers its fallback for a
 * failure. What it logs is not checked.
 */
class RetroGuardTest {

    private static final Runnable FAILS =
            () -> {
                throw new IllegalStateException("drawing");
            };

    @Test
    void aStoppingGuardRunsNothingAfterItsFirstFailure() {
        RetroGuard guard = RetroGuard.stopping("a test", "nothing shows");
        AtomicInteger runs = new AtomicInteger();

        guard.run(runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertFalse(guard.stopped());

        guard.run(FAILS);
        assertTrue(guard.stopped());

        guard.run(runs::incrementAndGet);
        assertEquals(1, runs.get(), "stopped");
        assertEquals("fallback", guard.call(() -> "body", "fallback"), "stopped");
    }

    @Test
    void aRetryingGuardGoesOnAfterAFailure() {
        RetroGuard guard = RetroGuard.retrying("a test", "this one is skipped");
        AtomicInteger runs = new AtomicInteger();

        guard.run(FAILS);
        guard.run(FAILS);
        assertFalse(guard.stopped());

        guard.run(runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertEquals("body", guard.call(() -> "body", "fallback"));
    }

    @Test
    void aFailingCallAnswersItsFallback() {
        RetroGuard guard = RetroGuard.retrying("a test", "this one is skipped");

        assertEquals(
                "fallback",
                guard.call(
                        () -> {
                            throw new IllegalStateException("drawing");
                        },
                        "fallback"));
    }

    @Test
    void aLinkageErrorIsCaughtAsAMissingWynntilsClassThrowsIt() {
        RetroGuard guard = RetroGuard.stopping("a test", "nothing shows");

        guard.run(
                () -> {
                    throw new NoClassDefFoundError("com/wynntils/Missing");
                });

        assertTrue(guard.stopped());
    }

    @Test
    void anErrorIsCaughtAsAnExceptionIs() {
        // Picking a stack's model has no event bus above it to catch what escapes.
        RetroGuard guard = RetroGuard.retrying("a test", "this one is skipped");

        guard.run(
                () -> {
                    throw new StackOverflowError();
                });
        assertEquals(
                "fallback",
                guard.call(
                        () -> {
                            throw new AssertionError();
                        },
                        "fallback"));
    }

    @Test
    void aCallEvenIfStoppedStillRunsAndItsFailureCounts() {
        RetroGuard guard = RetroGuard.stopping("a test", "nothing shows");

        assertEquals(
                "fallback",
                guard.callEvenIfStopped(
                        () -> {
                            throw new IllegalStateException("drawing");
                        },
                        "fallback"));
        assertTrue(guard.stopped(), "its failure counts");

        assertEquals("body", guard.callEvenIfStopped(() -> "body", "fallback"), "stopped");
        assertEquals("fallback", guard.call(() -> "body", "fallback"), "a plain call does not run");
    }

    @Test
    void aBodyMayAnswerNull() {
        RetroGuard guard = RetroGuard.retrying("a test", "this one is skipped");

        assertEquals(null, guard.call(() -> null, "fallback"), "a null answer is not a failure");
    }
}
