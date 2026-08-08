package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.InventoryKeyPressEvent;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import com.wynntils.utils.mc.McUtils;
import net.minecraft.client.KeyMapping;
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

  /**
   * Detects when the Wynntils screenshot keybind is pressed in an inventory
   * screen.  Sets a flag consumed by {@link LegacyScreenshotHandler} so the
   * mixin can take its own screenshot after applying legacy modifications.
   *
   * <p>Wynntils processes this same event in {@code KeyBindManager} and
   * passes it to {@code ItemScreenshotFeature.onInventoryPress}, which
   * stores the slot for the next render frame.  By the time our mixin in
   * {@code setTooltipForNextFrame} runs, the physical key state may already
   * be released, so we capture the press here instead of polling.</p>
   */
  @SubscribeEvent
  public void onInventoryKeyPress(InventoryKeyPressEvent event) {
    for (KeyMapping km : McUtils.options().keyMappings) {
      if (km.getName().contains("screenshotItem")) {
        if (km.matches(event.getKeyEvent())) {
          LegacyItemHandler.notifyScreenshotKeyPressed();
          VetsLogger.info("Screenshot keybind press detected via InventoryKeyPressEvent");
        }
        return;
      }
    }
  }
}
