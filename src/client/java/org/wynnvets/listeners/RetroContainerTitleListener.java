package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.ContainerLabelRenderEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.render.RetroContainerTitles;

/**
 * Subscribes to Wynntils' {@link ContainerLabelRenderEvent.ContainerLabel}, which Wynntils posts
 * as a container screen draws its title, so retro can draw its own title there, and to the
 * {@link ContainerLabelRenderEvent.InventoryLabel} posted right after it, for the player
 * inventory's label.
 *
 * <p>{@link RetroContainerTitles#relabel} sets retro's label, deciding the screen first unless its
 * background already did ({@link org.wynnvets.mixin.client.retro.RetroContainerBackgroundMixin
 * RetroContainerBackgroundMixin}); the decision is what lets {@link RetroSlotListener} paint that
 * screen's slot fills. It sets no colour: Wynntils passes vanilla's label grey on.
 * {@link RetroContainerTitles#relabelInventory} then blanks the inventory's label on a screen that
 * keeps Wynncraft's. Default priority: no Wynntils feature listens to these events.</p>
 */
public final class RetroContainerTitleListener {

    private static final RetroContainerTitleListener INSTANCE = new RetroContainerTitleListener();

    private RetroContainerTitleListener() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered RetroContainerTitleListener on Wynntils event bus");
    }

    @SubscribeEvent
    public void onContainerLabel(ContainerLabelRenderEvent.ContainerLabel event) {
        RetroContainerTitles.relabel(event);
    }

    @SubscribeEvent
    public void onInventoryLabel(ContainerLabelRenderEvent.InventoryLabel event) {
        RetroContainerTitles.relabelInventory(event);
    }
}
