package org.wynnvets.mixin.client.retro;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.wynnvets.retro.render.RetroSlots;

/**
 * Keeps retro's inert slots from answering the mouse.
 *
 * <p>{@code getHoveredSlot} is how a container screen finds the slot under the mouse, for the
 * hover highlight, the tooltip, clicks, drags and number keys alike, so a slot it does not return
 * neither highlights nor takes an item. While the retro pack is live on Wynncraft,
 * {@link RetroSlots#hovered} drops the inventory slots the pack's image edits painted over (the
 * crafting grid, result and offhand), and empty panel slots that never take an item. The body is
 * one call into code that does nothing while the pack is not live on Wynncraft.</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class RetroHoveredSlotMixin {

    /** Hands the slot under the mouse to {@link RetroSlots#hovered}. */
    @ModifyReturnValue(
            method = "getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;",
            at = @At("RETURN"))
    private Slot vetsmod$skipRetroInertSlots(Slot slot) {
        return RetroSlots.hovered((AbstractContainerScreen<?>) (Object) this, slot);
    }
}
