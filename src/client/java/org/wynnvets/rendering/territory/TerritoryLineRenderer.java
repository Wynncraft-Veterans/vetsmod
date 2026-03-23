package org.wynnvets.rendering.territory;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Renders territory boundary outlines in the world using the Gizmos API.
 *
 * <p>Draws a cage of coloured horizontal rectangles at the territory edges,
 * green when the player is inside the territory, red when outside.</p>
 *
 * <p><b>Credit:</b> The rendering approach — cage layout, distance culling,
 * green/red colour logic, and use of the {@code Gizmos} API — is directly
 * inspired by avomod2's {@code TerritoryOutlineRenderer} by Avicia
 * ({@code cf.avicia.avomod2}). The Yarn-to-Mojang mapping translation and
 * the restriction to specific named territories are the only substantive
 * changes from the original design.</p>
 */
public final class TerritoryLineRenderer {

    private TerritoryLineRenderer() {}

    /** Register the world render callback. Call once during client init. */
    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> render());
    }

    private static void render() {
        if (!TerritoryLineManager.hasAnyActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        BlockPos playerPos = mc.player.blockPosition();
        int playerX = playerPos.getX();
        int playerY = playerPos.getY();
        int playerZ = playerPos.getZ();
        Vec3 playerVec = mc.player.position();

        for (var entry : TerritoryLineManager.getAliases().entrySet()) {
            String alias = entry.getKey();
            if (!TerritoryLineManager.isActive(alias)) continue;

            int[] bounds = TerritoryLineManager.getBounds(alias);
            if (bounds == null) continue;

            int startX = bounds[0];
            int startZ = bounds[1];
            int endX = bounds[2];
            int endZ = bounds[3];

            boolean inTerritory = playerX > startX && playerX < endX
                    && playerZ > startZ && playerZ < endZ;

            // Distance culling — skip territories too far away
            double dist = Math.min(Math.abs(playerVec.x - startX), Math.abs(playerVec.x - endX))
                    + Math.min(Math.abs(playerVec.z - startZ), Math.abs(playerVec.z - endZ));
            int maxDistance = Math.min(mc.options.renderDistance().get(), 16) * 15;
            if (!inTerritory && dist > maxDistance) continue;

            int color = inTerritory ? 0xFF00FF00 : 0xFFFF0000;
            GizmoStyle strokeStyle = GizmoStyle.stroke(color, 2.0f);

            // Draw cage lines at 4-block intervals around the player's Y
            for (int level = playerY / 4 - 20; level < playerY / 4 + 20; level++) {
                Gizmos.cuboid(
                        new AABB(startX, 4.0 * level, startZ, endX, 4.0 * level, endZ),
                        strokeStyle
                );
            }
        }
    }
}
