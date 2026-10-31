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
 * Hooks the inner {@code setTooltipForNextFrame} overload that container-slot
 * and item-stack tooltips funnel through — including lists Wynntils may have
 * already rebuilt and wrapped in {@code Collections.unmodifiableList}. Not every
 * item tooltip reaches it: one drawn through {@code GuiGraphics#renderTooltip}
 * directly bypasses it, as Wynntils' hold-to-compare view does, and vanilla's
 * recipe book uses {@code setComponentTooltipForNextFrame} instead.
 *
 * <p>Runs <em>after</em> the entire Wynntils event chain (including handlers
 * at LOWEST priority that may replace the tooltip list), so VetsMod's
 * modifications cannot be overwritten by downstream event handlers.</p>
 *
 * <p>When {@link LegacyItemHandler#processTooltip} rewrites the tooltip, the call is
 * cancelled and re-invoked with the rewritten copy (and the gold border, once the server's
 * new tooltip styles have been seen); a reentry guard lets that second call through. Every
 * pass is also recorded for the item-dump tool ({@link TooltipCapture}).</p>
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
                                ? LegacyItemHandler.legacyBorder()
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
                LegacyItemHandler.screenshotIfRequested(font, modified, border);
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
