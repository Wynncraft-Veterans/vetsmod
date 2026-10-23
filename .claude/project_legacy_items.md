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
- [LegacyItemHandler](../src/client/java/org/wynnvets/items/LegacyItemHandler.java) — `isLegacyItem()` 8-branch cascade + state fields
- [LegacyTooltipRenderer](../src/client/java/org/wynnvets/items/LegacyTooltipRenderer.java) — 9-branch tooltip rewriter
- [NewFormatRenderer](../src/client/java/org/wynnvets/items/NewFormatRenderer.java) — PUA / new-format component-tree manipulation
- [LegacyHighlightPainter](../src/client/java/org/wynnvets/items/LegacyHighlightPainter.java) — `paintIfLegacy()`, the guard-and-paint both highlight sites call (§5)
- [LegacyEnchantmentRenderer](../src/client/java/org/wynnvets/items/LegacyEnchantmentRenderer.java) — names the specific enchantment a legacy item carries (§4)
- [LegacyScreenshotHandler](../src/client/java/org/wynnvets/items/LegacyScreenshotHandler.java) — re-renders the modified tooltip to the clipboard (§4)

**Data:**
- [definitions.yml](../src/client/resources/definitions.yml) — 9 sections

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
- `isBlockedScreen()` — true whenever Wynntils reports `Models.Housing.isInEditMode()`, and otherwise when the screen title matches one of the 9 `blocked_screen_titles` patterns in `definitions.yml` (housing/island menus that abuse enchant glint as a UI selector)

Name is normalized via `ChatFormatting.stripFormatting()` then `normalizeName()` (`stripSupplementaryPua()` plus the trade-market trailing `À` strip) before matching. The `newFormatOverridden` flag suppresses the `definitions` and `misc_definitions` name matches (branches 1 & 2) when the item is new-format AND its name is in `new_format_override`; branch 3's `no_lore_legacy` match and the other branches still apply.

Eight independent branches — **any one** triggers legacy. The numbering matches
the `// 1.` … `// 8.` comments in the method body:

| # | Branch | Source | Extra gate |
|---|--------|--------|-----------|
| 1 | `isLegacy(name)` | `definitions` | not newFormatOverridden |
| 2 | `isMiscLegacy(name)` + `hasMiscRarity(lore)` | `misc_definitions` | not newFormatOverridden, lore contains "Misc. Item" |
| 3 | `isNoLoreLegacy(name)` | `no_lore_legacy` | `lore.isEmpty()` |
| 4 | `isPedestalWipedItem(stack)` | item ID + custom name | vanilla armour/weapon shell, no lore, `§a`/`§b`/`§d`/`§e` name prefix, name not in `not_pedestal` |
| 5 | `hasBetaLegacyMarker(lore)` | lore | gold "Lv. min: N" line |
| 6 | `stack.hasFoil()` | NBT | not in `enchant_excluded_items`, name not in `unenchanted` |
| 7 | `hasJunkRarity(lore)` | lore | name not in `notjunk` |
| 8 | `hasCraftingRarity(lore)` | lore | — |

## 3. definitions.yml — the 9 sections

File: [definitions.yml](../src/client/resources/definitions.yml). Parsed by `ItemDefinitions.parse()` with SnakeYAML's safe loader: a stream of YAML documents separated by `---`, each a mapping from section name to a list of patterns. A pattern holding a regex backslash goes in single quotes (`'^\?\?\?$'`), since inside double quotes only YAML's own escapes are legal; an unknown section, or a document that is not a mapping, is ignored. All patterns are pre-compiled to `java.util.regex.Pattern` once at startup. **No runtime reload** — requires restart to pick up edits.

| Section | Purpose | Gate logic |
|---------|---------|-----------|
| `unenchanted` | Items that legitimately have foil in modern Wynncraft (e.g. ???, Ability Shard, Breathing Helmet, Mythic Everlasting Pufferfish, Liquid Emerald, Shiny, Transcriber) | exclusion in branch 6 |
| `definitions` | Primary legacy name list — old armor tiers, keys, holiday items, autographs, potions | branch 1 |
| `no_lore_legacy` | Legacy items that lost their lore (old keys, vanilla materials, Grian's Ocean Map) — only legacy when lore is empty | branch 3 |
| `misc_definitions` | Items requiring "Misc. Item" rarity (Carved Quartz Block, Firefly Wing, Mushrooms) — collides with modern misc | branch 2 |
| `not_pedestal` | Names that must never be treated as a pedestal-wiped shell (merchant items) | exclusion in branch 4 |
| `notjunk` | Modern junk-tier items (e.g. Leather, Tanned Sunfish, Ghostly, Roasted Flesh, Vines, Rubble) | exclusion in branch 7 |
| `enchant_excluded_items` | Minecraft namespaced IDs (`minecraft:experience_bottle`, `minecraft:enchanted_book`) — always have vanilla foil | exclusion in branch 6 |
| `new_format_override` | Names that exist in both old & new formats (e.g. Skeleton Key) — when item is new-format, its `definitions` / `misc_definitions` name match is suppressed (`no_lore_legacy` still applies) | gate on branches 1 & 2 |
| `blocked_screen_titles` | Screen titles where the whole system bails (housing/island menus that abuse enchant glint as a UI selector) | `isBlockedScreen()` guard |

`ItemDefinitions` responsibilities: YAML parsing, regex pre-compilation into 8 static `List<Pattern>` fields plus one `Set<String>` (`enchantExcludedItems`), and 9 lookup predicates (`isLegacy`, `isMiscLegacy`, `isNoLoreLegacy`, `isUnenchanted`, `isEnchantExcludedItem`, `isNotPedestal`, `isNotJunk`, `isNewFormatOverride`, `isBlockedScreenTitle`). Loaded from `VetsmodClient.onInitializeClient()` after `VetsConfig.load()`.

## 4. Tooltip rewriting — `LegacyTooltipRenderer.processTooltip()`

Entry point: called from `LegacyItemTooltipMixin` via `LegacyItemHandler.processTooltip()` at render time. 9 branches after the guard bail, **first match wins**:

| # | Trigger | Action |
|---|---------|--------|
| 0 | highlighting off / empty / blocked / "Crafted" prefix | bail, no rewrite |
| 1 | line 0 is blank/whitespace (Wynntils `ItemStatInfoFeature` rebuilds) | recover name from `currentItemStack.getHoverName()`, try new-format LEGACY-box insert |
| 2 | `isLegacy(name)` | gold name (new-format: recolour the emblem/frame name line gold, found by font marker, and insert the LEGACY box; old-format: replace line 0 and swap the rarity line); an item that also passes branch 6's foil test gets its "Enchanted" prefix too |
| 3 | `isMiscLegacy(name)` + misc rarity | same as branch 2 |
| 4 | `isNoLoreLegacy(name)` + empty lore | gold name; rarity inferred from `tooltip_style` component or `§`-colour prefix; insert "Legacy Item (Rarity)" before debug lines |
| 4b | `isPedestalWipedItem(currentItemStack)` | gold name; label = "Legacy Item (Pedestal-Wiped)" |
| 5 | beta-legacy marker ("Lv. min" gold line) | gold name; label "Alpha Legacy Item" when no standard rarity line exists (a `tooltip_style` rarity is appended in parentheses if present), else "Beta Legacy Item (Rarity)"; a foil item that passes branch 6's test gets the "Enchanted" prefix too, as in branch 2 |
| 6 | `hasFoil` + not excluded/unenchanted | prepend "Enchanted" in gold (old-format: `buildGoldName` adds a white ⬡ before it; new-format: `NewFormatRenderer.deepEnchantName` rewrites the name literal) + rarity swap (old-format; new-format inserts the LEGACY box instead) |
| 7 | junk rarity + not in `notjunk` | gold name + rarity swap |
| 8 | crafting rarity | gold name + rarity swap |

**Rarity swap** (`replaceRarityLines`): scans lore bottom-up for regex matching `^(Mythic\|Fabled\|Set\|Legendary\|Rare\|Unique\|Normal\|Junk\|Misc\.\|Crafting) [Ii]tem(?: (\[\d+\]))?$`, captures tier + optional count. Falls back to the `tooltip_style` component path (mapped: common, unique, rare, set, legendary, fabled, mythic, crafted) when no lore rarity is present. Label built as `"Legacy Item (Tier)"` in `ChatFormatting.GOLD`, inserted at `debugLinesStart()` — above F3+H item-id/component-count lines.

**Wynntils percentage suffix** (e.g. `[61.7%]`) is extracted and re-appended to preserve Wynntils stat output.

**The new-format rewrite and the enchantment, by branch.** `tryNewFormatRewrite(modified, plainText)` is branches 2, 3 and 6's new-format path. If `NewFormatRenderer.isNewFormatItem` says the tooltip is new-format, it gives the name line the "Enchanted" treatment (`applyEnchantedToNewFormatNameLine`) or the gold recolour (`recolorNewFormatNameLine`), inserts the LEGACY box (`insertLegacyBoxLine`) and, for an enchanted item, calls `appendEnchantmentBlock`. It returns `true` only once the box is in; otherwise the branch goes on to its old-format rewrite. Branch 1 runs the same steps inline for a new-format legacy item whose line 0 is blank. An item counts as enchanted when it has foil, its item id is not on the `enchant_excluded_items` list (both are folded into `LegacyItemHandler.currentItemHasFoil`), and its name is not on the `unenchanted` list. That is the test branch 6 opens on.

`appendEnchantmentBlock` returns at once while `legacyItemShowEnchantments` is off (§6) and otherwise hands the tooltip to `LegacyEnchantmentRenderer.showEnchantment` (below). It runs only once a branch has decided the item is enchanted and legacy: from branch 1 and `tryNewFormatRewrite` for an enchanted item, from branches 2, 3 and 5's old-format rewrites under the same test, and unconditionally from branch 6's old-format rewrite. Branches 4, 4b, 7 and 8 never call it.

**New-format tooltip rewriting** (`NewFormatRenderer`): detects items via presence of `tooltip/emblem/frame` or `banner/box` fonts. `isNewFormatItem` sets the sticky `newTooltipStylesAvailable` flag as a side effect. The LEGACY chip, built by `legacyBox()` (a gold `banner/box` run of `LEGACY_BOX_BACKGROUND` with the black lettering `LEGACY_BOX_FOREGROUND` as a nested child), is inserted, with a delimiter node, before the first rarity box in the Component tree via a 3-phase recursive descent (direct child → banner/symbol-sibling heuristic → recursive).

Only the §3 lists come from YAML; the remaining detection rules (e.g. lore lines, foil, item IDs, name colour codes, fonts) and the labels themselves (e.g. the "Legacy Item (…)" family, "Enchanted", "LEGACY ENCHANTMENTS") are hardcoded in Java.

**Spoiler handling (`handleSpoilers` config) is unrelated to legacy items** — it encodes `||spoiler||` markers you type before sending (`GuildChatDispatcher` via `SpoilerCodec`) and renders received encoded spoilers via `SpoilerRewriter`/`SpoilerFormatter`, not item tooltips.

### `LegacyEnchantmentRenderer`

[LegacyEnchantmentRenderer](../src/client/java/org/wynnvets/items/LegacyEnchantmentRenderer.java) names the specific vanilla enchantment a legacy item carries, read from the stack's `minecraft:enchantments` component. Wynncraft hides that component from new-format tooltips through `tooltip_display`, but it stays on the stack, and modern items carry it with an empty map, so a non-empty map is itself a legacy signal. `showEnchantment` tries these forms, in order:

1. **A paged new-format item:** a `LEGACY ENCHANTMENTS` block on the powder page, under Wynncraft's POWDER SOCKETS block and drawn like it: a boxed header with a right-aligned `1/1` count, a blank line, then one socket-sprite entry such as `◈ Knockback I`.
2. **A V2-era item**, which has no pages and leaves `minecraft:enchantments` visible: Minecraft already draws an enchantment line without its numeral, and the level is added to that line in place.
3. **An unidentified item**, new-format but pageless: a plain `- Legacy:` / `- Enchantment: …` pair at the end of the tooltip's content, shaped like Wynntils' item-guess block.

The boxed header uses the PUA box alphabets `NewFormatRenderer.LEGACY_BOX_BACKGROUND` documents, and its kerning and right-alignment are measured at runtime from the resource pack's glyph widths.

### `LegacyScreenshotHandler`

[LegacyScreenshotHandler](../src/client/java/org/wynnvets/items/LegacyScreenshotHandler.java) replaces Wynntils' item screenshot of a legacy item. Wynntils' `ItemScreenshotFeature` captures the tooltip before vetsmod rewrites it, so its image has no gold name and no LEGACY box. When Wynntils' screenshot keybind is pressed in an inventory, `LegacyTooltipEventListener` arms a one-shot latch (`LegacyItemHandler.notifyScreenshotKeyPressed`), but only if the hovered slot holds a legacy item (`LegacyItemHandler.isLegacyItem`); a press over an empty slot, no slot or a non-legacy item arms nothing. On the next tooltip pass that rewrites a legacy item, `LegacyItemTooltipMixin` calls `LegacyItemHandler.screenshotIfRequested`, which consumes the latch, renders the final tooltip offscreen and copies the image to the clipboard 250 ms later, after Wynntils' own copy. The latch (`ScreenshotLatch`) expires one second after the press, so a press that no rewriting pass picks up, such as a legacy item shown in the compare view, which bypasses the mixin, cannot fire on a later tooltip.

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

## 7. Data flow summary

**Startup:** `VetsmodClient.onInitializeClient()` → `VetsConfig.load()` → … → `ItemDefinitions.load()` (compiles regex); then, once the game has started, `VetsmodClient.onClientStarted()` → `WynntilsEventListener.register()`, which registers the legacy tooltip and highlight listeners on Wynntils' event bus.

**Hotbar frame:** vanilla `Gui.renderSlot()` → `LegacyHotbarMixin` HEAD → `LegacyHighlightPainter.paintIfLegacy()` → `isLegacyItem()` → 8-branch cascade → if true draw gradient + sprite → vanilla continues.

**Container frame:** `AbstractContainerScreen.renderSlot()` runs → `LegacyHighlightMixin` HEAD sets `newTooltipStylesAvailable` if the slot's item has a `tooltip_style` component (the mixin captures hover state in its separate `renderTooltip` HEAD hook) → Wynntils `SlotRenderEvent.Pre` fires → `LegacyHighlightEventListener` (LOWEST) → `LegacyHighlightPainter.paintIfLegacy()` → gradient + sprite.

**Tooltip frame:** `GuiGraphics.setTooltipForNextFrame()` → `LegacyItemTooltipMixin` HEAD → reentry guard → `LegacyTooltipRenderer.processTooltip()` → 9-branch cascade → if modified: cancel vanilla + re-invoke with modified list, set gold border (only if `newTooltipStylesAvailable`).

**Caching:** regex patterns compiled once; colour/sprite resolution runs every frame (cheap — concurrent hash map lookups); PUA/new-format detection runs every tooltip (no memoization).

**State fields** on `LegacyItemHandler`:
- `currentItemHasFoil`, `currentItemStack`, `currentHoveredSlot` — hover context; `LegacyHighlightMixin`'s `renderTooltip` hook sets and clears all three, and `LegacyTooltipEventListener` (Wynntils `ItemTooltipRenderEvent.Pre`) sets the first two
- `lastProcessedWasLegacy` — renderer → tooltip mixin signal for gold-border override
- `newTooltipStylesAvailable` — sticky per-session flag, reset by `ServerConnectionListener` on disconnect; suppresses gold tooltip border on old servers that lack the new resource pack

## 8. Edge cases & quirks

- **`new_format_override`**: an item like Skeleton Key exists in old (legacy) and new (modern) formats with the same display name. When `isNewFormatItem(stack)` (any supplementary code point in custom name, `cp >= 0x10000`) AND the name is in this list, `isLegacyItem`'s branches 1 & 2 (`processTooltip`'s Branches 2 & 3) are skipped; `no_lore_legacy` still applies.
- **`enchant_excluded_items`**: `minecraft:experience_bottle` / `minecraft:enchanted_book` always have vanilla foil — excluded by Minecraft registry ID, not name.
- **`notjunk`**: modern junk-tier farming materials (e.g. Leather, Tanned Sunfish, Roasted Flesh, Vines, Rubble, Ghostly*, Dusty Rum Bottle, Golden Teeth) — excluded from branch 7.
- **`unenchanted`**: items that legitimately have foil in modern Wynncraft — e.g. `???`, Ability Shard, `Breathing Helmet *`, `*Liquid Emerald*`, `*Mythic Everlasting Pufferfish*`, `Shiny *`, `*Transcriber` (a `*` marks where the pattern's wildcard sits) — bypass branch 6.
- **Alpha vs Beta legacy**: both triggered by gold "Lv. min: N" lore line. Alpha = no standard rarity line present; Beta = has one. Label differs accordingly.
- **Unidentified gear**: name contains BMP PUA lock icon; `stripSupplementaryPua()` normalises it before name matching. No separate identified-vs-unidentified branching.
- **"Crafted" items (player-made with durability)**: never legacy — early bail in branch 0 of tooltip renderer.
- **Wynntils `ItemStatInfoFeature` rebuild**: can blank out line 0. Branch 1 of tooltip renderer recovers the name from `currentItemStack.getHoverName()` and attempts new-format LEGACY-box insertion.
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
