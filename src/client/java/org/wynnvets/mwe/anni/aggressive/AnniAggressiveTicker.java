package org.wynnvets.mwe.anni.aggressive;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.mode.AnniMode;
import org.wynnvets.mwe.anni.mode.AnniModeManager;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.time.Instant;

/**
 * S5 — the "is aggressive mode currently active" flag, computed each tick.
 *
 * <p>Parallel to S4's {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker}'s
 * {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker#isOutlineSuppressionActive()}
 * — a cheap volatile read every consumer (zone-line renderer, scroll-spot
 * marker provider, alert dispatcher, ghosts prompt) tests at the top of
 * its hot path so the heavy work (line render, marker poll, snapshot
 * diff) short-circuits when aggressive mode is off.</p>
 *
 * <p><b>Gate (locked-in this session):</b> {@code mode == AGGRESSIVE ∧
 * window}. Window is the same T-2h..T+30m as S4. <i>No zone gate</i> — per
 * user, aggressive features are window-scoped, not location-scoped (zone
 * lines should render whenever you're aggressive + in-window so a Lutho
 * user can see them as they fly in). Per-feature toggles
 * ({@link org.wynnvets.config.VetsConfig#VETS_ANNI_ZONE_LINES} etc.) gate
 * individual consumers — this ticker doesn't aggregate them; consumers
 * check their own toggle in addition to {@link #isAggressiveActive()}.</p>
 *
 * <p>Computed strictly from the latest cached snapshot — no live
 * server pull, no I/O. Tick frequency is the client tick (~50ms);
 * boundary transitions are at most one tick late, which is well within
 * the slack the alerts and renderers need.</p>
 */
public final class AnniAggressiveTicker {

    /** Same as {@code AnniOutlineTicker} for cross-system consistency. */
    private static final long WINDOW_BEFORE_SECONDS = 2L * 60L * 60L;
    private static final long WINDOW_AFTER_SECONDS  = 30L * 60L;

    private static volatile boolean registered = false;
    private static volatile boolean aggressiveActive = false;

    private AnniAggressiveTicker() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        VetsLogger.debug("AnniAggressiveTicker registered");
    }

    /** Cheap public flag: {@code true} iff aggressive mode is on AND the
     *  snapshot's stamp is in the T-2h..T+30m window. Safe to call from any
     *  thread (volatile read). */
    public static boolean isAggressiveActive() {
        return aggressiveActive;
    }

    private static void tick() {
        try {
            aggressiveActive = computeGate();
        } catch (Exception e) {
            VetsLogger.debug("AnniAggressiveTicker.tick failed: {}", e.getMessage());
            aggressiveActive = false;
        }
    }

    private static boolean computeGate() {
        if (AnniModeManager.current() != AnniMode.AGGRESSIVE) return false;
        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        if (snapshot == null) return false;
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) return false;
        Long stamp = event.stampEpoch();
        if (stamp == null) return false;

        long now = Instant.now().getEpochSecond();
        long delta = stamp - now;
        return delta >= -WINDOW_AFTER_SECONDS && delta <= WINDOW_BEFORE_SECONDS;
    }
}
