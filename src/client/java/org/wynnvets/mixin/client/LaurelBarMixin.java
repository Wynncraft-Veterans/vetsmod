package org.wynnvets.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wynntils.features.players.CustomNametagRendererFeature;
import com.wynntils.services.leaderboard.LeaderboardService;
import com.wynntils.services.leaderboard.type.LeaderboardBadge;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.wynnvets.laurels.LaurelBadges;

/**
 * Draws the vets laurels on Wynntils' leaderboard badge bar, so they get its layout, rotation,
 * sneak dimming and config gates unchanged. Two {@code @WrapOperation}s on
 * {@code CustomNametagRendererFeature.drawBadges}: the first appends our badges to the list
 * Wynntils reads, the second swaps in our sheet when one of them is drawn. The model, the
 * encoding and the failure modes are in {@code vetsmod_laurels.md}; the logic is
 * {@link LaurelBadges}'s.
 *
 * <p><b>This mixin is in its own, non-required config</b>, {@code vetsmod.laurels.mixins.json},
 * with {@code require = 0} on both wraps, so a Wynntils refactor of {@code drawBadges} costs the
 * laurels and not the game. {@code remap = false} because the target is Wynntils' own class.</p>
 */
@Mixin(value = CustomNametagRendererFeature.class, remap = false)
public class LaurelBarMixin {

    @WrapOperation(
            method = "drawBadges",
            require = 0,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/wynntils/services/leaderboard/LeaderboardService;"
                                            + "getPlayerBadges(Ljava/util/UUID;)"
                                            + "Ljava/util/List;"))
    private List<LeaderboardBadge> vetsmod$appendLaurels(
            LeaderboardService service, UUID uuid, Operation<List<LeaderboardBadge>> original) {
        return LaurelBadges.withLaurels(original.call(service, uuid), uuid);
    }

    // Name-only target: the descriptor holds Minecraft types, which remap = false would not remap.
    @WrapOperation(
            method = "drawBadges",
            require = 0,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/wynntils/utils/render/RenderUtils;"
                                            + "renderLeaderboardBadge"))
    private void vetsmod$drawLaurel(
            PoseStack poseStack,
            OrderedSubmitNodeCollector collector,
            EntityRenderState entityState,
            CameraRenderState cameraState,
            Identifier texture,
            float width,
            float height,
            float uOffset,
            float vOffset,
            float u,
            float v,
            float textureWidth,
            float textureHeight,
            float customOffset,
            float horizontalShift,
            float verticalShift,
            Operation<Void> original) {
        LaurelBadges.onBadgeDrawn();
        if (LaurelBadges.isLaurel(uOffset)) {
            texture = LaurelBadges.TEXTURE;
            uOffset = LaurelBadges.sheetU(uOffset);
            textureWidth = LaurelBadges.TEXTURE_WIDTH;
            textureHeight = LaurelBadges.TEXTURE_HEIGHT;
        }
        original.call(
                poseStack,
                collector,
                entityState,
                cameraState,
                texture,
                width,
                height,
                uOffset,
                vOffset,
                u,
                v,
                textureWidth,
                textureHeight,
                customOffset,
                horizontalShift,
                verticalShift);
    }
}
