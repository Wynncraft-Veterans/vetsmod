package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.SlotRenderEvent;
import com.wynntils.utils.colors.CustomColor;
import com.wynntils.utils.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.logging.VetsLogger;

/**
 * Subscribes to Wynntils' {@link SlotRenderEvent.Pre} to draw legacy-item
 * highlights that render OVER any Wynntils rarity highlights.
 *
 * <p>Registered at {@link EventPriority#LOWEST} so it runs after Wynntils'
 * {@code ItemHighlightFeature} (which subscribes at {@code HIGH}), effectively
 * replacing Wynntils' highlight with our configurable gradient + sprite.</p>
 */
public final class LegacyHighlightEventListener {

    private static final LegacyHighlightEventListener INSTANCE = new LegacyHighlightEventListener();

    private LegacyHighlightEventListener() {}

    /** Registers this handler with the Wynntils event bus. */
    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered LegacyHighlightEventHandler on Wynntils event bus");
    }

    /** Unregisters this handler from the Wynntils event bus. */
    public static void unregister() {
        WynntilsMod.unregisterEventListener(INSTANCE);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSlotRenderPre(SlotRenderEvent.Pre event) {
        Slot slot = event.getSlot();
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;
        if (!LegacyItemHandler.isLegacyItem(stack)) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        guiGraphics.fillGradient(slot.x, slot.y, slot.x + 16, slot.y + 16,
            VetsConfig.getLegacyBackgroundGradientTopColor(),
            VetsConfig.getLegacyBackgroundGradientBottomColor());
        RenderUtils.drawSprite(
            guiGraphics,
            VetsConfig.getLegacyForegroundTexture(),
            CustomColor.fromARGBInt(VetsConfig.getLegacyForegroundColor()),
            slot.x - 1,
            slot.y - 1,
            18,
            18);
    }
}
