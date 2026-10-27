---
name: Legacy Items System
description: Exhaustive reference for vetsmod's legacy-item detection, tooltip rewriting, and gradient/sprite rendering — covers YAML definitions, mixins, config keys, and edge cases.
type: project
originSessionId: bb1ae987-4154-41e5-8e85-a49554d2accc
---
# Legacy Items — In-Depth Reference

vetsmod visually highlights old-format Wynncraft items ("legacy items") with a gradient background, a sprite overlay, a rewritten tooltip (gold name + "Legacy Item (Rarity)" label), and (where supported) a gold tooltip border. The system integrates with Wynntils event hooks and vanilla Minecraft mixins.

## 1. Key files

**Detection / tooltip logic:**
- [ItemDefinitions](../src/client/java/org/wynnvets/items/ItemDefinitions.java) — YAML loader + regex cache
- [LegacyItemHandler](../src/client/java/org/wynnvets/items/LegacyItemHandler.java) — `isLegacyItem()` 10-branch cascade + state fields
- [LegacyTooltipRenderer](../src/client/java/org/wynnvets/items/LegacyTooltipRenderer.java) — 11-branch tooltip rewriter
- [NewFormatRenderer](../src/client/java/org/wynnvets/items/NewFormatRenderer.java) — PUA / new-format component-tree manipulation
- [LegacyHighlightPainter](../src/client/java/org/wynnvets/items/LegacyHighlightPainter.java) — `paintIfLegacy()`, the guard-and-paint both highlight sites call (§5)
- [LegacyEnchantmentRenderer](../src/client/java/org/wynnvets/items/LegacyEnchantmentRenderer.java) — names the specific enchantment a legacy item carries (§4)
- [UnderrollDetector](../src/client/java/org/wynnvets/items/UnderrollDetector.java) — decides whether a `min_rerolls` item has too few rerolls; reads the count on page 1 and remembers it per Wynntils item (§4)
- [LegacyLoreText](../src/client/java/org/wynnvets/items/LegacyLoreText.java) — string-level tooltip/lore rules split out of the two classes above so the unit tests can reach them (neither can be class-loaded under test: both initialise `ItemStack`)
- [LegacyScreenshotHandler](../src/client/java/org/wynnvets/items/LegacyScreenshotHandler.java) — re-renders the modified tooltip to the clipboard (§4)

**Data:**
- [definitions.yml](../src/client/resources/definitions.yml) — 11 sections

**Config:**
- [VetsConfig](../src/client/java/org/wynnvets/config/VetsConfig.java)
- [VetsConfigKeys](../src/client/java/org/wynnvets/config/VetsConfigKeys.java) — the config key names
- [VetsConfigSchema](../src/client/java/org/wynnvets/config/VetsConfigSchema.java) — the `/wv config` key lists and valid-value lists (`VALID_SPRITES`)
- [VetsConfigDefaults](../src/client/java/org/wynnvets/config/VetsConfigDefaults.java) — the first-run defaults, the gradient, sprite and opacity ones included, which the jar's generated `config.yml` carries for `/wv config <key> default` ([ShippedConfig](../src/client/java/org/wynnvets/config/ShippedConfig.java) reads it)
- [LegacyItemStyle](../src/client/java/org/wynnvets/config/LegacyItemStyle.java) — resolves gradient/sprite from config
- [NamedColor](../src/client/java/org/wynnvets/config/NamedColor.java) — colour-name → RGB + alpha packing

**Mixins (3 legacy-item-specific out of 14 registered):**
- [LegacyHighlightMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHighlightMixin.java) — `AbstractContainerScreen#renderSlot` + `renderTooltip` (captures hover context)
- [LegacyHotbarMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java) — `Gui#renderSlot` (hands the hotbar slot to `LegacyHighlightPainter`; its whole body is that one call)
- [LegacyItemTooltipMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyItemTooltipMixin.java) — `GuiGraphics#setTooltipForNextFrame` (rewrites tooltip + gold border)

**Event listeners:**
- [LegacyHighlightEventListener](../src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java) — `SlotRenderEvent.Pre` at `LOWEST` priority (hands the container slot to `LegacyHighlightPainter`, overriding Wynntils `ItemHighlightFeature`)
- [LegacyTooltipEventListener](../src/client/java/org/wynnvets/listeners/LegacyTooltipEventListener.java) — sets hover context before tooltip
- [ServerConnectionListener](../src/client/java/org/wynnvets/listeners/ServerConnectionListener.java) — resets `newTooltipStylesAvailable` on disconnect

## 2. Detection cascade — `LegacyItemHandler.isLegacyItem(ItemStack)`

[LegacyItemHandler.isLegacyItem()](../src/client/java/org/wynnvets/items/LegacyItemHandler.java)

Guard conditions (return `false` early):
- `VetsConfigKeys.LEGACY_ITEM_HIGHLIGHTING` disabled
- `stack.isEmpty()`
- `isDetectionSuppressed()` — true when `isBlockedScreen()` is (whenever Wynntils reports `Models.Housing.isInEditMode()`, and otherwise when the screen title matches one of the 9 `blocked_screen_titles` patterns in `definitions.yml`: housing/island menus that abuse enchant glint as a UI selector), or when an item named in `inventory_suppressors` (the `Block Bank`) is anywhere in the player's inventory (scanned at most every 250 ms). `processTooltip` opens with the same guard
- `isCraftedItem(stack)`: a new-format player-crafted item (see "Crafted items" in §8). `processTooltip` bails on it too

Name is normalized via `ChatFormatting.stripFormatting()` then `normalizeName()` (`stripSupplementaryPua()` plus the trade-market trailing `À` strip) before matching. The `newFormatOverridden` flag suppresses the `definitions` and `misc_definitions` name matches (branches 1 & 2) when the item is new-format AND its name is in `new_format_override`; branch 3's `no_lore_legacy` match and the other branches still apply.

Ten independent branches — **any one** triggers legacy. The numbering matches
the `// 1.` … `// 10.` comments in the method body:

| # | Branch | Source | Extra gate |
|---|--------|--------|-----------|
| 1 | `isLegacy(name)` | `definitions` | not newFormatOverridden |
| 2 | `isMiscLegacy(name)` + `hasMiscRarity(lore)` | `misc_definitions` | not newFormatOverridden, lore contains "Misc. Item" |
| 3 | `isNoLoreLegacy(name)` | `no_lore_legacy` | `lore.isEmpty()` |
| 4 | `isPedestalWipedItem(stack)` | item ID + custom name | vanilla armour/weapon shell, no lore, `§a`/`§b`/`§d`/`§e` name prefix, name not in `not_pedestal` |
| 5 | `hasBetaLegacyMarker(lore)` | lore | gold "Lv. min: N" line, either capitalisation of `min` |
| 6 | `stack.hasFoil()` | NBT | not in `enchant_excluded_items`, name not in `unenchanted` |
| 7 | `hasJunkRarity(lore)` | lore | name not in `notjunk` |
| 8 | `hasCraftingRarity(lore)` | lore | — |
| 9 | `hasVintageBanner(lore)` | lore | the VINTAGE ribbon, read only from text in the `banner/ribbon` font |
| 10 | `UnderrollDetector.isUnderrolled(stack)` | `min_rerolls` + Wynntils `GearItem` + lore | `legacyItemUnderrolling` on; fewer rerolls than the name's threshold (an unidentified copy counts as 0); see "Underrolled items" in §4 |

## 3. definitions.yml — the 11 sections

File: [definitions.yml](../src/client/resources/definitions.yml). Its header comment describes the format and each section, so it can be edited without reading the code. Parsed by `ItemDefinitions.parse()` with SnakeYAML's safe loader: one mapping from section name to a list of entries, in the order of the table below (`---`-separated documents also load, and add up). Every entry but `enchant_excluded_items`' is a regex that must match the whole name (a `min_rerolls` entry pairs one, as `filter`, with a `threshold`); a pattern holding a regex backslash goes in single quotes (`'^\?\?\?$'`), since inside double quotes only YAML's own escapes are legal. A section repeated within one mapping is a parse error (so the build's real-file test fails); an empty entry, an unknown section, or a document that is not a mapping is ignored, and every name and title predicate (all but `isEnchantExcludedItem`) answers false for `null`. All patterns are pre-compiled to `java.util.regex.Pattern` once at startup. **No runtime reload** — requires restart to pick up edits.

| Section | Purpose | Gate logic |
|---------|---------|-----------|
| `definitions` | Primary legacy name list — old armor tiers, keys, holiday items, autographs, potions, and modern-format items Wynncraft has since removed (Vibrant Augment, the broken Warped Mount saddles) | branch 1 |
| `misc_definitions` | Items requiring "Misc. Item" rarity (Carved Quartz Block, Firefly Wing, Mushrooms, and the exact-name Misc Bucket items Stitched Flesh, Aqua Dust, Flamingo Feather, Uncraftable Potion, Cralozide Crystal, Sunflower Seeds, Pigman Meat, Broken Amulet, Shattered Crystal, Cobblestone, Lava Essence) — collides with modern misc | branch 2 |
| `no_lore_legacy` | Legacy items that lost their lore (old keys, vanilla materials, Grian's Ocean Map, the loreless Wolf's Fang) — only legacy when lore is empty | branch 3 |
| `min_rerolls` | `filter`/`threshold` pairs: items that can no longer be obtained with fewer rerolls than the threshold (Solidified Chestplate, 2). The highest matching threshold applies; an entry that is not a mapping with a non-empty string `filter` and a positive whole-number `threshold` is dropped | branch 10 |
| `new_format_override` | Names that exist in both old & new formats (e.g. Skeleton Key) — when item is new-format, its `definitions` / `misc_definitions` name match is suppressed (`no_lore_legacy` still applies) | gate on branches 1 & 2 |
| `unenchanted` | Items that legitimately have foil in modern Wynncraft (e.g. ???, Ability Shard, Breathing Helmet, Mythic Everlasting Pufferfish, Liquid Emerald, Shiny, Transcriber) | exclusion in branch 6 |
| `enchant_excluded_items` | Minecraft namespaced IDs (`minecraft:experience_bottle`, `minecraft:enchanted_book`) — always have vanilla foil | exclusion in branch 6 |
| `notjunk` | Modern junk-tier items (e.g. Leather, Tanned Sunfish, Ghostly, Roasted Flesh, Vines, Rubble) | exclusion in branch 7 |
| `not_pedestal` | Names that must never be treated as a pedestal-wiped shell (merchant items) | exclusion in branch 4 |
| `blocked_screen_titles` | Screen titles where the whole system bails (housing/island menus that abuse enchant glint as a UI selector) | `isBlockedScreen()` guard |
| `inventory_suppressors` | Names of items (the housing `Block Bank`) whose presence anywhere in the player's inventory makes the whole system bail, in the inventory and in any open container | `isDetectionSuppressed()` guard |

**Deliberately not listed** (checked and kept out; re-check before adding one): The Juggernaut, Sacred and Quicksand Crossers; the Replica Hallowynn Mask, a current item (so the Hallowynn Mask's entry has no optional `Replica ` prefix); Ghostly armour (Boots, Cap, Pants, Tunic), until dye colours can be told apart; Foehn Wind (sold by a Nesaak merchant); Short Cutter and Silver Short Spear (found in the Regular Island cave). Unconfirmed: Raw Cod is left out of `misc_definitions` as possibly still obtainable, and Firefly Wing is in it although it may be too.

`ItemDefinitions` responsibilities: YAML parsing, regex pre-compilation into 9 static `List<Pattern>` fields plus one `Set<String>` (`enchantExcludedItems`) and one `List<MinRerolls>` (`minRerollEntries`), and 11 lookups (`isLegacy`, `isMiscLegacy`, `isNoLoreLegacy`, `isUnenchanted`, `isEnchantExcludedItem`, `isNotPedestal`, `isNotJunk`, `isNewFormatOverride`, `isBlockedScreenTitle`, `isInventorySuppressor`, and `minRerolls`, which returns a threshold rather than a boolean). Loaded from `VetsmodClient.onInitializeClient()` after `VetsConfig.load()`.

## 4. Tooltip rewriting — `LegacyTooltipRenderer.processTooltip()`

Entry point: called from `LegacyItemTooltipMixin` via `LegacyItemHandler.processTooltip()` at render time. 11 branches after the guard bail, **first match wins**:

| # | Trigger | Action |
|---|---------|--------|
| 0 | highlighting off / empty / blocked / new-format player-crafted (`isCraftedItem(currentItemStack)`) | bail, no rewrite |
| 1 | line 0 is blank/whitespace (Wynntils `ItemStatInfoFeature` rebuilds) | recover name from `currentItemStack.getHoverName()`, try new-format LEGACY-box insert |
| 2 | `isLegacy(name)` | gold name (new-format: recolour the emblem/frame name line gold, found by font marker, and insert the LEGACY box; old-format: replace line 0 and swap the rarity line); an item that also passes branch 6's foil test gets its "Enchanted" prefix too, and an underrolled one (branch 10's test) its "Underrolled" prefix |
| 3 | `isMiscLegacy(name)` + misc rarity | same as branch 2 |
| 4 | `isNoLoreLegacy(name)` + empty lore | gold name; rarity inferred from `tooltip_style` component or `§`-colour prefix; insert "Legacy Item (Rarity)" before debug lines |
| 4b | `isPedestalWipedItem(currentItemStack)` | gold name; label = "Legacy Item (Pedestal-Wiped)" |
| 5 | beta-legacy marker ("Lv. min" gold line) | gold name; label "Alpha Legacy Item" when no standard rarity line exists (a `tooltip_style` rarity is appended in parentheses if present), else "Beta Legacy Item (Rarity)"; a foil item that passes branch 6's test gets the "Enchanted" prefix too, as in branch 2 |
| 6 | `hasFoil` + not excluded/unenchanted | prepend "Enchanted" in gold (old-format: `buildGoldName` adds a white ⬡ before it; new-format: `NewFormatRenderer.applyPrefixToNewFormatNameLine` rewrites the name literal through `deepPrefixName`) + rarity swap (old-format; new-format inserts the LEGACY box instead) |
| 7 | junk rarity + not in `notjunk` | gold name + rarity swap |
| 8 | crafting rarity | gold name + rarity swap |
| 9 | `hasVintageBanner(tooltipLines)` | same as branch 2 (an enchanted item has already been taken by branch 6) |
| 10 | `isUnderrolled()` (`UnderrollDetector`, gated by `legacyItemUnderrolling`) | same as branch 2, with an "Underrolled" prefix; new-format: the reroll box turns gold |

**Rarity swap** (`replaceRarityLines`): scans lore bottom-up for regex matching `^(Mythic\|Fabled\|Set\|Legendary\|Rare\|Unique\|Normal\|Junk\|Misc\.\|Crafting) [Ii]tem(?: (\[\d+\]))?$`, captures tier + optional count. Falls back to the `tooltip_style` component path (mapped: common, unique, rare, set, legendary, fabled, mythic, crafted) when no lore rarity is present. Label built as `"Legacy Item (Tier)"` in `ChatFormatting.GOLD`, inserted at `debugLinesStart()` — above F3+H item-id/component-count lines.

**Wynntils percentage suffix** (e.g. `[61.7%]`) is extracted and re-appended to preserve Wynntils stat output.

**The new-format rewrite and the enchantment, by branch.** `rewriteLegacy` is the rewrite of branches 2, 3, 6, 9 and 10. If `NewFormatRenderer.isNewFormatItem` says the tooltip is new-format, `rewriteNewFormat` gives the name line its prefix (`applyPrefixToNewFormatNameLine` with "Enchanted ", "Underrolled " or "Enchanted Underrolled ", from `namePrefix`) or else the gold recolour (`recolorNewFormatNameLine`), inserts the LEGACY box (`insertLegacyBoxLine`) and, for an enchanted item, calls `appendEnchantmentBlock`. It returns `true` only once the box is in; otherwise `rewriteLegacy` goes on to the old-format rewrite (`rewriteOldFormat`). Branch 1 calls `rewriteNewFormat` alone, for a new-format legacy item whose line 0 is blank. The old-format rewrite takes the same prefix through `buildGoldName`'s `namePrefix`, so a prefix shows whichever branch took the item. An item counts as enchanted (`isEnchanted`) when it has foil, its item id is not on the `enchant_excluded_items` list (both are folded into `LegacyItemHandler.currentItemHasFoil`), and its name is not on the `unenchanted` list. That is the test branch 6 opens on.

`appendEnchantmentBlock` returns at once while `legacyItemShowEnchantments` is off (§6) and otherwise hands the tooltip to `LegacyEnchantmentRenderer.showEnchantment` (below). It runs only once a branch has decided the item is enchanted and legacy: `rewriteNewFormat` calls it under the `isEnchanted` test and `rewriteOldFormat` when its caller passes that test's result, so it runs from branches 1, 2, 3 and 5 for an enchanted item and from branch 6 always. Branches 4, 4b, 7 and 8 never call it, and branches 9 and 10 never reach it: an enchanted item has already been taken by branch 6.

**New-format tooltip rewriting** (`NewFormatRenderer`): detects items via presence of `tooltip/emblem/frame` or `banner/box` fonts. `isNewFormatItem` sets the sticky `newTooltipStylesAvailable` flag as a side effect. The LEGACY chip, built by `legacyBox()` (a gold `banner/box` run of `LEGACY_BOX_BACKGROUND` with the black lettering `LEGACY_BOX_FOREGROUND` as a nested child), is inserted, with a delimiter node, before the first rarity box in the Component tree via a 3-phase recursive descent (direct child → banner/symbol-sibling heuristic → recursive).

Only the §3 lists come from YAML; the remaining detection rules (e.g. lore lines, foil, item IDs, name colour codes, fonts) and the labels themselves (e.g. the "Legacy Item (…)" family, "Enchanted", "Underrolled", "LEGACY ENCHANTMENTS") are hardcoded in Java.

**Spoiler handling (`handleSpoilers` config) is unrelated to legacy items** — it encodes `||spoiler||` markers you type before sending (`GuildChatDispatcher` via `SpoilerCodec`) and renders received encoded spoilers via `SpoilerRewriter`/`SpoilerFormatter`, not item tooltips.

### `LegacyEnchantmentRenderer`

[LegacyEnchantmentRenderer](../src/client/java/org/wynnvets/items/LegacyEnchantmentRenderer.java) names the specific vanilla enchantment a legacy item carries, read from the stack's `minecraft:enchantments` component. Wynncraft hides that component from new-format tooltips through `tooltip_display`, but it stays on the stack, and modern items carry it with an empty map, so a non-empty map is itself a legacy signal. `showEnchantment` tries these forms, in order:

1. **A paged new-format item:** a `LEGACY ENCHANTMENTS` block on the powder page (found by its socket sprites, or, for an item with no sockets at all (0/0), which draws none, by reading the POWDER SOCKETS header's glyphs back; the block then goes after the last content line above the page indicator), under Wynncraft's POWDER SOCKETS block and drawn like it: a boxed header with a right-aligned `1/1` count, a blank line, then one socket-sprite entry such as `◈ Knockback I`.
2. **A V2-era item**, which has no pages and leaves `minecraft:enchantments` visible: Minecraft already draws an enchantment line without its numeral, and the level is added to that line in place.
3. **An unidentified item**, new-format but pageless: a plain `- Legacy:` / `- Enchantment: …` pair at the end of the tooltip's content, shaped like Wynntils' item-guess block.

The boxed header uses the PUA box alphabets `NewFormatRenderer.LEGACY_BOX_BACKGROUND` documents, and its kerning and right-alignment are measured at runtime from the resource pack's glyph widths.

### Underrolled items — `UnderrollDetector`

**The rule.** Some items can no longer be obtained with fewer than a given number of rerolls (every current Solidified Chestplate has at least 2; user, 2026-10-27). `definitions.yml`'s `min_rerolls` lists them; a copy below its threshold predates the change, so it is legacy (check 10, branch 10). An unidentified copy counts as 0 rerolls, since identifying it would give 0 (user's call). Everything stops with `legacyItemUnderrolling` off.

**How.** The reroll counter is a lore line on page 1 only, and absent at 0 rerolls. The count comes from Wynntils' `WynnItemParser.parseItemStack` on the current stack, read only when that stack is page 1, and is remembered per Wynntils `GearItem` so pages 2 and 3 are judged too. `UnderrollDetector`'s class Javadoc has the glyph encoding, why `GearItem.getRerollCount()` and the parsed page number are not used, and the caching.

**Display.** Every page: the highlight, the gold border, and the gold name "Underrolled X" with the LEGACY box. Page 1 of a new-format tooltip (`LegacyTooltipRenderer.markRerollBox`, whichever branch took the item): `NewFormatRenderer.recolorRerollBox` turns the reroll line's `banner/box` chip and the badge's tinted left half gold, leaving the black digits and the badge's untinted right half alone; when the line is absent (0 rerolls) on an identified item, `insertZeroRerollLine` adds one reading 0 after the last divider, built with Wynntils' `BannerBoxFont.buildMessage` and laid out like Wynncraft's one-digit line, gold in the same places.

**Known misses.** An item never shown on page 1 during its `GearItem`'s life stays UNKNOWN, which is never flagged. The item dump's `vetsmodAnalysis.underroll` line shows the verdict, its source, the count and the threshold (`UNDERROLLED (stats page, rerolls 1, min 2)`).

### `LegacyScreenshotHandler`

[LegacyScreenshotHandler](../src/client/java/org/wynnvets/items/LegacyScreenshotHandler.java) replaces Wynntils' item screenshot of a legacy item. Wynntils' `ItemScreenshotFeature` captures the tooltip before vetsmod rewrites it, so its image has no gold name and no LEGACY box. When Wynntils' screenshot keybind is pressed in an inventory, `LegacyTooltipEventListener` arms a one-shot latch (`LegacyItemHandler.notifyScreenshotKeyPressed`), but only if the hovered slot holds a legacy item (`LegacyItemHandler.isLegacyItem`); a press over an empty slot, no slot or a non-legacy item arms nothing. On the next tooltip pass that rewrites a legacy item, `LegacyItemTooltipMixin` calls `LegacyItemHandler.screenshotIfRequested`, which consumes the latch, renders the final tooltip offscreen and copies the image to the clipboard 250 ms later, after Wynntils' own copy. The latch (`ScreenshotLatch`) expires one second after the press, so a press that no rewriting pass picks up, such as a legacy item shown in the compare view, which bypasses the mixin, cannot fire on a later tooltip. The render uses Wynntils' own capture geometry: the tooltip is drawn at (10, 10), and the measured size gets 12 more units on each axis for the background sprite's reach (3 padding plus a 9 margin). Wynntils 4.2.13 and later forces its (10, 10) positioner onto every tooltip drawn while its own capture's readback is in flight, which always includes this one, so any other position would be overridden; at the old (4, 4) the override cut off the image's right edge and last line.

## 5. Visual rendering — gradient + sprite

Drawn from **one place** — [LegacyHighlightPainter.paintIfLegacy(guiGraphics, stack, x, y)](../src/client/java/org/wynnvets/items/LegacyHighlightPainter.java) — reached from **two call sites**, which is the whole of each one's body. It was the same guard chain and the same two draw calls written twice; `grep -rn 'fillGradient\|drawSprite' src/client/java` now returns the painter and nothing else. The painter takes `(int x, int y)` rather than a `Slot`, because the mixin only has ints and a `Slot` parameter would pull `net.minecraft.world.inventory.Slot` into a `Gui` mixin. It has no unit test and cannot have one: both draw statements need a live `GuiGraphics`, one of them also needs Wynntils' `RenderUtils` / `CustomColor` (`modCompileOnly`, absent at test runtime), and even the two guards take an `ItemStack`, which nothing under `src/test` can construct without the Minecraft bootstrap.

**Hotbar:** [LegacyHotbarMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java) — `@Inject(method = "renderSlot", at = @At("HEAD"))` on `net.minecraft.client.gui.Gui`, passing the injected `x` / `y` straight through. The mixin imports no Wynntils class and no `VetsConfig` any more; a mixin's imports are what it transplants into its target, so that is worth keeping true.

**Containers:** [LegacyHighlightEventListener](../src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java) — subscribes to Wynntils `SlotRenderEvent.Pre` at `EventPriority.LOWEST` so it runs **after** Wynntils' `ItemHighlightFeature` (at `HIGH`), effectively overriding the stock Wynntils rarity highlight. It passes `slot.x` / `slot.y` and `event.getGuiGraphics()`, which is a plain getter over a final field, so reading it before the guards rather than after costs one field read.

Guard chain, both sites, inside the painter: `stack.isEmpty()` → `LegacyItemHandler.isLegacyItem(stack)`. The second subsumes the `LEGACY_ITEM_HIGHLIGHTING` check, since `isLegacyItem` opens with it — the hotbar mixin used to repeat that check itself, and dropping the repeat is what made the two chains the same function.

Drawing sequence:
1. `guiGraphics.fillGradient(x, y, x+16, y+16, topColor, bottomColor)` — top/bottom colours from `LegacyItemStyle.getBackgroundGradientTopColor()` / `…BottomColor()` with per-colour opacity packed into ARGB alpha
2. `RenderUtils.drawSprite(guiGraphics, LegacyItemStyle.getForegroundTexture(), CustomColor.fromARGBInt(LegacyItemStyle.getForegroundColor()), x - 10, y - 10, 36, 36)` — Wynntils' own sprite renderer, 36×36 at offset −10, taking a `com.wynntils.utils.render.Texture` enum value from `LegacyItemStyle`'s `HIGHLIGHT_*` table (fallback `Texture.HIGHLIGHT_WYNN`). There is no `blit` call and no raw texture path anywhere in the repo

`LegacyHighlightMixin` itself **does not draw** — it only captures hover state (`currentItemHasFoil`, `currentItemStack` for the tooltip pipeline, `currentHoveredSlot` for the item-dump tool) in its `renderTooltip` head and, via a server-compat patch in its `renderSlot` head, sets `newTooltipStylesAvailable` if it sees a `tooltip_style` component.

Sprites (`LEGACY_ITEM_FOREGROUND_SPRITE`): `wynn`, `tag`, `circle_transparent`, `circle_opaque`, `circle_outline_large`, `circle_outline_small`, `box_transparent`, `box_opaque`, `box_gradient_1`, `box_gradient_2`.

Colours resolved via `NamedColor.COLORS` map: Minecraft formatting codes + rarity colours + CSS colours + custom `legacy_orange` (0xF0501E) + `transparent`. Opacity is clamped 0–100 and packed as `(alpha << 24) | (rgb & 0xFFFFFF)`.

**Supporter glints (`SHOW_SUPPORTER_GLINTS`) are unrelated to legacy items**: `NametagMixin` animates supporter nametags and `AnimatedChatMixin` animates marked chat lines (supporter pills, and `/wv list` roster names marked by `ListFetcher`); none of it touches item slots or tooltips.

## 6. User-facing config keys (all via `/wv config`)

Registered in `VetsConfigSchema.USER_CONFIG_KEYS`. Validation goes through `LegacyItemStyle.isValidColor()` / `isValidSprite()`; opacity clamped 0–100. Persisted to `vetsmod/storage/config.json`.

| Key | Type | Default |
|-----|------|---------|
| `legacyItemHighlighting` | bool | `true` |
| `legacyItemBackgroundGradientTop` | colour name | `orange` |
| `legacyItemBackgroundGradientTopOpacity` | int 0-100 | `69` (≈ old 0xB0 alpha) |
| `legacyItemBackgroundGradientBottom` | colour name | `crimson` |
| `legacyItemBackgroundGradientBottomOpacity` | int 0-100 | `100` |
| `legacyItemForegroundSprite` | sprite name | `box_gradient_2` |
| `legacyItemForegroundColor` | colour name | `orange` |
| `legacyItemShowEnchantments` | bool | `true` |
| `legacyItemUnderrolling` | bool | `true` |

## 7. Data flow summary

**Startup:** `VetsmodClient.onInitializeClient()` → `VetsConfig.load()` → … → `ItemDefinitions.load()` (compiles regex); then, once the game has started, `VetsmodClient.onClientStarted()` → `WynntilsEventListener.register()`, which registers the legacy tooltip and highlight listeners on Wynntils' event bus.

**Hotbar frame:** vanilla `Gui.renderSlot()` → `LegacyHotbarMixin` HEAD → `LegacyHighlightPainter.paintIfLegacy()` → `isLegacyItem()` → 10-branch cascade → if true draw gradient + sprite → vanilla continues.

**Container frame:** `AbstractContainerScreen.renderSlot()` runs → `LegacyHighlightMixin` HEAD sets `newTooltipStylesAvailable` if the slot's item has a `tooltip_style` component (the mixin captures hover state in its separate `renderTooltip` HEAD hook) → Wynntils `SlotRenderEvent.Pre` fires → `LegacyHighlightEventListener` (LOWEST) → `LegacyHighlightPainter.paintIfLegacy()` → gradient + sprite.

**Tooltip frame:** `GuiGraphics.setTooltipForNextFrame()` → `LegacyItemTooltipMixin` HEAD → reentry guard → `LegacyTooltipRenderer.processTooltip()` → 11-branch cascade → if modified: cancel vanilla + re-invoke with modified list, set gold border (only if `newTooltipStylesAvailable`).

**Caching:** regex patterns compiled once; colour/sprite resolution runs every frame (cheap — concurrent hash map lookups); PUA/new-format detection runs every tooltip (no memoization); the underroll verdict is memoized per stack and per Wynntils item (see "Underrolled items" in §4).

**State fields** on `LegacyItemHandler`:
- `currentItemHasFoil`, `currentItemStack`, `currentHoveredSlot` — hover context; `LegacyHighlightMixin`'s `renderTooltip` hook sets and clears all three, and `LegacyTooltipEventListener` (Wynntils `ItemTooltipRenderEvent.Pre`) sets the first two
- `lastProcessedWasLegacy` — renderer → tooltip mixin signal for gold-border override
- `newTooltipStylesAvailable` — sticky per-session flag, reset by `ServerConnectionListener` on disconnect; suppresses gold tooltip border on old servers that lack the new resource pack

## 8. Edge cases & quirks

- **`new_format_override`**: an item like Skeleton Key exists in old (legacy) and new (modern) formats with the same display name. When `isNewFormatItem(stack)` (any supplementary code point in custom name, `cp >= 0x10000`) AND the name is in this list, `isLegacyItem`'s branches 1 & 2 (`processTooltip`'s Branches 2 & 3) are skipped; `no_lore_legacy` still applies.
- **`enchant_excluded_items`**: `minecraft:experience_bottle` / `minecraft:enchanted_book` always have vanilla foil — excluded by Minecraft registry ID, not name.
- **Seaskipper ticket UI element**: the V.S.S. Seaskipper menu draws each destination's ticket as a `name_tag` named e.g. "Galleon's Graveyard Pass for 16<coin>", with lore "Click to fast-travel to ...". It is a UI element, not an item, so the `definitions` entry for the Galleon pass carries a `(?! for \d)` lookahead that rejects the cost suffix, while a real pass ("Galleon's Graveyard Pass", or any `... Boat Pass`) stays legacy.
- **`notjunk`**: modern junk-tier farming materials (e.g. Leather, Tanned Sunfish, Roasted Flesh, Vines, Rubble, Ghostly*, Dusty Rum Bottle, Golden Teeth) — excluded from branch 7.
- **`unenchanted`**: items that legitimately have foil in modern Wynncraft — e.g. `???`, Ability Shard, `Breathing Helmet *`, `*Liquid Emerald*`, `*Mythic Everlasting Pufferfish*`, `Shiny *`, `*Transcriber` (a `*` marks where the pattern's wildcard sits) — bypass branch 6.
- **Alpha vs Beta legacy**: both triggered by gold "Lv. min: N" lore line (the `min` is matched case-insensitively: Dental Floss reads "Lv. Min: 26"; the modern "Combat Lv. Min: N" never matches, the pattern being anchored at `Lv`). Alpha = no standard rarity line present; Beta = has one. Label differs accordingly.
- **VINTAGE ribbon**: Wynncraft opens a legacy item's lore with a ribbon spelling VINTAGE (seen on the Llevigar Pinot). Two items share that name: the legacy one, which carries the ribbon and "Heal: 1000", and a modern one with "Right-Click to drink", "Heal: 500" and "Emergency Heal: 2000", which is not legacy (user, 2026-10-25). So the Pinot has no `definitions` name entry, and this branch alone marks the legacy one. The line is a `banner/ribbon` run (caps U+E060 and U+E062, one glyph per letter at U+E030 + index, a U+CFFFF kern before each) under a black default-font overlay of the same word at U+E000 + index. `hasVintageBanner` reads only the `banner/ribbon` text and `LegacyLoreText.isVintageRibbon` decodes it, so the word in plain lore text, or the same glyphs in the `banner/box` fill run, never count. Branch 9 of both cascades, so an item no name list covers is still caught. `/wv debug` item dumps report it as `hasVintageBanner`.
- **Unidentified gear**: name contains BMP PUA lock icon; `stripSupplementaryPua()` normalises it before name matching. No separate identified-vs-unidentified branching.
- **Crafted items**: a new-format crafted item is never legacy, because the crafter names it and any name list can match by accident (a crafted potion named "Treat" matched `definitions`' `^Treat.*$`). `LegacyItemHandler.isCraftedItem` tells one by its `minecraft:crafted` `tooltip_style` (the style of Wynntils' `GearTier.CRAFTED`, while its ingredient and material guides use `profession_ingredient` / `profession_material`). Both cascades bail on it before any branch: `isLegacyItem` and branch 0 of `processTooltip`. `/wv debug` item dumps report it as `isCraftedItem`. An old-format crafted item would by definition be legacy, so nothing exempts one; none is known to exist (user, 2026-10-27). An earlier tooltip-only exemption for an old-format "Crafted Helmet [30/30 Durability]" lore line was removed for that reason.
- **Wynntils `ItemStatInfoFeature` rebuild**: can blank out line 0. Branch 1 of tooltip renderer recovers the name from `currentItemStack.getHoverName()` and attempts new-format LEGACY-box insertion.
- **Block Bank (housing edit mode)**: the `Block Bank` (an `ender_chest` named "Block Bank", lore "A portable version of the block bank") is the housing editing tool and is carried in the inventory while in edit mode. If one is in the player's inventory, nothing is legacy, in the inventory or in any open container: `isDetectionSuppressed()` returns true for every item. This is the dependable signal for housing edit mode because Wynntils' `isInEditMode()` is set from a chat line ("You are now in housing edit mode") and stays false when edit mode was entered without that line reaching it (the likeliest reason a Block of Quartz was highlighted in a reported edit-mode screenshot, whose inventory shows a Block Bank). The inventory scan needs live `ItemStack`s and cannot run under test; the name match (`ItemDefinitions.isInventorySuppressor`) is tested.
- **Blocked screens**: housing edit mode (`Models.Housing.isInEditMode()`) is checked first, then the screen title against `definitions.yml`'s `blocked_screen_titles` (9 patterns — Island Settings/Rules/Weather, Background Song, Select a Song, Permission Groups, `Permissions for …`, `Available … Locations`, `Move here!`). These menus abuse enchant glint as UI selectors, so the whole system bails on them.
- **Old-server compat**: `newTooltipStylesAvailable` starts false; set true as soon as any item with `tooltip_style` or new-format font is seen. Gold tooltip border is suppressed until then to avoid garish fallback colours on pre-update servers. Reset on disconnect.
- **Wynntils percentage suffix** (`[61.7%]`): detected via `\s*(\[\d+\.?\d*%\])$`, extracted before rewriting, re-appended after.

## 9. Related config that isn't legacy-specific

- `handleSpoilers` (tri-state) — chat `||spoiler||` handling (encode outbound, render inbound), **not** item tooltips.
- `showSupporterGlints` — supporter glint on nametags, pills, and the `/wv list` roster; independent of legacy rendering.

## 10. Wynncraft item era history (background)

Why detection branches look the way they do — each era left a distinct tooltip fingerprint:

- **Alpha era** — no rarity line at all. Items had hex-UUIDs as `&f` (white) names, `&5` (dark purple) damage (weapons) or defense (armour), and `&6` (gold) `Lv. min:` lines. Detected via branch 5 (`hasBetaLegacyMarker`) when no standard rarity line is present → labelled "Alpha Legacy Item" (§4 has the `tooltip_style` case).
- **Beta era** — introduced rarity tiers: `&e` Unique, `&5` Rare, `&b` Legendary, and possibly `&a` Set. Reused the `&5` damage/defense + `&6` level format, sometimes with `&7` (gray) statistics. Detected via branch 5 when a standard rarity line *is* present → labelled "Beta Legacy Item (Rarity)".
- **Release era** — added elemental damages, more complex tooltips, and the `&c` Fabled and `&5` Mythic rarities. This is the format the bulk of `definitions.yml` patterns target.
- **Fruma / 2.1 era (current "new format")** — completely new rendering relying heavily on resource-pack fonts and Unicode PUA glyphs (`tooltip/emblem/frame`, `banner/box`). Removed the Set rarity. Drives `NewFormatRenderer`, the `newTooltipStylesAvailable` sticky flag, the `new_format_override` YAML section, and `stripSupplementaryPua()` name normalization.
