package org.wynnvets.items;

import com.wynntils.utils.colors.CustomColor;
import com.wynntils.utils.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.config.VetsConfig;

/**
 * The legacy-item slot highlight: a configurable background gradient with a sprite drawn over
 * it, sized to a 16&times;16 slot.
 *
 * <p>Two unrelated render paths draw it, and each had its own copy of the guard chain and both
 * draw calls: {@link org.wynnvets.mixin.client.legacy.LegacyHotbarMixin LegacyHotbarMixin} for
 * the in-game HUD hotbar, and
 * {@link org.wynnvets.listeners.LegacyHighlightEventListener LegacyHighlightEventListener} for
 * inventory and container screens, through Wynntils' {@code SlotRenderEvent.Pre}. They differ
 * only in where the coordinates come from — raw ints from the mixin's injected parameters,
 * {@code slot.x} / {@code slot.y} from the event.</p>
 *
 * <p>The method takes {@code (int x, int y)} rather than a {@code Slot} for that reason: the
 * mixin has only the ints, and a {@code Slot} parameter would drag
 * {@code net.minecraft.world.inventory.Slot} into a {@code Gui} mixin that has no use for
 * it.</p>
 *
 * <p>It is named {@code paintIfLegacy} because it calls
 * {@link LegacyItemHandler#isLegacyItem}: a name that hid the guard would invite a caller to
 * add its own, which is how the two chains drifted apart in the first place. That guard also
 * subsumes the config check — {@code isLegacyItem} opens on
 * {@code LEGACY_ITEM_HIGHLIGHTING} — and the empty-stack check, which is kept here anyway as
 * the cheaper of the two.</p>
 *
 * <p><b>No unit test is possible, and that is not a coverage gap to fix.</b> Both draw
 * statements need a live {@link GuiGraphics}; one of them also needs Wynntils'
 * {@link RenderUtils} and {@link CustomColor}, which are {@code modCompileOnly} and so absent
 * from the test runtime classpath. Even the two guards above them are out of reach &mdash;
 * they take an {@link ItemStack}, and nothing under {@code src/test} constructs one, because
 * {@code ItemStack.EMPTY} needs the Minecraft bootstrap. The check for this
 * class is the build plus the in-game pass, on a legacy item in the hotbar and the same item
 * in an inventory slot — two different code paths into one method.</p>
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
                VetsConfig.getLegacyBackgroundGradientTopColor(),
                VetsConfig.getLegacyBackgroundGradientBottomColor());
        RenderUtils.drawSprite(
                guiGraphics,
                VetsConfig.getLegacyForegroundTexture(),
                CustomColor.fromARGBInt(VetsConfig.getLegacyForegroundColor()),
                x - 10,
                y - 10,
                36,
                36);
    }
}
