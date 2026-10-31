package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.SlotRenderEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.render.RetroSlotValues;
import org.wynnvets.retro.render.RetroSlots;

/**
 * Subscribes to Wynntils' {@link SlotRenderEvent.Pre} to paint retro's panel slots
 * ({@link RetroSlots#paintIfPanel}) before anything else draws on a slot.
 *
 * <p>{@link EventPriority#HIGHEST} puts the panel under Wynntils' highlights
 * ({@code ItemHighlightFeature}, at {@code HIGH}) and under {@link LegacyHighlightEventListener}
 * ({@code LOWEST}), and the slot's item is drawn after the event. {@code receiveCanceled} paints
 * a slot another listener cancels too (Wynntils' streamer mode hides some slots at
 * {@code HIGHEST}): cancelling hides the item, not the slot's frame in the container's
 * texture.</p>
 *
 * <p>{@link SlotRenderEvent.Post}, after the slot's item, draws a value retro's title dropped
 * ({@link RetroSlotValues#drawValue}).</p>
 */
public final class RetroSlotListener {

    private static final RetroSlotListener INSTANCE = new RetroSlotListener();

    private RetroSlotListener() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered RetroSlotListener on Wynntils event bus");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onSlotRenderPre(SlotRenderEvent.Pre event) {
        RetroSlots.paintIfPanel(event.getGuiGraphics(), event.getScreen(), event.getSlot());
    }

    @SubscribeEvent
    public void onSlotRenderPost(SlotRenderEvent.Post event) {
        RetroSlotValues.drawValue(event.getGuiGraphics(), event.getScreen(), event.getSlot());
    }
}
