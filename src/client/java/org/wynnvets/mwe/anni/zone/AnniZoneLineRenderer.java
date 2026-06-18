package org.wynnvets.mwe.anni.zone;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker;

import java.util.List;

/**
 * S5 — draws the union of {@link AnniZone}'s 48-block disc circumferences
 * in-world so aggressive-mode users can see the anni zone boundary while
 * approaching.
 *
 * <p>Render hook: {@link WorldRenderEvents#AFTER_ENTITIES} — same as
 * {@link org.wynnvets.rendering.territory.TerritoryLineRenderer}. Uses
 * vanilla's {@link Gizmos#circle(Vec3, float, GizmoStyle)} primitive to
 * draw a stack of horizontal circles — one every {@link #Y_STEP} blocks
 * over a {@link #LEVELS_EITHER_SIDE}-stop range around the player's Y.
 * Visually this reads as a "cylinder cage" of stacked rings, mirroring
 * TerritoryLineRenderer's stacked-cuboid cage. Y values are snapped to
 * multiples of {@code Y_STEP} so consecutive frames don't jitter the
 * ring positions as the player moves vertically.</p>
 *
 * <p><b>Gate:</b> {@link AnniAggressiveTicker#isAggressiveActive()} AND
 * {@link VetsConfig#VETS_ANNI_ZONE_LINES}. No zone-presence check — per
 * the S5 design, aggressive features are window-scoped and visible
 * regardless of player position so users flying in can see the boundary.</p>
 *
 * <p>Distance culling: discs whose centre is more than ~200 blocks from
 * the player are skipped (cheap squared-distance test). Matches the
 * spirit of TerritoryLineRenderer's culling discipline; the zone is
 * small (1-2 discs total in practice) so culling rarely fires, but it
 * keeps the renderer cheap if vets-anni ever splits the event.</p>
 */
public final class AnniZoneLineRenderer {

    /** Beyond this squared-distance from the player, skip the disc. */
    private static final double CULL_DISTANCE_SQ = 200.0 * 200.0;

    /** ARGB — translucent magenta to differentiate from green/red
     *  territory lines and from the magenta scroll-spot beacon. Bright
     *  enough to see at distance, alpha 0xAA so it doesn't overwhelm. */
    private static final int LINE_COLOR_ARGB = 0xAAFF55FF;

    /** Stroke thickness in world units. */
    private static final float LINE_THICKNESS = 2.0f;

    /** Vertical spacing between stacked rings, in blocks. */
    private static final int Y_STEP = 10;

    /** Number of rings drawn above (and below) the player's snapped Y.
     *  Total per disc = {@code 2 * LEVELS_EITHER_SIDE + 1} rings.
     *  10 either side at 10-block step = ±100 blocks of vertical
     *  coverage, plenty for any flight altitude players use at anni. */
    private static final int LEVELS_EITHER_SIDE = 10;

    private static volatile boolean registered = false;

    private AnniZoneLineRenderer() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> render());
        VetsLogger.debug("AnniZoneLineRenderer registered");
    }

    private static void render() {
        if (!AnniAggressiveTicker.isAggressiveActive()) return;
        if (!VetsConfig.get(VetsConfig.VETS_ANNI_ZONE_LINES)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        List<AnniZone.Disc> discs = AnniZone.getDiscs();
        if (discs.isEmpty()) return;

        Vec3 ppos = player.position();
        GizmoStyle style = GizmoStyle.stroke(LINE_COLOR_ARGB, LINE_THICKNESS);

        // Snap to the nearest Y_STEP so the ring positions stay stable
        // as the player moves vertically — otherwise the rings jitter
        // every block-tick of altitude change.
        long baseY = Math.floorDiv((long) Math.floor(ppos.y), (long) Y_STEP)
                * (long) Y_STEP;

        for (AnniZone.Disc disc : discs) {
            double dx = ppos.x - disc.x();
            double dz = ppos.z - disc.z();
            if (dx * dx + dz * dz > CULL_DISTANCE_SQ) continue;
            for (int level = -LEVELS_EITHER_SIDE; level <= LEVELS_EITHER_SIDE; level++) {
                double y = baseY + (long) level * (long) Y_STEP;
                Gizmos.circle(
                        new Vec3(disc.x(), y, disc.z()),
                        (float) disc.radius(),
                        style
                );
            }
        }
    }
}
