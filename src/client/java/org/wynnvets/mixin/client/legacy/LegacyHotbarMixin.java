package org.wynnvets.mixin.client.legacy;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.items.LegacyHighlightPainter;

/**
 * Draws the legacy-item highlight on hotbar slots rendered by the in-game HUD.
 * Mirrors the behaviour of {@link LegacyHighlightMixin} which only covers
 * inventory / container screens ({@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}).
 */
@Mixin(Gui.class)
public class LegacyHotbarMixin {

    @Inject(
            method =
                    "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/DeltaTracker;"
                            + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
            at = @At("HEAD"))
    private void vetsmod$renderLegacyHotbarHighlight(
            GuiGraphics guiGraphics,
            int x,
            int y,
            DeltaTracker deltaTracker,
            Player player,
            ItemStack stack,
            int seed,
            CallbackInfo ci) {
        LegacyHighlightPainter.paintIfLegacy(guiGraphics, stack, x, y);
    }
}
