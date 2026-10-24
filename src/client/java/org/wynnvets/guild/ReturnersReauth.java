package org.wynnvets.guild;

import java.util.concurrent.atomic.AtomicInteger;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.TickDelays;

/**
 * Re-sends the auth frame when the player joins Returners mid-session, so the server's session
 * picks up the member tier.
 *
 * <p>temporary-server fixes a session's tier when it handles the auth frame, and vetsmod sends
 * that frame only when the inbound socket (re)connects. A waitlister who joins Returners
 * mid-session therefore keeps the waitlist tier server-side until the next connection, and the
 * server refuses their world-queue {@code /g}, which goes out as a {@code queue} frame (the relay
 * then follows its echo with a red "Not delivered" line). dazebot re-resolves the tier from Discord
 * roles on every auth and grants the member role within its 2 to 5 minute role-sync loops, so
 * {@link #onJoinedReturners()} makes one attempt at the join and one {@link #RETRY_DELAY_MS}
 * later. Each sends only while {@link #shouldReauth} holds, which it stops doing once the tier
 * is {@code member}; temporary-server overwrites the session on a good frame.</p>
 *
 * <p>A failed frame ends the server's session. The immediate attempt is quiet about a
 * {@linkplain #isTransient transient} failure, which is no verdict on the key; the retry
 * reports any failure through {@link UnlockManager#onAuthFailure}'s usual line, so a session
 * the re-auth lost does not go unnoticed.</p>
 *
 * <p>A join also opens an in-progress window ({@link #isInProgress()}), which the invite gate
 * reads: it lasts until {@link #ACK_GRACE_MS} after the retry, re-armed when the retry actually
 * runs, since a client that drops ticks runs it late.</p>
 *
 * <p>Everything here runs on the client thread. {@link #reset()}, from
 * {@link GuildLifecycle#reset()}, bumps a generation that drops a retry scheduled before a
 * disconnect, as {@code DistributeRuns.fenced} does, and closes the window. The predicates, the
 * window and the fence touch no Minecraft or Wynntils class, for {@code ReturnersReauthTest}.</p>
 */
final class ReturnersReauth {

    /** How long after the join the retry runs: past the slow end of dazebot's 2 to 5 minute
     *  role-sync loops. */
    static final long RETRY_DELAY_MS = 330_000L;

    /** How long after the retry the join's re-auth still counts as in progress: time for the
     *  retry's ack to come back. */
    static final long ACK_GRACE_MS = 30_000L;

    private static final AtomicInteger generation = new AtomicInteger();

    /** When the latest Returners join's re-auth stops counting as in progress
     *  ({@link #isInProgress()}): {@link #RETRY_DELAY_MS} plus {@link #ACK_GRACE_MS} after the
     *  join. 0 when no join has opened one since the last {@link #reset()}. */
    private static volatile long inProgressUntilMs = 0L;

    private ReturnersReauth() {}

    /**
     * Whether an attempt should send the auth frame: there is a stored key the server has not
     * rejected, the inbound socket is up to carry it, and the session's tier is not already
     * {@code member}. An empty tier (no ok ack, or a failed one) counts as not member.
     */
    static boolean shouldReauth(
            boolean hasKey, boolean socketUp, String tier, boolean keyRejected) {
        return hasKey && socketUp && !keyRejected && !UnlockManager.TIER_MEMBER.equals(tier);
    }

    /**
     * Whether an auth failure's detail is temporary-server failing to reach or read dazebot's
     * introspection endpoint ({@code auth rejected: introspection unavailable: …},
     * {@code … HTTP <n>}, {@code … bad payload}, {@code … misconfigured (401)},
     * {@code … disabled at dazebot}) rather than dazebot's verdict on the key.
     */
    static boolean isTransient(String detail) {
        return detail != null && detail.startsWith("auth rejected: introspection");
    }

    /** The disconnect count, for {@link #fenced}. */
    static int generation() {
        return generation.get();
    }

    /** Wraps {@code body} so it runs only if no {@link #reset()} has come since
     *  {@code generation} was read from {@link #generation()}. */
    static Runnable fenced(int generation, Runnable body) {
        return () -> {
            if (generation == ReturnersReauth.generation.get()) {
                body.run();
            } else {
                VetsLogger.debug("ReturnersReauth: dropped a retry from before a disconnect");
            }
        };
    }

    /** Drops any scheduled retry and closes the in-progress window. Called from
     *  {@link GuildLifecycle#reset()} on disconnect. */
    static void reset() {
        generation.incrementAndGet();
        inProgressUntilMs = 0L;
    }

    /**
     * Whether a Returners join's re-auth is still in progress: the join came less than
     * {@link #RETRY_DELAY_MS} plus {@link #ACK_GRACE_MS} ago, so its retry, or the retry's ack, may
     * still raise the tier to {@code member}. A player who was in Returners at login, or whose
     * retry has come and gone, is not waiting for anything.
     */
    static boolean isInProgress() {
        return inProgressAt(System.currentTimeMillis(), inProgressUntilMs);
    }

    /** {@link #isInProgress()}'s test, pure: whether {@code nowMs} falls before the end of the
     *  window, {@code untilMs} (0 for none). */
    static boolean inProgressAt(long nowMs, long untilMs) {
        return nowMs < untilMs;
    }

    /** The end of the window a Returners join at {@code joinedAtMs} opens. */
    static long windowEnd(long joinedAtMs) {
        return joinedAtMs + RETRY_DELAY_MS + ACK_GRACE_MS;
    }

    /** Opens, or moves, the in-progress window so it ends at {@code untilMs}. */
    // Package-private for unit tests. See ReturnersReauthTest.
    static void openWindowUntil(long untilMs) {
        inProgressUntilMs = untilMs;
    }

    /** Called on the client thread by {@link GuildLifecycle#onGuildJoined()} when the guild
     *  joined is Returners: an attempt now, and the retry {@link #RETRY_DELAY_MS} later. */
    static void onJoinedReturners() {
        openWindowUntil(windowEnd(System.currentTimeMillis()));
        attempt(false);
        TickDelays.runAfter(
                RETRY_DELAY_MS,
                "Returners re-auth retry",
                fenced(
                        generation(),
                        () -> {
                            // Ticks can run late: end the window a grace after the retry itself.
                            openWindowUntil(System.currentTimeMillis() + ACK_GRACE_MS);
                            attempt(true);
                        }));
    }

    private static void attempt(boolean retry) {
        if (!GuildLifecycle.enteredWorld || (retry && !GuildStateManager.isReturners())) {
            return;
        }
        String tier = UnlockManager.currentTier();
        if (!shouldReauth(
                UnlockManager.hasStoredKey(),
                V1ApiManager.isInboundConnected(),
                tier,
                UnlockManager.keyRejectedByAuthAck())) {
            return;
        }
        VetsLogger.info(
                "Joined Returners with auth tier '{}'; re-sending the auth frame{}",
                tier,
                retry ? " (retry)" : "");
        UnlockManager.reauth(!retry);
    }
}
