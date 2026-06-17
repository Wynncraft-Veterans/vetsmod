package org.wynnvets.mwe.anni.bossbar;

import com.wynntils.core.components.Models;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

/**
 * Per-field change detection + bold/underline pulse state for the
 * vets-anni passive-mode boss bar.
 *
 * <p>Spec §3.1.1: "If something changes (someone is assigned to a
 * party, their role changes, their party gets a world, whatever), play
 * sound (whatever wynntils does when someone's name gets mentioned,
 * just play it twice) and flash the thing that changed. To do this,
 * toggle it between {@code &l} and {@code &n&l}." World changes get
 * the additional "flash indefinitely until the user is on that world"
 * — that's modelled as a live mismatch predicate, not a one-shot
 * timed flash window.</p>
 *
 * <p>Two ping/flash models:</p>
 * <ul>
 *   <li><b>Role / party / RSVP</b> — diff on snapshot push, latch a
 *       timed flash window for {@code vetsAnniFlashIntensity} ms, ping
 *       2× per change. First observation is skipped (login-with-
 *       existing-state shouldn't bing on every reconnect).</li>
 *   <li><b>World</b> — flash is live state ({@code party.world != null
 *       AND user is not on it}); the user walking on/off the right
 *       world toggles it silently. The ping fires on a different
 *       trigger: the snapshot's {@code party.world} value changes
 *       (assignment / reassignment). Position movement never bings.
 *       First observation is skipped (no bing on login).</li>
 * </ul>
 *
 * <p>Singleton listener on {@link AnniSnapshotCache}. Snapshot diffs
 * fire on the WS reader thread; the listener bounces onto the client
 * thread before mutating state. {@link #tick()} runs per client tick
 * via the boss-bar manager's tick loop — no separate tick wiring.</p>
 */
public final class FlashTracker {

    /** Pulse half-period — 250 ms → 2 Hz. Slow enough to read but
     *  obviously animated. */
    private static final long PHASE_TOGGLE_MS = 250L;

    /** Number of "name-ping"-style sound plays to queue on a change. */
    private static final int SOUNDS_PER_CHANGE = 2;
    /** Cap on the in-flight ping queue. Multiple simultaneous triggers
     *  (e.g. snapshot reassignment that also causes mismatch) collapse
     *  to a single 2-ping burst rather than 4+ sounds. */
    private static final int SOUNDS_CAP = 2;
    /** Delay between the two sound plays so the second isn't masked. */
    private static final long SOUND_INTER_DELAY_MS = 110L;

    // Per-field timed flashes for role/party/rsvp.
    private static volatile long flashRoleUntil  = 0L;
    private static volatile long flashPartyUntil = 0L;
    private static volatile long flashRsvpUntil  = 0L;

    // Live world-mismatch state, recomputed in tick(). True iff a party
    // world is set on the snapshot AND the player is not currently on
    // it. Drives styleFor("world"). Position-driven — no ping here.
    private static volatile boolean worldMismatch = false;

    // Last-seen snapshot values for the diff fields. null = "current
    // value is null" — distinct from "never observed", which is what
    // the *Observed sentinels track. The first observation in a session
    // is silent; every subsequent transition (including null → set and
    // set → null) flashes and pings.
    private static volatile String lastRole  = null;
    private static volatile Integer lastParty = null;
    private static volatile String lastRsvp  = null;
    private static volatile String lastWorld = null;
    private static volatile boolean roleObserved  = false;
    private static volatile boolean partyObserved = false;
    private static volatile boolean rsvpObserved  = false;
    private static volatile boolean worldObserved = false;

    // Phase bit toggles every PHASE_TOGGLE_MS in tick().
    private static long phaseToggleAt = 0L;
    private static boolean phaseOn = false;

    // Pending name-ping sounds to play on subsequent ticks.
    private static int pendingSounds = 0;
    private static long nextSoundAt = 0L;

    private static volatile boolean registered = false;

    private FlashTracker() {
    }

    /** Idempotent registration — subscribes to {@link AnniSnapshotCache}.
     *  Tick wiring lives in {@link VetsBossBarManager#register()} so
     *  there's exactly one {@code END_CLIENT_TICK} handler in the
     *  boss-bar subsystem. */
    public static void register() {
        if (registered) return;
        registered = true;
        AnniSnapshotCache.addListener(FlashTracker::onSnapshotChanged);
        VetsLogger.debug("FlashTracker registered");
    }

    /** Per-tick housekeeping — called by {@link VetsBossBarManager#tick()}.
     *  Drives the phase bit, the live world-mismatch state, and drains
     *  the pending sound queue. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now >= phaseToggleAt) {
            phaseOn = !phaseOn;
            phaseToggleAt = now + PHASE_TOGGLE_MS;
        }

        updateWorldMismatch();

        if (pendingSounds > 0 && now >= nextSoundAt) {
            playPingSound();
            pendingSounds--;
            nextSoundAt = now + SOUND_INTER_DELAY_MS;
        }
    }

    /** Whether {@code fieldKey} is currently flashing. Used by
     *  {@link VetsBossBarContentBuilder} to decide whether the pulse
     *  decoration should apply. */
    public static boolean isFlashing(String fieldKey) {
        long now = System.currentTimeMillis();
        switch (fieldKey) {
            case "role":  return flashRoleUntil > now;
            case "party": return flashPartyUntil > now;
            case "world": return worldMismatch;
            case "rsvp":  return flashRsvpUntil > now;
            default:      return false;
        }
    }

    /** Returns {@code base} with {@code .withUnderlined(true)} when
     *  {@code fieldKey} is flashing AND the pulse phase bit is high.
     *  The spec's "{@code &l ↔ &n&l}" alternation assumes the caller
     *  already set bold on {@code base}; this only adds the underline. */
    public static Style styleFor(String fieldKey, Style base) {
        if (!isFlashing(fieldKey) || !phaseOn) return base;
        return base.withUnderlined(true);
    }

    /** Debug entry — force the named field into its flash window.
     *  For world, sets the mismatch flag directly (cleared on the next
     *  tick if the live state disagrees). */
    public static void forceFlash(String fieldKey) {
        long until = System.currentTimeMillis() + flashDurationMs();
        switch (fieldKey) {
            case "role":  flashRoleUntil  = until; break;
            case "party": flashPartyUntil = until; break;
            case "world":
                // Visible-for-one-tick override; the real worldMismatch
                // recomputes in tick() next frame.
                worldMismatch = true;
                break;
            case "rsvp":  flashRsvpUntil  = until; break;
            default:
                VetsLogger.debug("FlashTracker.forceFlash: unknown field {}", fieldKey);
                return;
        }
        queuePingSounds();
    }

    /** Clear all flash state — called on mode-switch-to-silent and on
     *  boss-bar deactivation so the next activation starts clean. */
    public static void reset() {
        flashRoleUntil  = 0L;
        flashPartyUntil = 0L;
        flashRsvpUntil  = 0L;
        worldMismatch = false;
        lastRole = null;
        lastParty = null;
        lastRsvp = null;
        lastWorld = null;
        roleObserved = false;
        partyObserved = false;
        rsvpObserved = false;
        worldObserved = false;
        pendingSounds = 0;
    }

    // ── Internals ───────────────────────────────────────────────────────

    /** Snapshot listener — invoked on the WS reader thread. Bounces
     *  the diff onto the client thread. */
    private static void onSnapshotChanged(AnniSnapshot snapshot) {
        if (snapshot == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> applyDiff(snapshot));
    }

    /** Diff the new snapshot against last-seen values. All four
     *  field-change pings live here (role / party / RSVP / world).
     *  World's mismatch FLASH state is separately recomputed each tick
     *  in {@link #updateWorldMismatch()} so position movement toggles
     *  the flash silently. */
    private static void applyDiff(AnniSnapshot snapshot) {
        String roleCode = extractRoleCode(snapshot);
        Integer partyOrd = extractPartyOrdinal(snapshot);
        String rsvpCode = extractRsvp(snapshot);
        String worldCode = extractPartyWorld(snapshot);

        long now = System.currentTimeMillis();
        long until = now + flashDurationMs();
        boolean anyChange = false;

        if (roleObserved && !equalsNullable(lastRole, roleCode)) {
            flashRoleUntil = until;
            anyChange = true;
        }
        roleObserved = true;
        lastRole = roleCode;

        if (partyObserved && !equalsNullable(lastParty, partyOrd)) {
            flashPartyUntil = until;
            anyChange = true;
        }
        partyObserved = true;
        lastParty = partyOrd;

        if (rsvpObserved && !equalsNullable(lastRsvp, rsvpCode)) {
            flashRsvpUntil = until;
            anyChange = true;
        }
        rsvpObserved = true;
        lastRsvp = rsvpCode;

        // World: ping only when the snapshot's party.world value
        // transitions (assignment / reassignment / unassignment).
        // First observation skipped via the worldObserved sentinel so
        // login with an existing assignment doesn't bing.
        if (worldObserved && !equalsNullable(lastWorld, worldCode)) {
            anyChange = true;
        }
        worldObserved = true;
        lastWorld = worldCode;

        if (anyChange) queuePingSounds();
    }

    /** Per-tick: recompute the live world-mismatch flag from the
     *  snapshot's {@code party.world} and the player's current world.
     *  Pure state — no pings here (user-position movement is silent;
     *  bings happen on snapshot value changes, handled in
     *  {@link #applyDiff(AnniSnapshot)}). */
    private static void updateWorldMismatch() {
        String partyWorld = extractPartyWorld(AnniSnapshotCache.latest());
        String currentWorld = Models.WorldState.getCurrentWorldName();
        worldMismatch = partyWorld != null && (currentWorld == null
                || !currentWorld.equalsIgnoreCase(partyWorld));
    }

    private static String extractRoleCode(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board != null && board.role() != null) return board.role();
        return null;
    }

    private static Integer extractPartyOrdinal(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.party() == null) return null;
        int o = board.party().ordinal();
        return o > 0 ? o : null;
    }

    private static String extractPartyWorld(AnniSnapshot snapshot) {
        if (snapshot == null) return null;
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.party() == null) return null;
        return board.party().world();
    }

    private static String extractRsvp(AnniSnapshot snapshot) {
        AnniSnapshot.Rsvp rsvp = snapshot.rsvp();
        if (rsvp == null || rsvp.revoked()) return null;
        return rsvp.notice();
    }

    private static <T> boolean equalsNullable(T a, T b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private static long flashDurationMs() {
        String intensity = VetsConfig.getString(VetsConfig.VETS_ANNI_FLASH_INTENSITY);
        if (intensity == null) intensity = "normal";
        switch (intensity) {
            case "subtle": return 5_000L;
            case "strong": return 20_000L;
            case "normal":
            default:       return 10_000L;
        }
    }

    private static void queuePingSounds() {
        if (!VetsConfig.get(VetsConfig.VETS_ANNI_FLASH_SOUND)) return;
        pendingSounds = Math.min(pendingSounds + SOUNDS_PER_CHANGE, SOUNDS_CAP);
        // Play the next sound immediately if nothing is currently in flight.
        if (nextSoundAt == 0L) nextSoundAt = System.currentTimeMillis();
    }

    private static void playPingSound() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) return;
        try {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
        } catch (Exception e) {
            VetsLogger.debug("FlashTracker.playPingSound failed: {}", e.getMessage());
        }
    }
}
