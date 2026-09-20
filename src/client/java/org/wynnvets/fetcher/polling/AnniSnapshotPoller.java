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
 * <p>The {@code anni_state} server push is the primary delivery channel
 * for snapshot updates. In practice it goes silent for many board edits
 * (own RSVP/role changes, organiser edits propagated to affected players)
 * and recovery requires either a manual {@code /wv anni} or a config
 * toggle. This poller fires an {@link AnniQueryClient#query()} every
 * {@value #POLL_INTERVAL_SECONDS} seconds while the player is inside the
 * anni window (T-90m → anni). Outside the window the poll is a no-op —
 * users running idle hours before anni don't need a 30 s heartbeat.</p>
 *
 * <p><b>Alone among the six schedules, this one waits a full period before
 * its first tick.</b> It passes {@value #POLL_INTERVAL_SECONDS} as
 * {@code PollingService}'s {@code initialDelay} as well as its
 * {@code period}, where the other five pass {@code 0}; so <em>this</em> poller
 * does not tick for the first {@value #POLL_INTERVAL_SECONDS} seconds of a
 * session, while the other five fire immediately. The
 * cold-start pull that covers that gap is
 * {@link org.wynnvets.fetcher.ondemand.StampFetcher}'s, not this
 * class's.</p>
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
 * sides.</p>
 *
 * <p>Cost analysis: 30 s × ~90 minutes = 180 query frames per anni
 * window, single-flight queued. Each frame is &lt;100 bytes on the
 * inbound socket. Negligible vs. the existing party-observation cadence.</p>
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

    private AnniSnapshotPoller() {}

    /** Starts the poll. Idempotent. */
    public static void start() {
        if (SERVICE.start()) {
            VetsLogger.debug("AnniSnapshotPoller started");
        }
    }

    private static void tick() {
        try {
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
