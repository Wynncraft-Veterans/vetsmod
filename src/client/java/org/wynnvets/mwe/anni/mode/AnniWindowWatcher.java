package org.wynnvets.mwe.anni.mode;

import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.time.Instant;

/**
 * Resets {@link VetsConfig#VETS_ANNI_MODE} to {@code silent} once the
 * anni window closes (T+30 min after the announced stamp).
 *
 * <p>Subscribes to {@link AnniSnapshotCache}; every push or pull that
 * lands a snapshot checks whether the window has closed. The poller
 * pushes every ~10 s during the hot window (T-2h to T+30 m), so the
 * reset typically fires within ~10 s of the window edge.</p>
 *
 * <p>Anchor tracking — once the anni starts, vets-anni emits
 * {@code stamp_epoch: null} (per the snapshot contract: "null when past
 * /unknown"). We therefore cache the most recent non-null stamp and
 * key the window-close check off that, otherwise the renderer would
 * lose its anchor the moment the anni began.</p>
 *
 * <p>One-shot: once the reset fires, {@link #lastKnownStamp} is cleared
 * so we don't repeatedly fire it on every subsequent snapshot until
 * the NEXT anni is announced and we observe its stamp.</p>
 *
 * <p>Idempotent registration via {@link #register()} — call once at
 * client init.</p>
 */
public final class AnniWindowWatcher {

    /** Seconds after the announced stamp at which the hot window
     *  closes — per spec §"WITHIN 2h of an anni, OR within 30 mins
     *  after an anni". */
    private static final long WINDOW_CLOSE_AFTER_STAMP_SECS = 30L * 60L;

    private static volatile boolean registered = false;
    private static volatile Long lastKnownStamp = null;

    private AnniWindowWatcher() {
    }

    /** Wire the snapshot-cache listener. Safe to call repeatedly. */
    public static void register() {
        if (registered) return;
        registered = true;
        AnniSnapshotCache.addListener(AnniWindowWatcher::onSnapshot);
        VetsLogger.debug("AnniWindowWatcher registered");
    }

    private static void onSnapshot(AnniSnapshot snapshot) {
        if (snapshot == null) return;
        AnniSnapshot.Event event = snapshot.event();
        Long stamp = event != null ? event.stampEpoch() : null;
        if (stamp != null) {
            lastKnownStamp = stamp;
        }

        Long anchor = lastKnownStamp;
        if (anchor == null) return;
        long now = Instant.now().getEpochSecond();
        long windowEnd = anchor + WINDOW_CLOSE_AFTER_STAMP_SECS;
        if (now <= windowEnd) return;

        String mode = VetsConfig.getString(VetsConfig.VETS_ANNI_MODE);
        if (mode != null && !"silent".equals(mode)) {
            VetsConfig.setString(VetsConfig.VETS_ANNI_MODE, "silent");
            VetsLogger.debug("Anni mode reset to silent (T+30m window closed)");
        }
        // Clear so we don't keep checking against this stamp until a
        // new one is observed (next anni's announcement).
        lastKnownStamp = null;
    }
}
