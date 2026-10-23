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
 * server refuses their world-queue {@code /g}, which goes out as a {@code queue} frame
 * ({@code relay-echo-shows-server-refused-frames}). dazebot re-resolves the tier from Discord
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
 * <p>Everything here runs on the client thread. {@link #reset()}, from
 * {@link GuildLifecycle#reset()}, bumps a generation that drops a retry scheduled before a
 * disconnect, as {@code DistributeRuns.fenced} does. The predicates and the fence touch no
 * Minecraft or Wynntils class, for {@code ReturnersReauthTest}.</p>
 */
final class ReturnersReauth {

    /** How long after the join the retry runs: past the slow end of dazebot's 2 to 5 minute
     *  role-sync loops. */
    static final long RETRY_DELAY_MS = 330_000L;

    private static final AtomicInteger generation = new AtomicInteger();

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

    /** Drops any scheduled retry. Called from {@link GuildLifecycle#reset()} on disconnect. */
    static void reset() {
        generation.incrementAndGet();
    }

    /** Called on the client thread by {@link GuildLifecycle#onGuildJoined()} when the guild
     *  joined is Returners: an attempt now, and the retry {@link #RETRY_DELAY_MS} later. */
    static void onJoinedReturners() {
        attempt(false);
        TickDelays.runAfter(
                RETRY_DELAY_MS,
                "Returners re-auth retry",
                fenced(generation(), () -> attempt(true)));
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
