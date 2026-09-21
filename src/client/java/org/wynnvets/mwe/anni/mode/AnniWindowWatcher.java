package org.wynnvets.mwe.anni.mode;

import java.time.Instant;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.mwe.anni.state.AnniWindows;

/**
 * Resets the anni mode to {@link AnniModeManager#preferredMode()} once
 * the anni window closes (T+30 min after the announced stamp).
 *
 * <p>Subscribes to {@link AnniSnapshotCache}; every push or pull that
 * lands a snapshot checks whether the window has closed. temporary-server
 * pushes {@code anni_state} frames every ~10 s in its own hot window and
 * ~5 min outside it, and vetsmod's
 * {@link org.wynnvets.fetcher.polling.AnniSnapshotPoller AnniSnapshotPoller} adds a
 * 30 s query while inside the 90-minute bar window — so at the T+30 m edge the
 * reset lands on whichever push arrives first, typically within ~10 s. The bar
 * window has closed by then; this is the slower safety net.</p>
 *
 * <p>Delegates the target selection to
 * {@link AnniModeManager#preferredMode()} rather than hard-coding
 * SILENT — this preserves a user's explicit pick across the window
 * boundary while still returning still-defaulted users to the correct
 * eligibility default.</p>
 *
 * <p>Anchor tracking — once the anni starts, vets-anni emits
 * {@code stamp_epoch: null} (per the snapshot contract: "null when past
 * /unknown"). We therefore cache the most recent non-null stamp and
 * key the window-close check off that, otherwise the renderer would
 * lose its anchor the moment the anni began.</p>
 *
 * <p>One-shot per anni cycle: {@link #lastKnownStamp} is cleared as soon as
 * {@link AnniWindows#hotWindowClosed} returns true, <b>whether or not a
 * transition was attempted and whether or not it succeeded</b> — the clear sits
 * after the {@code if}, not inside it. So a reset that {@code transitionTo}
 * refuses (the {@code /stream} mutex) is not retried on the next snapshot; the
 * anchor is already gone. Nothing fires again until the NEXT anni is announced
 * and a non-null stamp is observed.</p>
 *
 * <p>Idempotent registration via {@link #register()} — call once at
 * client init.</p>
 */
public final class AnniWindowWatcher {

    private static volatile boolean registered = false;
    private static volatile Long lastKnownStamp = null;

    private AnniWindowWatcher() {}

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
        if (!AnniWindows.hotWindowClosed(anchor, now)) return;

        AnniMode target = AnniModeManager.preferredMode();
        if (AnniMode.fromConfig() != target) {
            AnniModeManager.transitionTo(target, AnniModeManager.Source.AUTO_WINDOW_CLOSE);
        }
        // Clear so we don't keep checking against this stamp until a
        // new one is observed (next anni's announcement).
        lastKnownStamp = null;
    }
}
