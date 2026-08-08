package org.wynnvets.mixin.client.legacy;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.debug.dump.TooltipCapture;
import org.wynnvets.items.LegacyItemHandler;

/**
 * Hooks the inner {@code setTooltipForNextFrame} overload that every item
 * tooltip funnels through — including lists Wynntils may have already
 * rebuilt and wrapped in {@code Collections.unmodifiableList}.
 *
 * <p>Runs <em>after</em> the entire Wynntils event chain (including handlers
 * at LOWEST priority that may replace the tooltip list), so VetsMod's
 * modifications cannot be overwritten by downstream event handlers.</p>
 *
 * <p>Uses {@code @Inject} with cancellation to replace the (potentially
 * unmodifiable) list with a new mutable copy containing our gold name,
 * then re-invokes the method.  A reentry guard prevents infinite recursion.</p>
 */
@Mixin(GuiGraphics.class)
public class LegacyItemTooltipMixin {

    @Unique private boolean vetsmod$processing = false;

    @Inject(
            method =
                    "setTooltipForNextFrame("
                            + "Lnet/minecraft/client/gui/Font;"
                            + "Ljava/util/List;"
                            + "Ljava/util/Optional;"
                            + "II"
                            + "Lnet/minecraft/resources/Identifier;"
                            + ")V",
            at = @At("HEAD"),
            cancellable = true)
    private void vetsmod$recolorLegacyTooltip(
            Font font,
            List<Component> components,
            Optional<TooltipComponent> image,
            int mouseX,
            int mouseY,
            Identifier background,
            CallbackInfo ci) {
        boolean reentry = vetsmod$processing;
        if (reentry) {
            TooltipCapture.record(
                    false,
                    LegacyItemHandler.currentItemStack,
                    components,
                    components,
                    false,
                    true,
                    background,
                    LegacyItemHandler.lastProcessedWasLegacy);
            return;
        }

        List<Component> modified = LegacyItemHandler.processTooltip(components);
        if (modified != components) {
            ci.cancel();
            vetsmod$processing = true;
            try {
                // BEGIN PATCH(old-server-compat): Revert to just lastProcessedWasLegacy check.
                Identifier border =
                        LegacyItemHandler.lastProcessedWasLegacy
                                        && LegacyItemHandler.newTooltipStylesAvailable
                                ? LegacyItemHandler.LEGACY_BORDER
                                : background;
                // END PATCH(old-server-compat)
                TooltipCapture.record(
                        true,
                        LegacyItemHandler.currentItemStack,
                        components,
                        modified,
                        true,
                        false,
                        border,
                        LegacyItemHandler.lastProcessedWasLegacy);
                ((GuiGraphics) (Object) this)
                        .setTooltipForNextFrame(font, modified, image, mouseX, mouseY, border);
                // If Wynntils' screenshot keybind is held, take our own screenshot
                // with the fully-modified legacy tooltip and overwrite the clipboard.
                LegacyItemHandler.screenshotIfRequested(font, modified);
            } finally {
                vetsmod$processing = false;
            }
        } else {
            TooltipCapture.record(
                    true,
                    LegacyItemHandler.currentItemStack,
                    components,
                    components,
                    false,
                    false,
                    background,
                    LegacyItemHandler.lastProcessedWasLegacy);
        }
    }
}
