package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.logging.VetsLogger;

/**
 * Processes legacy-item tooltips via the Wynntils event bus so that all
 * downstream Wynntils features (item screenshot, item compare, etc.) see
 * the corrected tooltip — gold name, no italic, "Legacy Item" rarity label.
 *
 * <p>Subscribes at {@link EventPriority#NORMAL} so that Wynntils' own tooltip
 * modifiers run first (they also run at NORMAL but are registered earlier),
 * and the {@code ItemScreenshotFeature} at {@link EventPriority#LOW} sees the
 * final result.</p>
 *
 * <p>Sets {@link LegacyItemHandler#eventProcessedTooltip} when it modifies
 * the tooltip list.  The companion mixin ({@code LegacyItemTooltipMixin})
 * checks this flag to skip redundant re-processing on the same list.</p>
 */
public final class LegacyTooltipEventHandler {

  private static final LegacyTooltipEventHandler INSTANCE = new LegacyTooltipEventHandler();

  private LegacyTooltipEventHandler() {}

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
    List<Component> original = event.getTooltips();
    if (original.isEmpty()) return;

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

    // Strip the italic that getStyledHoverName() applies to CUSTOM_NAME items.
    // The event tooltips come from Screen.getTooltipFromItem → getTooltipLines
    // → getStyledHoverName, which wraps getHoverName() in a parent Component
    // with ITALIC if the item has a custom name.  Our getHoverName mixin
    // already provides the correct gold/enchanted name — we just need to
    // remove the spurious italic wrapper.
    List<Component> tooltips = stripItalicFirstLine(original, stack);

    // Run the full legacy tooltip processing (rarity line replacement, etc.)
    List<Component> processed = LegacyItemHandler.processTooltip(tooltips);

    if (processed != original) {
      event.setTooltips(processed);
      // Signal to LegacyItemTooltipMixin that this list was already processed
      LegacyItemHandler.eventProcessedTooltip = true;
    }
  }

  /**
   * If the item has a {@code CUSTOM_NAME} and the first tooltip line's root
   * style includes italic, returns a new list with that italic stripped.
   * Otherwise returns the original list unchanged.
   */
  private static List<Component> stripItalicFirstLine(List<Component> tooltips, ItemStack stack) {
    if (stack == null || !stack.has(DataComponents.CUSTOM_NAME)) return tooltips;

    Component first = tooltips.get(0);
    Style rootStyle = first.getStyle();
    // getStyledHoverName wraps in Component.empty().append(name).withStyle(rarity, ITALIC)
    // The italic flag is on the root style of that wrapper.
    if (rootStyle.isItalic()) {
      MutableComponent stripped = first.copy();
      stripped.setStyle(rootStyle.withItalic(false));
      List<Component> result = new ArrayList<>(tooltips);
      result.set(0, stripped);
      return result;
    }
    return tooltips;
  }
}
