package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.HotbarSlotRenderEvent;
import com.wynntils.mc.event.SlotRenderEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.render.RetroSlotIcons;

/**
 * Subscribes to Wynntils' {@link SlotRenderEvent.Post} and {@link HotbarSlotRenderEvent.Post} to
 * draw over a slot's item what its stub cannot say while the retro pack is live
 * ({@link RetroSlotIcons}): an unidentified gear box's type, a mastery tome's type and glint, and
 * the Ability Tree button's glint. Both are posted after the slot's item is drawn, where Wynntils'
 * own {@code UnidentifiedItemIconFeature} draws its icon, which retro turns off.
 */
public final class RetroSlotIconListener {

    private static final RetroSlotIconListener INSTANCE = new RetroSlotIconListener();

    private RetroSlotIconListener() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered RetroSlotIconListener on Wynntils event bus");
    }

    @SubscribeEvent
    public void onSlotRenderPost(SlotRenderEvent.Post event) {
        RetroSlotIcons.drawOverSlot(event.getGuiGraphics(), event.getScreen(), event.getSlot());
    }

    @SubscribeEvent
    public void onHotbarSlotRenderPost(HotbarSlotRenderEvent.Post event) {
        RetroSlotIcons.drawOverHotbarSlot(
                event.getGuiGraphics(), event.getItemStack(), event.getX(), event.getY());
    }
}
