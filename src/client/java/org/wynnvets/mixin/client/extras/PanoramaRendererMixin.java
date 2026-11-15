package org.wynnvets.mixin.client.extras;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.wynnvets.extras.panorama.ExtrasPanorama;

/**
 * Draws the Vetlas panorama of {@code /wv extras panorama}.
 *
 * <p>{@code render} turns the panorama, draws its cube map, then lays vanilla's overlay over it,
 * behind the main menu, every screen opened with no world, and the screens shown while a world
 * loads. {@link ExtrasPanorama#draw} decides which cube map that one call draws: Vetlas's while
 * the egg is on and retro is off, else the renderer's own, so the turn and the overlay stay
 * vanilla's. The body is one call into code that throws only what the renderer's own draw
 * throws.</p>
 */
@Mixin(PanoramaRenderer.class)
public abstract class PanoramaRendererMixin {

    /** Hands the panorama's cube map draw to {@link ExtrasPanorama#draw}. */
    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIZ)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/CubeMap;render(Lnet/minecraft/client/Minecraft;FF)V"))
    private void vetsmod$extrasPanorama(
            CubeMap cubeMap,
            Minecraft minecraft,
            float pitch,
            float yaw,
            Operation<Void> original) {
        ExtrasPanorama.draw(
                minecraft, cubeMap, drawn -> original.call(drawn, minecraft, pitch, yaw));
    }
}
