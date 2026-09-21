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
 * It is the hotbar counterpart of
 * {@link org.wynnvets.listeners.LegacyHighlightEventListener LegacyHighlightEventListener}, which
 * draws the same highlight in inventory / container screens
 * ({@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}) through Wynntils'
 * {@code SlotRenderEvent.Pre}; both hand the slot's stack and position to
 * {@link org.wynnvets.items.LegacyHighlightPainter#paintIfLegacy
 * LegacyHighlightPainter.paintIfLegacy}. {@link LegacyHighlightMixin}, on those same screens,
 * does not draw.
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
