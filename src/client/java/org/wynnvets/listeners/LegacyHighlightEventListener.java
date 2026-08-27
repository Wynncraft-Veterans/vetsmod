package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.SlotRenderEvent;
import net.minecraft.world.inventory.Slot;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.items.LegacyHighlightPainter;
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

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered LegacyHighlightEventHandler on Wynntils event bus");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSlotRenderPre(SlotRenderEvent.Pre event) {
        Slot slot = event.getSlot();
        LegacyHighlightPainter.paintIfLegacy(
                event.getGuiGraphics(), slot.getItem(), slot.x, slot.y);
    }
}
