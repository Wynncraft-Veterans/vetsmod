package org.wynnvets.fetcher.polling;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.mwe.anni.state.AnniWindows;

/**
 * Belt-and-braces snapshot refresher for the anni window.
 *
 * <p>The {@code anni_state} server push is meant to be the primary delivery
 * channel for snapshot updates. The server sends it only to an authenticated outbound
 * socket, which vetsmod now opens only for a confirmed member tier against a server
 * advertising {@code chat_tiers: "all"}; before that it never arrived, the likelier
 * reason it was seen to go silent for board edits. A player with no key, or tier
 * {@code other}, still gets no push. Outside the push, this poller's ticks and the
 * {@code /wv debug tree anni} simulation commands, the cache changes only on a pull: the
 * post-connect re-pull, {@code StampFetcher}'s pull while the cache is empty, and the
 * refresh after an in-game RSVP. This poller fires an
 * {@link AnniQueryClient#query()} every
 * {@value #POLL_INTERVAL_SECONDS} seconds while the player is inside the
 * anni window (T-90m → anni). Outside the window the poll is a no-op —
 * users running idle hours before anni don't need a 30 s heartbeat.</p>
 *
 * <p><b>Alone among the seven schedules, this one waits a full period before
 * its first tick.</b> It passes {@value #POLL_INTERVAL_SECONDS} as
 * {@code PollingService}'s {@code initialDelay} as well as its
 * {@code period}, where the other six pass {@code 0}; so <em>this</em> poller
 * does not tick for the first {@value #POLL_INTERVAL_SECONDS} seconds of a
 * session, while the other six fire immediately. That delay costs
 * nothing at cold start: {@code tick()} queries only once a snapshot
 * with an in-window stamp is already cached, so this poller never fills
 * a cold cache. Cold-start pulls come from elsewhere, among them {@link
 * org.wynnvets.mwe.anni.network.AnniWsHandler AnniWsHandler}'s
 * post-connect re-pull and {@link org.wynnvets.fetcher.ondemand.StampFetcher
 * StampFetcher}'s cold-cache paths ({@code vetsmod_mwe_anni.md}
 * §"Snapshot pipeline").</p>
 *
 * <p>Reuses the same 90-minute window as
 * {@link org.wynnvets.mwe.anni.bossbar.VetsBossBarManager VetsBossBarManager}'s
 * activation gate — one constant,
 * {@link org.wynnvets.mwe.anni.state.AnniWindows#BAR_WINDOW_SECONDS
 * AnniWindows#BAR_WINDOW_SECONDS} — so that by intent every surface which
 * "wakes up" inside the window is kept fresh by the same poll cadence. The
 * gate itself is in that class's private {@code tickInner}, <b>not</b> in its
 * {@code isActive()}, which only reports the resulting flag; an earlier
 * version of this sentence linked the latter, and a live link to the wrong
 * member is something {@code -Xdoclint:reference} cannot catch.</p>
 *
 * <p>The two share that number and <b>not</b> their floor, which the old
 * "match VetsBossBarManager" comment never said. This poller stops at
 * {@code secondsUntilAnni > 0}; the bar stops twenty seconds earlier, at its
 * {@code DROP_DEAD_SECONDS_BEFORE_ANNI} hard return. So the last twenty seconds
 * before an anni are polled but not drawn, and that is deliberate on both
 * sides. The bar's ceiling is not always that number either: its gate ORs the
 * window test with the player standing in
 * {@link org.wynnvets.mwe.anni.zone.AnniZone AnniZone}, so a player in the zone
 * before T-90m can have a bar that this poller does not refresh
 * ({@code vetsmod_mwe_anni.md} §"Anni time windows").</p>
 *
 * <p>Cost: up to 180 queries per anni window (one every 30 s for 90 minutes), each a
 * small request frame whose reply future is queued FIFO in {@link AnniQueryClient}, not
 * coalesced. A reply carries a full snapshot, or none when temporary-server has none to
 * give. Per temporary-server, a reply comes from its short-lived
 * snapshot cache while that is fresh, and from a live vets-anni fetch otherwise
 * (v1_protocol.md §1.11).</p>
 *
 * <p>Because its gate reads the cached stamp, a debug-injected in-window snapshot would arm
 * this poller, and the next reply carrying a snapshot would overwrite it. So the debug harness
 * holds the scheduled pull while its injected snapshot stands ({@link #setDebugHold}). Other
 * pulls, such as the post-connect re-pull or the refresh after an RSVP, still replace it, and
 * leave the hold standing.</p>
 */
public final class AnniSnapshotPoller {

    /** User-stated SLA was "30 secs MAX during the anni window". */
    private static final int POLL_INTERVAL_SECONDS = 30;

    private static final PollingService SERVICE =
            new PollingService(
                    "VetsMod-AnniSnapshotPoller",
                    AnniSnapshotPoller::tick,
                    POLL_INTERVAL_SECONDS,
                    POLL_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);

    /** Set after the debug harness writes the cache by hand, and cleared by its
     *  {@code snapshot clear}, {@code snapshot refresh} and {@code guess}, and by
     *  {@code /wv debug false}, so turning debug mode back on doesn't bring a hold back.
     *  {@link #tick()} also ignores it while debug mode is off. */
    private static volatile boolean debugHold = false;

    private AnniSnapshotPoller() {}

    /** Holds or releases the scheduled pull for a debug-injected snapshot; see
     *  {@link #debugHold}. For the debug harness and {@code /wv debug false} only. */
    public static void setDebugHold(boolean hold) {
        debugHold = hold;
    }

    /** Starts the poll. Idempotent. */
    public static void start() {
        if (SERVICE.start()) {
            VetsLogger.debug("AnniSnapshotPoller started");
        }
    }

    private static void tick() {
        try {
            if (debugHold && VetsLogger.isDebugEnabled()) {
                return;
            }
            if (!inAnniWindow()) {
                return;
            }
            AnniQueryClient.query();
        } catch (Exception e) {
            VetsLogger.debug("AnniSnapshotPoller tick failed: {}", e.getMessage());
        }
    }

    /** True while the current snapshot's stamp is in the future AND
     *  within the 90-minute window. False otherwise (cold cache, past
     *  stamp, or pre-window quiet hours). */
    private static boolean inAnniWindow() {
        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        if (snapshot == null) {
            return false;
        }
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) {
            return false;
        }
        Long stamp = event.stampEpoch();
        if (stamp == null) {
            return false;
        }
        long secondsUntilAnni = stamp - Instant.now().getEpochSecond();
        return secondsUntilAnni > 0 && secondsUntilAnni <= AnniWindows.BAR_WINDOW_SECONDS;
    }
}
