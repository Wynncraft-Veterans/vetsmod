package org.wynnvets.fetcher.polling;

import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 * <p>Reuses the same 90-minute window gate as
 * {@link org.wynnvets.mwe.anni.bossbar.VetsBossBarManager#isActive()};
 * by intent every surface that "wakes up" inside the window is kept
 * fresh by the same poll cadence.</p>
 *
 * <p>Cost analysis: 30 s × ~90 minutes = 180 query frames per anni
 * window, single-flight queued. Each frame is &lt;100 bytes on the
 * inbound socket. Negligible vs. the existing party-observation cadence.</p>
 */
public final class AnniSnapshotPoller {

    /** Match {@link org.wynnvets.mwe.anni.bossbar.VetsBossBarManager#ANNI_WINDOW_SECONDS
     *  VetsBossBarManager#ANNI_WINDOW_SECONDS}. Polling
     *  outside this window is wasted work — no surface consumes the
     *  snapshot during the long idle gap between anni events. */
    private static final long ANNI_WINDOW_SECONDS = 90L * 60L;

    /** User-stated SLA was "30 secs MAX during the anni window". */
    private static final int POLL_INTERVAL_SECONDS = 30;

    private static ScheduledExecutorService scheduler;
    private static boolean running = false;

    private AnniSnapshotPoller() {
    }

    /** Starts the poll. Idempotent. */
    public static void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VetsMod-AnniSnapshotPoller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                AnniSnapshotPoller::tick,
                POLL_INTERVAL_SECONDS,
                POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        VetsLogger.debug("AnniSnapshotPoller started");
    }

    /** Stops the poll. Draining matches {@link AnniStampPoller#stop()}. */
    public static void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        running = false;
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
        return secondsUntilAnni > 0 && secondsUntilAnni <= ANNI_WINDOW_SECONDS;
    }
}
