package org.wynnvets.mwe.anni.aggressive;

import java.time.Instant;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.mode.AnniMode;
import org.wynnvets.mwe.anni.mode.AnniModeManager;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.mwe.anni.state.AnniWindows;

/**
 * S5 — the "is aggressive mode currently active" flag, computed each tick.
 *
 * <p>Parallel to S4's {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker}'s
 * {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker#isOutlineSuppressionActive()}
 * — a cheap volatile read consumers test at the top of their hot path so the
 * heavy work (line render, marker poll, snapshot diff) short-circuits when
 * aggressive mode is off. See {@link #isAggressiveActive()} for the reader
 * census.</p>
 *
 * <p>⚠️ <b>The parallel stops at the failure mode, and the two tickers are
 * opposites there.</b> This one fails <em>closed</em>: its catch assigns
 * {@code aggressiveActive = false}, so a tick that throws turns aggressive
 * surfaces off. {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker AnniOutlineTicker}'s catch assigns nothing, so it fails
 * <em>stale</em> — a throw with the gate open leaves its two behavioural
 * readers, {@code EntityOutlineColorMixin} and {@code NametagMixin},
 * believing anni rendering is still on. Do not reason from one to the other.
 * {@code vetsmod_rendering.md} §6 owns the contrast.</p>
 *
 * <p><b>Gate (locked-in this session):</b> {@code mode == AGGRESSIVE ∧
 * window}. Window is the same T-2h..T+30m as S4, via
 * {@link org.wynnvets.mwe.anni.state.AnniWindows#inHotWindow(long)}.
 * <i>No zone gate</i> — per user, aggressive features are window-scoped, not
 * location-scoped. ⚠️ That does <b>not</b> mean the zone lines are visible
 * from Lutho: {@link org.wynnvets.mwe.anni.zone.AnniZoneLineRenderer AnniZoneLineRenderer} culls any disc centre more than 200
 * blocks away horizontally, so with the 48-block disc radius a ring's near
 * edge appears at roughly 152 blocks — on final approach, not on the flight
 * in. The window gate is what was decided; the draw distance is a separate
 * limit that survives it. Per-feature toggles
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

    private static volatile boolean registered = false;
    private static volatile boolean aggressiveActive = false;

    private AnniAggressiveTicker() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        VetsLogger.debug("AnniAggressiveTicker registered");
    }

    /**
     * Cheap public flag: {@code true} iff aggressive mode is on AND the
     * snapshot's stamp is in the T-2h..T+30m window. Safe to call from any
     * thread (volatile read).
     *
     * <p><b>Four behavioural readers</b> —
     * {@link org.wynnvets.mwe.anni.zone.AnniZoneLineRenderer AnniZoneLineRenderer},
     * {@link org.wynnvets.mwe.anni.waypoint.ScrollSpotMarkerProvider ScrollSpotMarkerProvider},
     * {@link AggressiveAlertDispatcher} and {@link GhostsPromptHandler} — each
     * testing it at the top of its hot path. <b>Two diagnostic reads</b> also
     * report it: {@link org.wynnvets.debug.DebugCommands DebugCommands}'
     * zone-lines dump ({@code /wv debug trigger zoneLinesDump}) and
     * {@link GhostsPromptHandler}'s own prompt dump. Six call sites in all,
     * which is the count the doc-count manifest pins.</p>
     */
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
        return AnniWindows.inHotWindow(delta);
    }
}
