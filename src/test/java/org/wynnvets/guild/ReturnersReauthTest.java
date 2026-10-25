package org.wynnvets.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReturnersReauth}'s pure parts: when an attempt sends the auth frame, which
 * failure details count as transient, the in-progress window a join opens, and the disconnect
 * fence, which also closes the window.
 *
 * <p>{@code onJoinedReturners} and the attempt it schedules read {@code UnlockManager} (and so
 * {@code VetsConfig}), {@code V1ApiManager} and Wynntils' tick scheduler, and are not exercised.
 * {@code UnlockManager.TIER_MEMBER} is a compile-time constant, so {@code shouldReauth} does not
 * initialise {@code UnlockManager}.</p>
 */
class ReturnersReauthTest {

    @Test
    void shouldReauth_sendsForAStoredUnrejectedKeyOnALiveSocketBelowMember() {
        assertTrue(ReturnersReauth.shouldReauth(true, true, "waitlist", false));
        assertTrue(ReturnersReauth.shouldReauth(true, true, "honourary", false));
        assertTrue(ReturnersReauth.shouldReauth(true, true, "other", false));
        assertTrue(
                ReturnersReauth.shouldReauth(true, true, "", false),
                "no standing tier (no ok ack yet, or a failed one) is not member");
    }

    @Test
    void shouldReauth_eachMissingConditionBlocksIt() {
        assertFalse(ReturnersReauth.shouldReauth(false, true, "waitlist", false), "no key");
        assertFalse(ReturnersReauth.shouldReauth(true, false, "waitlist", false), "socket down");
        assertFalse(ReturnersReauth.shouldReauth(true, true, "waitlist", true), "key rejected");
        assertFalse(
                ReturnersReauth.shouldReauth(true, true, "member", false),
                "already member: nothing for a re-auth to change");
    }

    /** Every introspection reason temporary-server's provider produces (app/auth/provider.py),
     *  as the auth handler wraps it. */
    @Test
    void isTransient_holdsForEachIntrospectionFailure() {
        assertTrue(
                ReturnersReauth.isTransient(
                        "auth rejected: introspection unavailable: ConnectTimeout"));
        assertTrue(ReturnersReauth.isTransient("auth rejected: introspection HTTP 502"));
        assertTrue(ReturnersReauth.isTransient("auth rejected: introspection bad payload"));
        assertTrue(ReturnersReauth.isTransient("auth rejected: introspection misconfigured (401)"));
        assertTrue(ReturnersReauth.isTransient("auth rejected: introspection disabled at dazebot"));
    }

    @Test
    void isTransient_failsForAVerdictOnTheKeyAndForNonAuthDetails() {
        assertFalse(ReturnersReauth.isTransient("auth rejected: revoked"));
        assertFalse(ReturnersReauth.isTransient("auth rejected: unknown key"));
        assertFalse(ReturnersReauth.isTransient("auth rejected: empty key"));
        assertFalse(ReturnersReauth.isTransient("Authentication required"));
        assertFalse(ReturnersReauth.isTransient(null));
    }

    @Test
    void fenced_runsWhenNoResetCameBetween() {
        AtomicInteger runs = new AtomicInteger();
        Runnable retry =
                ReturnersReauth.fenced(ReturnersReauth.generation(), runs::incrementAndGet);
        retry.run();
        assertEquals(1, runs.get());
    }

    @Test
    void aJoinsReauthIsInProgressUntilItsRetryAndTheAckGraceHavePassed() {
        long joined = 1_000_000L;
        long end = ReturnersReauth.windowEnd(joined);
        assertEquals(joined + 330_000L + 30_000L, end);
        assertTrue(ReturnersReauth.inProgressAt(joined, end));
        assertTrue(ReturnersReauth.inProgressAt(joined + ReturnersReauth.RETRY_DELAY_MS, end));
        assertFalse(ReturnersReauth.inProgressAt(end, end));
        assertFalse(ReturnersReauth.inProgressAt(joined, 0L), "no join opened a window");
    }

    @Test
    void aResetClosesTheWindow() {
        ReturnersReauth.openWindowUntil(System.currentTimeMillis() + 60_000L);
        assertTrue(ReturnersReauth.isInProgress());
        ReturnersReauth.reset();
        assertFalse(ReturnersReauth.isInProgress());
    }

    @Test
    void fenced_dropsTheRetryAfterAReset() {
        AtomicInteger runs = new AtomicInteger();
        Runnable retry =
                ReturnersReauth.fenced(ReturnersReauth.generation(), runs::incrementAndGet);
        ReturnersReauth.reset();
        retry.run();
        assertEquals(0, runs.get(), "a retry scheduled before a disconnect must not run after it");
    }
}
