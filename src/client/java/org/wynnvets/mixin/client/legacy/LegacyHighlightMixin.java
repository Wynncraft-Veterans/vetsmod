package org.wynnvets.mixin.client.legacy;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.items.LegacyItemHandler;

/**
 * Detects whether the server sends new-format tooltip styles, and captures the
 * hovered item's foil state, stack and slot.
 *
 * <p>The {@code renderSlot} hook sets the sticky
 * {@link org.wynnvets.items.LegacyItemHandler#newTooltipStylesAvailable
 * LegacyItemHandler.newTooltipStylesAvailable} flag when a container-screen
 * slot holds an item carrying a {@code tooltip_style} data component. The
 * {@code renderTooltip} hook records the hovered item's foil state and stack
 * for the tooltip pipeline and the hovered slot for the item-dump tool, and
 * clears all three when nothing is hovered.</p>
 *
 * <p>Highlight drawing for legacy items is handled by
 * {@link org.wynnvets.listeners.LegacyHighlightEventListener} via the Wynntils
 * event bus, which draws OVER any Wynntils rarity highlight at
 * {@code EventPriority.LOWEST}.</p>
 */
@Mixin(AbstractContainerScreen.class)
public class LegacyHighlightMixin {

    @Shadow protected Slot hoveredSlot;

    @Inject(method = "renderSlot", at = @At("HEAD"))
    private void vetsmod$renderLegacyHighlight(
            GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        // BEGIN PATCH(old-server-compat): Remove this block.
        if (!LegacyItemHandler.newTooltipStylesAvailable
                && stack.has(DataComponents.TOOLTIP_STYLE)) {
            LegacyItemHandler.newTooltipStylesAvailable = true;
        }
        // END PATCH(old-server-compat)
    }

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"))
    private void vetsmod$captureHoveredItemFoil(
            GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (hoveredSlot != null && hoveredSlot.hasItem()) {
            ItemStack hovered = hoveredSlot.getItem();
            LegacyItemHandler.currentItemHasFoil =
                    hovered.hasFoil() && !ItemDefinitions.isEnchantExcludedItem(hovered);
            LegacyItemHandler.currentItemStack = hovered;
            LegacyItemHandler.currentHoveredSlot = hoveredSlot;
        } else {
            LegacyItemHandler.currentItemHasFoil = false;
            LegacyItemHandler.currentItemStack = ItemStack.EMPTY;
            LegacyItemHandler.currentHoveredSlot = null;
        }
    }
}
