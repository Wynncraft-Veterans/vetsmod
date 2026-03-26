package org.wynnvets.rendering.territory;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Renders territory boundary outlines in the world using debug lines.
 *
 * <p>Draws a cage of coloured horizontal rectangles at the territory edges,
 * green when the player is inside the territory, red when outside.</p>
 *
 * <p><b>Credit:</b> The rendering approach — cage layout, distance culling,
 * green/red colour logic — is directly inspired by avomod2's
 * {@code TerritoryOutlineRenderer} by Avicia ({@code cf.avicia.avomod2}).
 * The Yarn-to-Mojang mapping translation and the restriction to specific
 * named territories are the only substantive changes from the original
 * design.</p>
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

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);

        PoseStack poseStack = new PoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

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

            float r = inTerritory ? 0f : 1f;
            float g = inTerritory ? 1f : 0f;

            // Draw cage lines at 4-block intervals around the player's Y
            for (int level = playerY / 4 - 20; level < playerY / 4 + 20; level++) {
                float y = (float) (4.0 * level);
                // Four edges of horizontal rectangle
                buffer.addVertex(matrix, (float) startX, y, (float) startZ).setColor(r, g, 0f, 1f);
                buffer.addVertex(matrix, (float) endX, y, (float) startZ).setColor(r, g, 0f, 1f);

                buffer.addVertex(matrix, (float) endX, y, (float) startZ).setColor(r, g, 0f, 1f);
                buffer.addVertex(matrix, (float) endX, y, (float) endZ).setColor(r, g, 0f, 1f);

                buffer.addVertex(matrix, (float) endX, y, (float) endZ).setColor(r, g, 0f, 1f);
                buffer.addVertex(matrix, (float) startX, y, (float) endZ).setColor(r, g, 0f, 1f);

                buffer.addVertex(matrix, (float) startX, y, (float) endZ).setColor(r, g, 0f, 1f);
                buffer.addVertex(matrix, (float) startX, y, (float) startZ).setColor(r, g, 0f, 1f);
            }
        }

        BufferUploader.drawWithShader(buffer.buildOrThrow());
        poseStack.popPose();

        RenderSystem.enableDepthTest();
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
    }
}
