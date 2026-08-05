package org.wynnvets.mwe.anni.bossbar;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.BossEvent;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mixin.client.accessors.BossHealthOverlayAccessor;
import org.wynnvets.mwe.anni.mode.AnniMode;
import org.wynnvets.mwe.anni.mode.AnniModeManager;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.mwe.anni.zone.AnniZone;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Singleton driver for the synthetic vets-anni boss bar.
 *
 * <p>Per-tick this manager decides whether the bar should be on (mode
 * ∈ {PASSIVE, AGGRESSIVE}, snapshot has a future {@code stamp_epoch},
 * we're inside the activation window) and, if so, rebuilds its name
 * component via {@link VetsBossBarContentBuilder}. The synthetic
 * {@link LerpingBossEvent} lives in
 * {@link BossHealthOverlay#events} via {@link BossHealthOverlayAccessor}.</p>
 *
 * <p>Coexistence with vanilla / Wynncraft / Wynntils boss bars is
 * handled <b>on the render side</b>:
 * {@link org.wynnvets.mixin.client.BossHealthOverlayMixin} filters the
 * {@code events.values()} iteration in {@code BossHealthOverlay.render}
 * so only our UUID draws while {@link #isActive()} is true. We do
 * <b>not</b> cancel update packets — vanilla's {@code events.get(uuid).setName}
 * pattern would NPE if we had hidden entries for bars the server still
 * thinks exist (this was the cause of the 2026-06-16 testing crash).
 * Leaving the update path alone keeps Wynntils' tracking + the streamer-
 * mode mutex coherent without any let-through hack.</p>
 *
 * <p>T-20s deactivation gates (parent plan §"Risks"):
 * <ol>
 *   <li>The content builder returns {@code null} at {@code <=20s};
 *       this drives the regular path.</li>
 *   <li>This tick-time watchdog independently force-deactivates when
 *       {@code stamp_epoch - now <= 20} — covers a stale builder or
 *       snapshot-clock skew. Also covers kick-into-queue at T-20
 *       since the watchdog re-checks every client tick (~50 ms).</li>
 * </ol>
 * Plus the existing {@code AnniWindowWatcher} which resets the mode to
 * silent at T+30 m — a slower safety net.</p>
 */
public final class VetsBossBarManager {

    /** Activation gate — the bar shows when {@code secondsUntilAnni
     *  ≤ 90 m} OR the player is inside the anni zone. Outside both,
     *  the bar stays hidden even in passive/aggressive mode so users
     *  going about their day hours before anni aren't ambushed by a
     *  perpetual boss bar. */
    private static final long ANNI_WINDOW_SECONDS = 90L * 60L; // 90 min

    /** Progress nominally fills to 100% at T-100m and drains linearly
     *  to 0% at T-20s — a 100-minute progress window. Decoupled from
     *  {@link #ANNI_WINDOW_SECONDS} (the activation gate) so the
     *  {@link BossEvent.BossBarOverlay#NOTCHED_10} overlay's ten segment
     *  dividers land exactly every 10 minutes of wall-clock time. At
     *  activation (T-90m or zone-entry) the bar already reads
     *  {@code 90% ≈ 9-of-10 sections filled} — the spec-requested
     *  "90 mins = 9 notches" mapping. Each subsequent 10-minute step
     *  drops one section, giving a reliable readout of how much time
     *  is left without the user having to read the countdown text. */
    private static final long PROGRESS_FULL_AT_SECONDS = 100L * 60L; // 100 min

    /** Hard T-20s gate — independent of the content builder. */
    private static final long DROP_DEAD_SECONDS_BEFORE_ANNI = 20L;

    /** Failsafe: deactivate after this many ms regardless of mode. */
    private static final long FAILSAFE_DEACTIVATE_MS = (long) (2.5 * 60L * 60L * 1000L);

    /** Deterministic UUID so collisions across mod reloads or
     *  hot-restarts replace cleanly rather than accumulating. */
    private static final UUID BAR_UUID = UUID.nameUUIDFromBytes(
            "vetsmod-anni-bossbar".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static volatile boolean registered = false;
    private static volatile boolean active = false;
    private static volatile long activatedAtMs = 0L;


    private static final LerpingBossEvent bossEvent = new LerpingBossEvent(
            BAR_UUID,
            Component.literal(""),
            1.0f,
            BossEvent.BossBarColor.PURPLE,
            // NOTCHED_10 paired with the 100-minute progress window above:
            // 10 segments × 10 minutes each = a notch every 10 minutes of
            // real time. At activation (T-90m) the bar reads 90% ≈ 9
            // notches filled, matching the user-asked "90 mins = 9 notches"
            // readability target. Closest vanilla offers — there is no
            // NOTCHED_9.
            BossEvent.BossBarOverlay.NOTCHED_10,
            false, false, false);

    private VetsBossBarManager() {
    }

    /** Idempotent registration. Wires the per-tick driver +
     *  {@link FlashTracker#register()} (so there's exactly one anni
     *  snapshot listener for the boss-bar subsystem). */
    public static void register() {
        if (registered) return;
        registered = true;
        FlashTracker.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        VetsLogger.debug("VetsBossBarManager registered");
    }

    /** Whether the synthetic bar is currently rendered. Read by
     *  {@link org.wynnvets.mixin.client.BossHealthOverlayMixin} to gate
     *  packet swallowing — when false, vanilla boss bars pass through
     *  unmolested. */
    public static boolean isActive() {
        return active;
    }

    /** The synthetic bar's UUID — consumed by
     *  {@link org.wynnvets.mixin.client.BossHealthOverlayMixin} to
     *  identify which entry in {@code BossHealthOverlay#events} to
     *  let through the render filter. */
    public static UUID barUuid() {
        return BAR_UUID;
    }

    // ── Per-tick driver ────────────────────────────────────────────────

    private static void tick() {
        try {
            tickInner();
            FlashTracker.tick();
        } catch (Exception e) {
            // Never let a per-tick exception escape — the boss-bar
            // pipeline must never break the main render loop.
            VetsLogger.debug("VetsBossBarManager.tick failed: {}", e.getMessage());
        }
    }

    private static void tickInner() {
        // Mode gate. SILENT is a strict no-op (per spec).
        AnniMode mode = AnniModeManager.current();
        if (mode == AnniMode.SILENT) {
            if (active) deactivate();
            return;
        }

        // Master kill-switch — even passive/aggressive respects this.
        if (!VetsConfig.get(VetsConfig.VETS_ANNI_BOSSBAR_ENABLED)) {
            if (active) deactivate();
            return;
        }

        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        if (snapshot == null) {
            if (active) deactivate();
            return;
        }
        AnniSnapshot.Event event = snapshot.event();
        Long stampEpoch = event != null ? event.stampEpoch() : null;
        if (stampEpoch == null) {
            if (active) deactivate();
            return;
        }
        long secondsUntilAnni = stampEpoch - Instant.now().getEpochSecond();

        // Gate 2 of 3: hard wall-clock T-20 watchdog, independent of
        // the content builder.
        if (secondsUntilAnni <= DROP_DEAD_SECONDS_BEFORE_ANNI) {
            if (active) deactivate();
            return;
        }

        // Failsafe — protects against a stuck snapshot keeping the bar
        // up indefinitely.
        if (active && System.currentTimeMillis() - activatedAtMs > FAILSAFE_DEACTIVATE_MS) {
            VetsLogger.debug("VetsBossBarManager: 2.5h failsafe → deactivate");
            deactivate();
            return;
        }

        double playerX = 0.0, playerZ = 0.0;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc != null ? mc.player : null;
        if (player != null) {
            playerX = player.getX();
            playerZ = player.getZ();
        }

        // Activation gate: show the bar only when we're in the anni
        // window (≤90m) OR the player has walked into the anni zone.
        // Outside both, even passive/aggressive stays quiet — no
        // perpetual bar hours before anni.
        boolean inWindow = secondsUntilAnni <= ANNI_WINDOW_SECONDS;
        boolean inZone = player != null && AnniZone.isInZone(playerX, playerZ);
        if (!inWindow && !inZone) {
            if (active) deactivate();
            return;
        }
        MutableComponent name = VetsBossBarContentBuilder.build(
                snapshot, secondsUntilAnni, playerX, playerZ);

        // Gate 1 of 3: builder said null → deactivate.
        if (name == null) {
            if (active) deactivate();
            return;
        }

        // Re-put on every tick: vanilla clears BossHealthOverlay#events
        // on world transfer / disconnect, but our static `active` flag
        // survives. Without the unconditional re-put the synthetic bar
        // silently vanishes after a world handoff, the render filter
        // short-circuits to emptyList, and the user sees no bars at all
        // until something flips `active` to false (e.g. config toggle)
        // and the next tick takes the activate branch. Re-putting is
        // self-healing against every external removal vector.
        activate();
        bossEvent.setName(name);
        bossEvent.setProgress(progressFor(secondsUntilAnni));
        bossEvent.setColor(VetsBossBarContentBuilder.colorFor(snapshot));
    }

    // ── Activate / deactivate ──────────────────────────────────────────

    private static void activate() {
        Map<UUID, LerpingBossEvent> events = events();
        if (events == null) return;
        // Insert our synthetic bar; do NOT clear pre-existing entries.
        // Vanilla's render is filtered by BossHealthOverlayMixin to draw
        // only our UUID while active, so Wynncraft's bars stay alive in
        // the map (and stay consistent with the server's view) for the
        // duration. This avoids the NPE crash the earlier "events.clear()
        // at activate" design caused — vanilla's update path does
        // `events.get(uuid).setName(...)` and dereferences null on bars
        // we had wiped.
        events.put(BAR_UUID, bossEvent);
        // First-activation-only bookkeeping. Re-puts on subsequent ticks
        // must NOT bump activatedAtMs or the 2.5h failsafe would never
        // fire.
        if (!active) {
            active = true;
            activatedAtMs = System.currentTimeMillis();
            VetsLogger.debug("VetsBossBarManager: activated");
        }
    }

    private static void deactivate() {
        Map<UUID, LerpingBossEvent> events = events();
        if (events != null) {
            events.remove(BAR_UUID);
        }
        active = false;
        activatedAtMs = 0L;
        FlashTracker.reset();
        VetsLogger.debug("VetsBossBarManager: deactivated");
    }

    private static Map<UUID, LerpingBossEvent> events() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return null;
        BossHealthOverlay overlay = mc.gui.getBossOverlay();
        if (overlay == null) return null;
        try {
            return ((BossHealthOverlayAccessor) (Object) overlay).getEvents();
        } catch (ClassCastException e) {
            VetsLogger.warn("BossHealthOverlayAccessor mixin not applied? {}", e.getMessage());
            return null;
        }
    }

    /** One linear ramp across {@link #PROGRESS_FULL_AT_SECONDS} less
     *  {@link #DROP_DEAD_SECONDS_BEFORE_ANNI} — 100% at T-100m, draining
     *  linearly to 0% at the T-20s deactivation wall. No plateau, and
     *  {@link #ANNI_WINDOW_SECONDS} plays no part here. Activation is
     *  gated separately at T-90m or zone entry, so the bar already reads
     *  {@code ~90%} the moment it appears; the {@code p > 1f} clamp is
     *  only reachable by entering the zone earlier than T-100m. */
    private static float progressFor(long secondsUntilAnni) {
        long window = PROGRESS_FULL_AT_SECONDS - DROP_DEAD_SECONDS_BEFORE_ANNI;
        long usable = Math.max(0L, secondsUntilAnni - DROP_DEAD_SECONDS_BEFORE_ANNI);
        if (window <= 0) return 0f;
        float p = (float) usable / (float) window;
        if (p < 0f) return 0f;
        if (p > 1f) return 1f;
        return p;
    }

}
