package org.wynnvets.mixin.client.retro;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractMountInventoryScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.wynnvets.retro.render.RetroContainerTitles;

/**
 * Draws a container that keeps Wynncraft's label on Wynncraft's own background.
 *
 * <p>The retro pack copies vanilla's container textures over the ones Wynncraft repaints (the
 * chest, hopper and horse screens), which suits a container retro relabels; under a container
 * whose label keeps Wynncraft's art, the vanilla texture would show through that art.
 * {@link RetroContainerTitles#background} answers, for the texture {@code renderBg} draws, the live
 * pack's copy of Wynncraft's own texture on such a screen, and the texture unchanged otherwise.
 * {@code renderBg} runs before {@code renderLabels}, so the screen's decision is made here first.
 * The body is one call into code that changes nothing while the pack is not live on Wynncraft.</p>
 */
@Mixin({ContainerScreen.class, HopperScreen.class, AbstractMountInventoryScreen.class})
public abstract class RetroContainerBackgroundMixin {

    /** Hands each container texture {@code renderBg} draws to the retro decision. */
    @ModifyArg(
            method = "renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/GuiGraphics;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"),
            index = 1)
    private Identifier vetsmod$drawWynncraftsBackground(Identifier texture) {
        return RetroContainerTitles.background((AbstractContainerScreen<?>) (Object) this, texture);
    }
}
