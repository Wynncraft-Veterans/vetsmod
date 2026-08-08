package org.wynnvets.mwe.anni.outline;

import com.wynntils.mc.extension.EntityExtension;
import com.wynntils.utils.colors.CustomColor;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.mode.AnniMode;
import org.wynnvets.mwe.anni.mode.AnniModeManager;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;
import org.wynnvets.mwe.anni.zone.AnniZone;

/**
 * Per-tick driver for the S4 player highlight overlay.
 *
 * <p>Mirrors {@link org.wynnvets.mwe.anni.bossbar.VetsBossBarManager VetsBossBarManager}'s tick +
 * register conventions (subscribes {@link ClientTickEvents#END_CLIENT_TICK} once at init; catches
 * all per-tick exceptions to never break the render loop).</p>
 *
 * <p>Activation gate, all four required:</p>
 * <ol>
 *   <li>{@link AnniModeManager#current()} != {@link AnniMode#SILENT}</li>
 *   <li>Snapshot stamp epoch present AND {@code T-2h ≤ now ≤ T+30m}</li>
 *   <li>{@link AnniZone#isInZone(double, double)} returns true (or the
 *       debug {@link #forceInZone} override is on)</li>
 *   <li>At least one of {@link VetsConfig#VETS_ANNI_OUTLINES_ENABLED} or
 *       {@link VetsConfig#VETS_ANNI_NAMETAGS_ENABLED} is true (so users
 *       who want neither half see no S4 effect at all)</li>
 * </ol>
 *
 * <p>While the gate holds, this ticker walks {@code level.players()} and for each player either
 * applies their registry-tier glow colour or clears it to {@link CustomColor#NONE}. The "anni
 * nametag mode" flag {@link #isOutlineSuppressionActive()} flips true so the
 * {@link org.wynnvets.mixin.client.NametagMixin NametagMixin} anni branch and
 * {@link org.wynnvets.mixin.client.EntityOutlineColorMixin EntityOutlineColorMixin}'s outsider
 * clobber become eligible on the same tick — each still checks its own config toggle, and the
 * gate needs only one of the two.</p>
 *
 * <p>Tracks every username we've applied a non-NONE glow to so that when
 * the gate closes (or a player falls off the registry), the cleanup walk
 * resets only what we touched — vanilla / Wynntils entities the user
 * has their own reason to colour aren't disturbed.</p>
 *
 * <p>Spec window (per parent plan): T-2h to T+30m. Tighter than the
 * boss-bar window (which uses 90 m OR in-zone) because highlights are
 * the high-intrusiveness UI — outline + nametag recolour on every
 * nearby player is reserved for the strictest spec interpretation.</p>
 */
public final class AnniOutlineTicker {

    /** Activation window — 2 hours before stamp through 30 minutes after.
     *  Matches spec §"Player Highlights": "WITHIN 2h of an anni, OR
     *  within 30 mins after an anni". */
    private static final long WINDOW_BEFORE_SECONDS = 2L * 60L * 60L; // 2 h

    private static final long WINDOW_AFTER_SECONDS = 30L * 60L; // 30 m

    private static volatile boolean registered = false;
    private static volatile boolean suppressionActive = false;

    /** Debug override — {@code true} forces the in-zone gate to pass
     *  regardless of player position. Flipped by
     *  {@code /wv debug tree anni zone enter|exit}. */
    private static volatile boolean forceInZone = false;

    /** Usernames (lowercased) we've applied a glow colour to. Used so the
     *  cleanup walk only clears entries we touched, not random other
     *  entities' colours. {@link CopyOnWriteArraySet} for cheap iteration
     *  without a lock; mutations are infrequent (per-tick at most). */
    private static final Set<String> appliedUsernames = new CopyOnWriteArraySet<>();

    private AnniOutlineTicker() {}

    /** Idempotent registration. */
    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        VetsLogger.debug("AnniOutlineTicker registered");
    }

    /** {@code true} while the S4 activation gate holds. The two
     *  behavioural readers are {@link org.wynnvets.mixin.client.EntityOutlineColorMixin
     *  EntityOutlineColorMixin} (which zeroes an outsider's already-extracted {@code
     *  state.outlineColor}) and {@link org.wynnvets.mixin.client.NametagMixin NametagMixin}'s anni
     *  branch (to decide whether to recolour); two debug dumps also report it. */
    public static boolean isOutlineSuppressionActive() {
        return suppressionActive;
    }

    /** Debug entry — {@code /wv debug tree anni zone enter|exit} flips
     *  this. When {@code true}, the ticker treats the player as
     *  in-zone regardless of position. */
    public static void setForceInZone(boolean value) {
        forceInZone = value;
        VetsLogger.debug("AnniOutlineTicker.forceInZone = {}", value);
    }

    // ── Per-tick driver ────────────────────────────────────────────────

    private static void tick() {
        try {
            tickInner();
        } catch (Exception e) {
            VetsLogger.debug("AnniOutlineTicker.tick failed: {}", e.getMessage());
        }
    }

    private static void tickInner() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            if (suppressionActive) {
                clearAllApplied(level);
                suppressionActive = false;
            }
            return;
        }

        boolean shouldBeActive = gateHolds(player);
        if (!shouldBeActive) {
            if (suppressionActive) {
                clearAllApplied(level);
                suppressionActive = false;
            }
            return;
        }
        suppressionActive = true;

        // Walk every player on the world; either apply the registry entry
        // or clear any leftover glow we previously applied. Don't touch
        // entities we never coloured.
        Set<String> stillApplied = new HashSet<>();
        for (AbstractClientPlayer other : level.players()) {
            if (other == player) continue;
            String name = other.getGameProfile().name();
            if (name == null) continue;
            String key = name.toLowerCase(Locale.ROOT);
            AnniOutlineRegistry.Entry entry = AnniOutlineRegistry.getEntry(name);
            EntityExtension ext = (EntityExtension) other;
            if (entry != null) {
                ext.setGlowColor(entry.outlineColor());
                stillApplied.add(key);
            } else if (appliedUsernames.contains(key)) {
                // Was coloured by us last tick but is no longer on a vets
                // party (or fell out of render range and came back) —
                // restore default.
                ext.setGlowColor(CustomColor.NONE);
            }
        }
        // Drop usernames that didn't appear in this tick's walk (the player
        // left render range or logged off). If they reappear later, the
        // registry rebuild handles them.
        for (String prev : appliedUsernames) {
            if (!stillApplied.contains(prev)) {
                appliedUsernames.remove(prev);
            }
        }
        appliedUsernames.addAll(stillApplied);
    }

    private static boolean gateHolds(LocalPlayer player) {
        AnniMode mode = AnniModeManager.current();
        if (mode == AnniMode.SILENT) return false;

        boolean outlines = VetsConfig.get(VetsConfig.VETS_ANNI_OUTLINES_ENABLED);
        boolean nametags = VetsConfig.get(VetsConfig.VETS_ANNI_NAMETAGS_ENABLED);
        if (!outlines && !nametags) return false;

        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        if (snapshot == null) return false;
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) return false;
        Long stamp = event.stampEpoch();
        if (stamp == null) return false;

        long now = Instant.now().getEpochSecond();
        long delta = stamp - now;
        // delta positive = anni in future; negative = anni in past.
        boolean inWindow = delta >= -WINDOW_AFTER_SECONDS && delta <= WINDOW_BEFORE_SECONDS;
        if (!inWindow) return false;

        boolean inZone = forceInZone || AnniZone.isInZone(player.getX(), player.getZ());
        if (!inZone) return false;

        return true;
    }

    private static void clearAllApplied(ClientLevel level) {
        if (level == null) {
            appliedUsernames.clear();
            return;
        }
        // For every player we previously coloured, reset their glow to
        // NONE. Iterate the world's player list rather than calling
        // setGlowColor on stale entity refs.
        for (AbstractClientPlayer other : level.players()) {
            String name = other.getGameProfile().name();
            if (name == null) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (appliedUsernames.contains(key)) {
                ((EntityExtension) other).setGlowColor(CustomColor.NONE);
            }
        }
        appliedUsernames.clear();
    }
}
