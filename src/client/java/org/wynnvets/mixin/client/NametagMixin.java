package org.wynnvets.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wynntils.mc.extension.EntityRenderStateExtension;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.util.SupportersFetcher;
import org.wynnvets.util.nametag.NametagAnimator;

/**
 * Intercepts nametag submission for player entities to apply a subtle
 * animated gradient on supporter usernames.
 *
 * <p>The mixin runs at {@code HEAD} of {@code AvatarRenderer.submitNameTag},
 * modifying the {@code nameTag} field on the render state <em>before</em>
 * Wynntils (or vanilla) reads it.  Priority is set below the default 1000
 * so that this handler is the first HEAD injection to execute, ensuring the
 * animated component is visible to downstream consumers (e.g.&nbsp;Wynntils'
 * {@code AvatarRendererMixin} at default priority 1000).</p>
 *
 * <p>Uses Wynntils' {@code EntityRenderStateExtension} (set during
 * {@code extractRenderState}) to obtain the real {@code Player} entity and
 * its GameProfile username, completely bypassing nametag text parsing for
 * the supporter check.</p>
 */
@Mixin(value = AvatarRenderer.class, priority = 900)
public class NametagMixin {

    @Inject(
            method = "submitNameTag("
                    + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/CameraRenderState;"
                    + ")V",
            at = @At("HEAD"))
    private void vetsmod$animateSupporterNametag(
            AvatarRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState cameraRenderState,
            CallbackInfo ci) {
        if (state.nameTag == null) return;

        // Retrieve the real player entity via Wynntils' render-state extension.
        String username = null;
        if (state instanceof EntityRenderStateExtension ext) {
            Entity entity = ext.getEntity();
            if (entity instanceof AbstractClientPlayer player) {
                username = player.getGameProfile().name();
            }
        }
        if (username == null || !SupportersFetcher.isSupporter(username)) return;

        Component animated = NametagAnimator.tryAnimate(state.nameTag, username);
        if (animated != null) {
            state.nameTag = animated;
        }
    }
}
