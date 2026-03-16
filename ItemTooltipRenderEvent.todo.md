# ItemTooltipRenderEvent Migration — Replacing LegacyItemTooltipMixin

## Overview

`LegacyItemTooltipMixin` injects into `GuiGraphics.setTooltipForNextFrame(Font, List<Component>, Optional<TooltipComponent>, int, int, Identifier)` at `HEAD` with cancellation, intercepting the final tooltip submission point to rewrite tooltip lines for legacy items. It uses a boolean re-entry guard (`vetsmod$processing`) to avoid infinite recursion when calling the same method with modified content.

Wynntils' `ItemTooltipRenderEvent.Pre` fires at a higher-level hook point, provides the tooltip as a mutable list, and avoids the re-entry problem entirely. It also gives access to the `ItemStack` being hovered, which the current mixin lacks (it operates on raw `List<Component>` with no item context).

---

## What LegacyItemTooltipMixin Does Today

```java
@Mixin(GuiGraphics.class)
public class LegacyItemTooltipMixin {

    @Unique private boolean vetsmod$processing = false;

    @Inject(method = "setTooltipForNextFrame(...)", at = @At("HEAD"), cancellable = true)
    private void vetsmod$recolorLegacyTooltip(
        Font font, List<Component> components, Optional<TooltipComponent> image,
        int mouseX, int mouseY, Identifier background, CallbackInfo ci) {

        if (vetsmod$processing) return;        // re-entry guard

        List<Component> modified = LegacyItemHandler.processTooltip(components);
        if (modified != components) {           // reference equality check
            ci.cancel();
            vetsmod$processing = true;
            try {
                ((GuiGraphics)(Object) this)
                    .setTooltipForNextFrame(font, modified, image, mouseX, mouseY, background);
            } finally {
                vetsmod$processing = false;
            }
        }
    }
}
```

### Why the Re-entry Guard Exists

Wynntils' `TooltipHandler` wraps tooltip lists in `Collections.unmodifiableList()` before passing them to `setTooltipForNextFrame`. VetsMod can't mutate the list in-place — it must create a new `ArrayList` copy, modify it, then call `setTooltipForNextFrame` again with the new list. This second call would trigger the mixin again, so the boolean guard prevents infinite recursion.

### What LegacyItemHandler.processTooltip() Does

Given the tooltip line list, it:

1. **Short-circuits** on empty lists and crafted items (detected by the `CRAFTED_PATTERN`)
2. **Extracts** the first line (item name), normalizes it (strips trailing `À`, strips `⬡ Enchanted ` prefix if present, strips Wynntils percentage suffix)
3. **Preserves** the Wynntils colored percentage suffix component (e.g., `[61.7%]`) for re-attachment
4. **Detects** the item category:
   - Name-matched legacy (`ItemDefinitions.isLegacy`)
   - Misc-legacy (name match + "Misc. Item" rarity)
   - Beta/Alpha legacy (gold `Lv. min:` line)
   - Enchanted (foil + not in unenchanted whitelist) — uses `currentItemHasFoil` set by `LegacyHighlightMixin`
   - Junk legacy ("Junk Item" rarity + not in notjunk whitelist)
   - Crafting legacy ("Crafting Item" rarity)
5. **Rewrites** the tooltip:
   - First line → gold-styled name (with enchanted prefix if applicable), percentage suffix preserved
   - Rarity line → replaced with "Legacy Item (Rarity)" or "Beta/Alpha Legacy Item" in gold
   - Inserted before F3+H debug lines

If no modification is needed, it returns the original list reference (identity check skips the cancel).

---

## What Wynntils Provides

### ItemTooltipRenderEvent.Pre

```java
public static class Pre extends ItemTooltipRenderEvent implements ICancellableEvent {
    private List<Component> tooltips;

    public Pre(GuiGraphics guiGraphics, ItemStack itemStack,
               List<Component> tooltips, int mouseX, int mouseY) { ... }

    public List<Component> getTooltips()                    // returns unmodifiable view
    public void setTooltips(List<Component> tooltips)       // replaces with new unmodifiable view
    public ItemStack getItemStack()                         // the actual hovered item!
    public void setItemStack(ItemStack itemStack)
    public void setMouseX(int mouseX)
    public void setMouseY(int mouseY)
    // Implements ICancellableEvent — cancel() suppresses the tooltip entirely
}
```

**Key advantages over the mixin approach:**

1. **`getItemStack()` is available** — the mixin only sees `List<Component>` with no item context. With the event, you can call `stack.hasFoil()` directly instead of relying on the cross-mixin `LegacyItemHandler.currentItemHasFoil` hack.
2. **`setTooltips()` replaces the list cleanly** — no need to cancel-and-re-invoke. The event internally stores the replacement and Wynntils' mixin uses the final value.
3. **No re-entry guard needed** — `setTooltips()` is a simple field assignment, not a method re-invocation.
4. **Priority control** — `@SubscribeEvent(priority = EventPriority.LOW)` lets vetsmod run after Wynntils' own tooltip processing is complete (e.g., after Wynntils adds percentage suffixes, rebuilds stat lines, etc.), so vetsmod sees the fully-processed tooltip and can modify the final output.

### ItemTooltipRenderEvent.Post

Fires after rendering. Useful for drawing additional overlays on top of the tooltip (not needed for this migration).

### Registration

Same as the chat migration: `WynntilsMod.registerEventListener(listenerObject)`, `@SubscribeEvent` methods.

---

## Migration Plan

### Phase 1: Create the Event Listener

Add a new class (or add a method to an existing Wynntils listener class if one was created for the ChatMessageEvent migration):

```java
package org.wynnvets.items;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

public final class WynntilsItemListener {

    private static final WynntilsItemListener INSTANCE = new WynntilsItemListener();

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onTooltipRender(ItemTooltipRenderEvent.Pre event) {
        // Phase 2 implementation
    }
}
```

Register during `VetsmodClient.onInitializeClient()`:
```java
WynntilsItemListener.register();
```

#### Why EventPriority.LOW?

Wynntils' own tooltip features (percentage display, stat rewriting, identification ranges, reroll count) run at `NORMAL` priority. By subscribing at `LOW`, vetsmod sees the fully-processed Wynntils tooltip and modifies it last. This ensures:

- The Wynntils percentage suffix `[61.7%]` is already appended to the name → vetsmod can detect and re-attach it
- Wynntils stat lines and reformatted content are final → vetsmod's rarity-line replacement finds the correct line
- No ordering conflict with Wynntils features

---

### Phase 2: Implement the Tooltip Processor

```java
@SubscribeEvent(priority = EventPriority.LOW)
public void onTooltipRender(ItemTooltipRenderEvent.Pre event) {
    ItemStack stack = event.getItemStack();
    if (stack.isEmpty()) return;

    List<Component> tooltips = new ArrayList<>(event.getTooltips());

    // Pass the hasFoil flag directly from the ItemStack — eliminates the
    // cross-mixin currentItemHasFoil hack
    LegacyItemHandler.currentItemHasFoil = stack.hasFoil();

    List<Component> modified = LegacyItemHandler.processTooltip(tooltips);
    if (modified != tooltips) {
        event.setTooltips(modified);
    }
}
```

**Critical detail**: `event.getTooltips()` returns an `Collections.unmodifiableList` wrapper. `LegacyItemHandler.processTooltip()` already creates a new `ArrayList` copy when modifications are needed (via `new ArrayList<>(tooltipLines)`), so it handles unmodifiable input correctly. However, we pass `new ArrayList<>(event.getTooltips())` to ensure `processTooltip()` always receives a mutable list — its identity-check early return (`modified != components`) works against the original reference.

Wait — actually, looking at the code more carefully: `processTooltip()` already returns the original `tooltipLines` reference when no changes are needed, and a new `ArrayList` when changes are made. The identity check works correctly as long as we pass through the event's list reference:

```java
@SubscribeEvent(priority = EventPriority.LOW)
public void onTooltipRender(ItemTooltipRenderEvent.Pre event) {
    ItemStack stack = event.getItemStack();
    if (stack.isEmpty()) return;

    LegacyItemHandler.currentItemHasFoil = stack.hasFoil();

    List<Component> original = event.getTooltips();
    List<Component> modified = LegacyItemHandler.processTooltip(original);
    if (modified != original) {
        event.setTooltips(modified);
    }
}
```

This is cleaner. `processTooltip()` will create a copy internally via `new ArrayList<>(tooltipLines)` when modifications are needed, so the unmodifiable input is never mutated.

---

### Phase 3: Eliminate the currentItemHasFoil Cross-Mixin Hack

**Current flow**: `LegacyHighlightMixin` injects into `AbstractContainerScreen.renderTooltip()` at `HEAD` and sets `LegacyItemHandler.currentItemHasFoil = hoveredSlot.getItem().hasFoil()` before the tooltip is rendered. The tooltip mixin then reads this static field to decide whether an item is "enchanted" (foiled but not in the unenchanted whitelist).

**With the event**: `ItemTooltipRenderEvent.Pre` provides `getItemStack()` directly. We set `currentItemHasFoil` from the event's ItemStack, eliminating the dependency on `LegacyHighlightMixin`'s tooltip-phase injection:

```java
LegacyItemHandler.currentItemHasFoil = event.getItemStack().hasFoil();
```

**After this migration**: Remove the `renderTooltip` injection from `LegacyHighlightMixin`. Keep only the `renderSlot` injection (which draws the highlight color). The tooltip portion of that mixin becomes dead code.

Specifically, in `LegacyHighlightMixin`, the `@Inject` on `renderTooltip` that captures `currentItemHasFoil` can be entirely deleted:

```java
// DELETE THIS from LegacyHighlightMixin:
@Inject(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V",
        at = @At("HEAD"))
private void vetsmod$captureHoveredFoil(GuiGraphics graphics, int mouseX, int mouseY,
                                         CallbackInfo ci) {
    // ... sets LegacyItemHandler.currentItemHasFoil
}
```

---

### Phase 4: Refactor processTooltip to Accept ItemStack (Optional Enhancement)

Currently `processTooltip()` takes only `List<Component>` and uses the static `currentItemHasFoil` field for foil detection. A cleaner API would pass the `ItemStack` directly:

```java
// New signature
public static List<Component> processTooltip(List<Component> tooltipLines, ItemStack stack) {
    if (tooltipLines.isEmpty()) return tooltipLines;
    if (isCraftedItem(tooltipLines)) return tooltipLines;

    boolean hasFoil = stack.hasFoil();  // direct access, no static field

    // ... rest of logic, replacing `currentItemHasFoil` with `hasFoil`
}
```

Then the event listener becomes:
```java
@SubscribeEvent(priority = EventPriority.LOW)
public void onTooltipRender(ItemTooltipRenderEvent.Pre event) {
    ItemStack stack = event.getItemStack();
    if (stack.isEmpty()) return;

    List<Component> original = event.getTooltips();
    List<Component> modified = LegacyItemHandler.processTooltip(original, stack);
    if (modified != original) {
        event.setTooltips(modified);
    }
}
```

This eliminates the mutable static field entirely, making the code thread-safe and easier to reason about.

**Further**: With `ItemStack` available, `processTooltip` could also use `stack.getHoverName()` instead of extracting the name from the tooltip's first line, and use `stack.getOrDefault(DataComponents.LORE, ...)` instead of scanning tooltip lines for rarity. However, the current approach works against the fully-rendered tooltip (which includes Wynntils additions), so using the tooltip lines is arguably more correct.

---

### Phase 5: Delete LegacyItemTooltipMixin

Once the event listener is working:

1. Delete `src/client/java/org/wynnvets/mixin/client/LegacyItemTooltipMixin.java`
2. Remove `"LegacyItemTooltipMixin"` from `src/client/resources/vetsmod.client.mixins.json`
3. Remove the `renderTooltip` `@Inject` from `LegacyHighlightMixin` (the foil capture)
4. Remove or deprecate `LegacyItemHandler.currentItemHasFoil` static field
5. Verify build and test

---

## Interaction with Wynntils' Tooltip Pipeline

Understanding the full tooltip rendering chain is important to avoid conflicts:

### Wynntils' Processing Order

1. **Minecraft** builds the initial tooltip `List<Component>` from `ItemStack.getTooltipLines()`
2. **Wynntils `ItemHandler`** annotates the item (attaches `WynnItem` annotation with parsed data)
3. **Wynntils `TooltipHandler`** rebuilds the tooltip from scratch using registered `TooltipComponent` providers (stat lines, identification ranges, powder slots, etc.)
4. **Wynntils features** (at `NORMAL` priority) may further modify via `ItemTooltipRenderEvent.Pre`:
   - Add percentage overlay `[61.7%]`
   - Add price info
   - Color rarity line
   - etc.
5. **VetsMod** (at `LOW` priority) receives the final tooltip and applies legacy item modifications

### What This Means for Legacy Item Detection

By the time vetsmod's listener fires:

- **The name line (index 0)** may have been modified by Wynntils. Wynntils typically preserves the item name but may append a percentage suffix like ` [61.7%]`. VetsMod's `processTooltip()` already handles this via `extractColoredSuffix()` and `stripPercentSuffix()` — no change needed.

- **The rarity line** may have been replaced or reformatted by Wynntils. VetsMod's `RARITY_PATTERN` (`^(Mythic|Fabled|...) Item(?: (\[\d+\]))?$`) should still match, because Wynntils preserves the rarity text format. Test this for each rarity tier.

- **The lore (DataComponents.LORE)** is NOT affected by tooltip events — `processTooltip()` only reads tooltip `List<Component>` lines, not the raw lore. So `hasBetaLegacyMarker()`, `hasMiscRarity()`, etc. work from the rendered tooltip, which should contain all the information.

- **Wynntils may add extra lines** (identification stats, reroll count, powder info). These appear between the name and the rarity/debug lines. VetsMod's rarity replacement scans bottom-up (`for (int i = lines.size() - 1; ...)`), so extra mid-tooltip lines don't interfere.

---

## Edge Cases and Risks

### 1. Tooltip for Non-Item Contexts

**Risk**: `ItemTooltipRenderEvent.Pre` fires for all item tooltips (inventory, hotbar, creative mode, recipe book, etc.). The current mixin hooks `GuiGraphics.setTooltipForNextFrame`, which captures an even broader set of tooltips (including non-item tooltips rendered by custom screens).

**Impact**: The event is actually **more targeted** than the mixin — it only fires for item tooltips, which is exactly what vetsmod needs. Non-item tooltips (e.g., button tooltips) won't trigger the listener.

### 2. Wynntils Disabled/Feature Toggled

**Risk**: If a user disables Wynntils' tooltip features, the event still fires (it's fired by the mixin, not by features). The tooltip content will be closer to vanilla MC + Wynncraft server formatting. VetsMod's detection should work on both Wynntils-processed and raw tooltips.

**Mitigation**: Test with Wynntils tooltip features disabled to verify `processTooltip()` handles unprocessed tooltips correctly (it should — the code was written to work with raw Wynncraft tooltips).

### 3. Event Cancellation by Wynntils

**Risk**: A Wynntils feature might cancel the tooltip event entirely (e.g., to render a custom tooltip screen). If cancelled before vetsmod's `LOW` priority runs, the listener won't fire.

**Impact**: Minimal — if Wynntils is replacing the tooltip with its own UI, vetsmod's modifications aren't needed anyway.

### 4. setTooltips() Creates Unmodifiable Copy

**Risk**: `event.setTooltips(list)` wraps the input in `Collections.unmodifiableList()`. VetsMod's `processTooltip()` returns a mutable `ArrayList`, which is then wrapped. This is fine — the list doesn't need to be mutable after this point.

### 5. Multiple Calls to setTooltips()

**Risk**: If another listener at `LOWEST` priority also calls `setTooltips()`, it would overwrite vetsmod's changes.

**Impact**: Unlikely — `LOW` is low enough that only deliberately late-binding code runs after. If this becomes an issue, use `LOWEST` instead.

### 6. The LegacyItemNameMixin Is Unaffected

**Important**: This migration addresses only `LegacyItemTooltipMixin` (tooltip content rewriting). `LegacyItemNameMixin` (which hooks `ItemStack.getHoverName()` to change the item's display name, affecting held-item HUD, chat hover text, etc.) is **not replaced** by this migration and must remain as a mixin. There is no Wynntils event for `getHoverName()`.

Similarly, `LegacyHighlightMixin`'s `renderSlot` injection (which draws the colored slot background) is not affected. Only its `renderTooltip` injection (the foil capture) is removed.

---

## The LegacyHotbarMixin Consideration

`LegacyHotbarMixin` on `Gui.renderSlot` draws highlight backgrounds on hotbar items. Wynntils has `HotbarSlotRenderEvent` which might replace this, but that's a separate investigation. This migration document only covers the tooltip mixin.

---

## Future Enhancement: ItemAnnotator Integration

After this migration, a natural next step is registering a custom `ItemAnnotator` via `Handlers.Item.registerAnnotator()` that caches legacy/enchanted/junk classification as an `ItemAnnotation` on the ItemStack. This would:

1. Run detection logic once per ItemStack (not on every tooltip render)
2. Provide a `LegacyItemAnnotation` that `processTooltip()`, `LegacyItemNameMixin`, and `LegacyHighlightMixin` all read from
3. Integrate with Wynntils' item identification pipeline

This is a larger change and should be tackled separately, but the tooltip event migration is a prerequisite.

---

## Complete Listener Code (Final Form)

```java
package org.wynnvets.items;

import com.wynntils.core.WynntilsMod;
import com.wynntils.mc.event.ItemTooltipRenderEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;

public final class WynntilsItemListener {

    private static final WynntilsItemListener INSTANCE = new WynntilsItemListener();

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onTooltipRender(ItemTooltipRenderEvent.Pre event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;

        List<Component> original = event.getTooltips();
        List<Component> modified = LegacyItemHandler.processTooltip(original, stack);
        if (modified != original) {
            event.setTooltips(modified);
        }
    }
}
```

---

## Testing Checklist

- [ ] Name-matched legacy items show gold name + "Legacy Item (Rarity)" in tooltip
- [ ] Misc-legacy items (Glass Bottle etc. with "Misc. Item") show correct label
- [ ] Beta legacy items (gold `Lv. min:` line) show "Beta Legacy Item"
- [ ] Alpha legacy items (gold `Lv. min:` + no rarity line) show "Alpha Legacy Item"
- [ ] Enchanted items (foil + not unenchanted) show "⬡ Enchanted Name" in tooltip
- [ ] Unenchanted whitelist items (Breathing Helmet etc.) are NOT marked enchanted
- [ ] Junk items (not in notjunk whitelist) show gold name + legacy label
- [ ] NotJunk whitelist items (Tanned Sunfish etc.) are NOT marked legacy
- [ ] Crafting rarity items show gold name + legacy label
- [ ] Crafted items (matching CRAFTED_PATTERN) are NOT modified
- [ ] Wynntils percentage suffix `[61.7%]` is preserved with correct color
- [ ] F3+H debug lines (resource ID, component count) appear below the legacy label
- [ ] Tooltip renders without lag (no per-frame allocation churn beyond existing)
- [ ] Hotbar tooltip works (not just inventory)
- [ ] Creative mode search results tooltip works
- [ ] `LegacyItemTooltipMixin` is deleted, mixin json updated
- [ ] `LegacyHighlightMixin` `renderTooltip` injection is deleted
- [ ] `currentItemHasFoil` static field is removed (or replaced with ItemStack param)
- [ ] Build clean with no mixin references to deleted class
