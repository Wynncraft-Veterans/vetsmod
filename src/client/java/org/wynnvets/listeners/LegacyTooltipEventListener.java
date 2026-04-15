package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.logging.VetsLogger;

/**
 * Sets item-context fields ({@link LegacyItemHandler#currentItemStack},
 * {@link LegacyItemHandler#currentItemHasFoil}) via the Wynntils event bus.
 *
 * <p>Subscribes at {@link EventPriority#NORMAL} to capture the hovered item
 * before any downstream handler.  Actual tooltip modification is deferred to
 * {@code LegacyItemTooltipMixin}, which runs after the entire event chain
 * and thus cannot be overwritten by later Wynntils handlers (e.g.
 * {@code onTooltipPreFinalize} at LOWEST).</p>
 */
public final class LegacyTooltipEventListener {

  private static final LegacyTooltipEventListener INSTANCE = new LegacyTooltipEventListener();

  private LegacyTooltipEventListener() {}

  /** Registers this handler with the Wynntils event bus. */
  public static void register() {
    WynntilsMod.registerEventListener(INSTANCE);
    VetsLogger.debug("Registered LegacyTooltipEventHandler on Wynntils event bus");
  }

  /** Unregisters this handler from the Wynntils event bus. */
  public static void unregister() {
    WynntilsMod.unregisterEventListener(INSTANCE);
  }

  @SubscribeEvent(priority = EventPriority.NORMAL)
  public void onItemTooltipRender(ItemTooltipRenderEvent.Pre event) {
    // Ensure currentItemStack is set for the tooltip processing pipeline.
    // LegacyHighlightMixin normally sets this, but only inside
    // AbstractContainerScreen.renderTooltip — the event may fire from
    // other call sites (e.g. creative inventory, recipe book).
    ItemStack stack = event.getItemStack();
    if (stack != null && !stack.isEmpty()) {
      LegacyItemHandler.currentItemStack = stack;
      LegacyItemHandler.currentItemHasFoil =
          stack.hasFoil()
              && !org.wynnvets.items.ItemDefinitions.isEnchantExcludedItem(stack);
    }
  }
}
