package org.wynnvets.items;

import com.wynntils.utils.colors.CustomColor;
import com.wynntils.utils.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.config.LegacyItemStyle;

/**
 * The legacy-item slot highlight: a configurable background gradient with a sprite drawn over
 * it, sized to a 16&times;16 slot.
 *
 * <p>Two render paths call it, differing only in where the coordinates come from:
 * {@link org.wynnvets.mixin.client.legacy.LegacyHotbarMixin LegacyHotbarMixin} for the in-game
 * HUD hotbar (raw ints from the mixin's injected parameters), and
 * {@link org.wynnvets.listeners.LegacyHighlightEventListener LegacyHighlightEventListener} for
 * inventory and container screens, through Wynntils' {@code SlotRenderEvent.Pre}
 * ({@code slot.x} / {@code slot.y}). It takes {@code (int x, int y)} rather than a
 * {@code Slot} because the mixin has only the ints, and a {@code Slot} parameter would pull
 * {@code net.minecraft.world.inventory.Slot} into a {@code Gui} mixin.</p>
 *
 * <p>The guard is part of the method, so callers add none of their own:
 * {@link LegacyItemHandler#isLegacyItem} also covers the config check (it opens on
 * {@code LEGACY_ITEM_HIGHLIGHTING}), and the empty-stack check runs first as the cheaper of
 * the two.</p>
 *
 * <p>There is no unit test, and none is possible: both draw statements need a live
 * {@link GuiGraphics}; one of them also needs Wynntils' {@link RenderUtils} and
 * {@link CustomColor}, which are {@code modCompileOnly} and so absent from the test runtime
 * classpath; and the guards take an {@link ItemStack}, which nothing under {@code src/test}
 * can construct without the Minecraft bootstrap. It is checked in game, on a legacy item in
 * the hotbar and the same item in an inventory slot.</p>
 */
public final class LegacyHighlightPainter {

    private LegacyHighlightPainter() {}

    /**
     * Draws the legacy highlight over the 16&times;16 slot at {@code (x, y)}, if {@code stack}
     * is a legacy item and the feature is on. Does nothing otherwise.
     *
     * @param guiGraphics the graphics context to draw into
     * @param stack       the stack in the slot
     * @param x           the slot's left edge, in GUI-scaled pixels
     * @param y           the slot's top edge, in GUI-scaled pixels
     */
    public static void paintIfLegacy(GuiGraphics guiGraphics, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;
        if (!LegacyItemHandler.isLegacyItem(stack)) return;

        guiGraphics.fillGradient(
                x,
                y,
                x + 16,
                y + 16,
                LegacyItemStyle.getBackgroundGradientTopColor(),
                LegacyItemStyle.getBackgroundGradientBottomColor());
        RenderUtils.drawSprite(
                guiGraphics,
                LegacyItemStyle.getForegroundTexture(),
                CustomColor.fromARGBInt(LegacyItemStyle.getForegroundColor()),
                x - 10,
                y - 10,
                36,
                36);
    }
}
