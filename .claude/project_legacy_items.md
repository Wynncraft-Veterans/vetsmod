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

**Data:**
- [definitions.yml](../src/client/resources/definitions.yml) — 9 sections, 203 lines

**Config:**
- [VetsConfig](../src/client/java/org/wynnvets/config/VetsConfig.java)
- [LegacyItemStyle](../src/client/java/org/wynnvets/config/LegacyItemStyle.java) — resolves gradient/sprite from config
- [NamedColor](../src/client/java/org/wynnvets/config/NamedColor.java) — colour-name → RGB + alpha packing

**Mixins (3 legacy-item-specific out of 14 registered):**
- [LegacyHighlightMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHighlightMixin.java) — `AbstractContainerScreen#renderSlot` + `renderTooltip` (captures hover context)
- [LegacyHotbarMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java) — `Gui#renderSlot` (draws hotbar gradient + sprite)
- [LegacyItemTooltipMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyItemTooltipMixin.java) — `GuiGraphics#setTooltipForNextFrame` (rewrites tooltip + gold border)

**Event listeners:**
- [LegacyHighlightEventListener](../src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java) — `SlotRenderEvent.Pre` at `LOWEST` priority (draws container gradient + sprite, overriding Wynntils `ItemHighlightFeature`)
- [LegacyTooltipEventListener](../src/client/java/org/wynnvets/listeners/LegacyTooltipEventListener.java) — sets hover context before tooltip
- [ServerConnectionListener](../src/client/java/org/wynnvets/listeners/ServerConnectionListener.java) — resets `newTooltipStylesAvailable` on disconnect

## 2. Detection cascade — `LegacyItemHandler.isLegacyItem(ItemStack)`

[LegacyItemHandler.isLegacyItem()](../src/client/java/org/wynnvets/items/LegacyItemHandler.java)

Guard conditions (return `false` early):
- `VetsConfig.LEGACY_ITEM_HIGHLIGHTING` disabled
- `stack.isEmpty()`
- `isBlockedScreen()` — screen title is `"Island Rules"` or `"Move here!"` (these use enchant glint as UI selectors)

Name is normalized via `stripSupplementaryPua()` + `ChatFormatting.stripFormatting()` before matching. The `newFormatOverridden` flag suppresses name-based matches (branches 1 & 2) when the item is new-format AND its name is in `new_format_override`.

Eight independent branches — **any one** triggers legacy. The numbering matches
the `// 1.` … `// 8.` comments in the method body:

| # | Branch | Source | Extra gate |
|---|--------|--------|-----------|
| 1 | `isLegacy(name)` | `definitions` (~97 patterns) | not newFormatOverridden |
| 2 | `isMiscLegacy(name)` + `hasMiscRarity(lore)` | `misc_definitions` (~21) | not newFormatOverridden, lore contains "Misc. Item" |
| 3 | `isNoLoreLegacy(name)` | `no_lore_legacy` (~17) | `lore.isEmpty()` |
| 4 | `isPedestalWipedItem(stack)` | item ID + custom name | vanilla armour/weapon shell, no lore, `§a`/`§b`/`§d`/`§e` name prefix, name not in `not_pedestal` |
| 5 | `hasBetaLegacyMarker(lore)` | lore | gold "Lv. min: N" line |
| 6 | `stack.hasFoil()` | NBT | not in `enchant_excluded_items`, name not in `unenchanted` |
| 7 | `hasJunkRarity(lore)` | lore | name not in `notjunk` |
| 8 | `hasCraftingRarity(lore)` | lore | — |

## 3. definitions.yml — the 9 sections

File: [definitions.yml](../src/client/resources/definitions.yml). Parsed by `ItemDefinitions.parse()` — simple state machine: `---` section delimiters, headers end with `:`, patterns prefixed `- "…"`. All patterns are pre-compiled to `java.util.regex.Pattern` once at startup. **No runtime reload** — requires restart to pick up edits.

| Section | Purpose | Gate logic |
|---------|---------|-----------|
| `unenchanted` | Items that legitimately have foil in modern Wynncraft (???, Ability Shard, Breathing Helmet, Mythic Everlasting Pufferfish, Liquid Emerald, Shiny, Transcriber) | exclusion in branch 6 |
| `definitions` | Primary legacy name list — old armor tiers, keys, holiday items, autographs, potions | branch 1 |
| `no_lore_legacy` | Legacy items that lost their lore (old keys, vanilla materials, Grian's Ocean Map) — only legacy when lore is empty | branch 3 |
| `misc_definitions` | Items requiring "Misc. Item" rarity (Carved Quartz Block, Firefly Wing, Mushrooms) — collides with modern misc | branch 2 |
| `not_pedestal` | Names that must never be treated as a pedestal-wiped shell (merchant items) | exclusion in branch 4 |
| `notjunk` | Modern junk-tier items (Leather, Tanned Sunfish, Ghostly, Roasted Flesh, Vines, Rubble) | exclusion in branch 7 |
| `enchant_excluded_items` | Minecraft namespaced IDs (`minecraft:experience_bottle`, `minecraft:enchanted_book`) — always have vanilla foil | exclusion in branch 6 |
| `new_format_override` | Names that exist in both old & new formats (e.g. Skeleton Key) — when item is new-format, name match is suppressed | gate on branches 1 & 2 |
| `blocked_screen_titles` | Screen titles where the whole system bails (housing/island menus that abuse enchant glint as a UI selector) | `isBlockedScreen()` guard |

`ItemDefinitions` responsibilities: YAML parsing, regex pre-compilation into 8 static `List<Pattern>` fields plus one `Set<String>` (`enchantExcludedItems`), and 9 lookup predicates (`isLegacy`, `isMiscLegacy`, `isNoLoreLegacy`, `isUnenchanted`, `isEnchantExcludedItem`, `isNotPedestal`, `isNotJunk`, `isNewFormatOverride`, `isBlockedScreenTitle`). Loaded from `VetsmodClient.onInitializeClient()` after `VetsConfig.load()`.

## 4. Tooltip rewriting — `LegacyTooltipRenderer.processTooltip()`

Entry point: called from `LegacyItemTooltipMixin` via `LegacyItemHandler.processTooltip()` at render time. 9 branches after the guard bail, **first match wins**:

| # | Trigger | Action |
|---|---------|--------|
| 0 | highlighting off / empty / blocked / "Crafted" prefix | bail, no rewrite |
| 1 | line 0 is blank/whitespace (Wynntils `ItemStatInfoFeature` rebuilds) | recover name from `currentItemStack.getHoverName()`, try new-format LEGACY-box insert |
| 2 | `isLegacy(name)` | gold name + rarity swap (new-format: restore custom name, gold recolour, insert LEGACY box; old-format: replace line 0, swap rarity line) |
| 3 | `isMiscLegacy(name)` + misc rarity | same as branch 2 |
| 4 | `isNoLoreLegacy(name)` + empty lore | gold name; rarity inferred from `tooltip_style` component or `§`-colour prefix; insert "Legacy Item (Rarity)" before debug lines |
| 4b | `isPedestalWipedItem(currentItemStack)` | gold name; label = "Legacy Item (Pedestal-Wiped)" |
| 5 | beta-legacy marker ("Lv. min" gold line) | gold name; label = "Legacy Item (Alpha)" if no standard rarity, else "Legacy Item (Beta)" |
| 6 | `hasFoil` + not excluded/unenchanted | prepend "⬡ Enchanted" (gold on old-format, yellow on new-format) + rarity swap |
| 7 | junk rarity + not in `notjunk` | gold name + rarity swap |
| 8 | crafting rarity | gold name + rarity swap |

**Rarity swap** (`replaceRarityLines`): scans lore bottom-up for regex matching `^(Mythic\|Fabled\|Set\|Legendary\|Rare\|Unique\|Normal\|Junk\|Misc\.\|Crafting) Item(?: (\[\d+\]))?$`, captures tier + optional count. Falls back to the `tooltip_style` component path (mapped: common, unique, rare, set, legendary, fabled, mythic, crafted) when no lore rarity is present. Label built as `"Legacy Item (Tier)"` in `ChatFormatting.GOLD`, inserted at `debugLinesStart()` — above F3+H item-id/component-count lines.

**Wynntils percentage suffix** (e.g. `[61.7%]`) is extracted and re-appended to preserve Wynntils stat output.

**New-format tooltip rewriting** (`NewFormatRenderer`): detects items via presence of `tooltip/emblem/frame` or `banner/box` fonts. `isNewFormatItem` sets the sticky `newTooltipStylesAvailable` flag as a side effect. Pre-encoded `LEGACY_BOX_TEXT` (PUA glyphs in `banner/box` font, gold) is inserted before the first rarity box in the Component tree via a 3-phase recursive descent (direct child → sibling → recursive).

No hardcoded tooltip strings — everything is YAML-driven or runtime-derived from the item's rarity.

**Spoiler handling (`handleSpoilers` config) is unrelated to legacy items** — it handles `||spoiler||` chat markers in `SpoilerRewriter`, not item tooltips.

## 5. Visual rendering — gradient + sprite

Rendered in **two places**, using identical drawing code:

**Hotbar:** [LegacyHotbarMixin](../src/client/java/org/wynnvets/mixin/client/legacy/LegacyHotbarMixin.java) — `@Inject(method = "renderSlot", at = @At("HEAD"))` on `net.minecraft.client.gui.Gui`.

**Containers:** [LegacyHighlightEventListener](../src/client/java/org/wynnvets/listeners/LegacyHighlightEventListener.java) — subscribes to Wynntils `SlotRenderEvent.Pre` at `EventPriority.LOWEST` so it runs **after** Wynntils' `ItemHighlightFeature` (at `HIGH`), effectively overriding the stock Wynntils rarity highlight.

Drawing sequence:
1. `guiGraphics.fillGradient(x, y, x+16, y+16, topColor, bottomColor)` — top/bottom colours from `LegacyItemStyle.getBackgroundGradientTopColor()` / `…BottomColor()` with per-colour opacity packed into ARGB alpha
2. `guiGraphics.blit(RenderPipelines.GUI_TEXTURED, WYNNTILS_HIGHLIGHT, x-1, y-1, spriteOffset, 0f, 18, 18, 256, 256, foregroundColor)` — texture `wynntils:textures/ui_components/highlight.png`, U offset = sprite ordinal × 18, tinted by `foregroundColor`

`LegacyHighlightMixin` itself **does not draw** — it only captures hover state (`currentItemHasFoil`, `currentItemStack`) for the tooltip pipeline and, via a server-compat patch in its `renderSlot` head, sets `newTooltipStylesAvailable` if it sees a `tooltip_style` component.

Sprites (`LEGACY_ITEM_FOREGROUND_SPRITE`): `wynn`, `tag`, `circle_transparent`, `circle_opaque`, `circle_outline_large`, `circle_outline_small`, `box_transparent`, `box_opaque`, `box_gradient_1`, `box_gradient_2`.

Colours resolved via `NamedColor.COLORS` map: Minecraft formatting codes + rarity colours + CSS colours + custom `legacy_orange` (0xF0501E) + `transparent`. Opacity is clamped 0–100 and packed as `(alpha << 24) | (rgb & 0xFFFFFF)`.

**Supporter glints (`SHOW_SUPPORTER_GLINTS`) are independent**: rendered on nametags/pills via a separate mixin, never interacts with legacy-item gradient — both can apply to the same item.

## 6. User-facing config keys (all via `/wv config`)

Registered in `VetsConfig.USER_CONFIG_KEYS`. Validation delegated to `VetsConfig.isValidColor()` / `isValidSprite()`; opacity clamped 0–100. Persisted to `vetsmod/storage/config.json`.

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

**Startup:** `VetsmodClient.onInitializeClient()` → `VetsConfig.load()` → `ItemDefinitions.load()` (compiles regex) → event listeners registered.

**Hotbar frame:** vanilla `Gui.renderSlot()` → `LegacyHotbarMixin` HEAD → `isLegacyItem()` → 8-branch cascade → if true draw gradient + sprite → vanilla continues.

**Container frame:** `AbstractContainerScreen.renderSlot()` runs → `LegacyHighlightMixin` HEAD captures hover state → Wynntils `SlotRenderEvent.Pre` fires → `LegacyHighlightEventListener` (LOWEST) draws gradient + sprite.

**Tooltip frame:** `GuiGraphics.setTooltipForNextFrame()` → `LegacyItemTooltipMixin` HEAD → reentry guard → `LegacyTooltipRenderer.processTooltip()` → 9-branch cascade → if modified: cancel vanilla + re-invoke with modified list, set gold border (only if `newTooltipStylesAvailable`).

**Caching:** regex patterns compiled once; colour/sprite resolution runs every frame (cheap — concurrent hash map lookups); PUA/new-format detection runs every tooltip (no memoization).

**State fields** on `LegacyItemHandler`:
- `currentItemHasFoil`, `currentItemStack` — hover context, set by highlight mixin + listener before tooltip
- `lastProcessedWasLegacy` — renderer → tooltip mixin signal for gold-border override
- `newTooltipStylesAvailable` — sticky per-session flag, reset by `ServerConnectionListener` on disconnect; suppresses gold tooltip border on old servers that lack the new resource pack

## 8. Edge cases & quirks

- **`new_format_override`**: an item like Skeleton Key exists in old (legacy) and new (modern) formats with the same display name. When `isNewFormatItem(stack)` (supplementary PUA in custom name, `cp >= 0x10000`) AND the name is in this list, branches 1 & 2 are skipped.
- **`enchant_excluded_items`**: `minecraft:experience_bottle` / `minecraft:enchanted_book` always have vanilla foil — excluded by Minecraft registry ID, not name.
- **`notjunk`**: modern junk-tier farming materials (Leather, Tanned Sunfish, Roasted Flesh, Vines, Rubble, Ghostly*, Dusty Rum Bottle, Golden Teeth) — excluded from branch 7.
- **`unenchanted`**: items that legitimately have foil in modern Wynncraft — `???`, Ability Shard, Breathing Helmet*, Liquid Emerald*, Mythic Everlasting Pufferfish, Shiny*, Transcriber* — bypass branch 6.
- **Alpha vs Beta legacy**: both triggered by gold "Lv. min: N" lore line. Alpha = no standard rarity line present; Beta = has one. Label differs accordingly.
- **Unidentified gear**: name contains BMP PUA lock icon; `stripSupplementaryPua()` normalises it before name matching. No separate identified-vs-unidentified branching.
- **"Crafted" items (player-made with durability)**: never legacy — early bail in branch 0 of tooltip renderer.
- **Wynntils `ItemStatInfoFeature` rebuild**: can blank out line 0. Branch 1 of tooltip renderer recovers the name from `currentItemStack.getHoverName()` and attempts new-format LEGACY-box insertion.
- **Blocked screens** ("Island Rules", "Move here!"): these Wynncraft menus abuse enchant glint as UI selectors — whole system bails on them.
- **Old-server compat**: `newTooltipStylesAvailable` starts false; set true as soon as any item with `tooltip_style` or new-format font is seen. Gold tooltip border is suppressed until then to avoid garish fallback colours on pre-update servers. Reset on disconnect.
- **Wynntils percentage suffix** (`[61.7%]`): detected via `\s*(\[\d+\.?\d*%\])$`, extracted before rewriting, re-appended after.

## 9. Related config that isn't legacy-specific

- `handleSpoilers` (tri-state) — chat `||spoiler||` handling only, **not** item tooltips.
- `showSupporterGlints` — nametag decoration, independent of legacy rendering.

## 10. Wynncraft item era history (background)

Why detection branches look the way they do — each era left a distinct tooltip fingerprint:

- **Alpha era** — no rarity line at all. Items had hex-UUIDs as `&f` (white) names, `&5` (dark purple) damage (weapons) or defense (armour), and `&6` (gold) `Lv. min:` lines. Detected via branch 5 (`hasBetaLegacyMarker`) when no standard rarity line is present → labelled "Legacy Item (Alpha)".
- **Beta era** — introduced rarity tiers: `&e` Unique, `&5` Rare, `&b` Legendary, and possibly `&a` Set. Reused the `&5` damage/defense + `&6` level format, sometimes with `&7` (gray) statistics. Detected via branch 5 when a standard rarity line *is* present → labelled "Legacy Item (Beta)".
- **Release era** — added elemental damages, more complex tooltips, and the `&c` Fabled and `&5` Mythic rarities. This is the format the bulk of `definitions.yml` patterns target.
- **Fruma / 2.1 era (current "new format")** — completely new rendering relying heavily on resource-pack fonts and Unicode PUA glyphs (`tooltip/emblem/frame`, `banner/box`). Removed the Set rarity. Drives `NewFormatRenderer`, the `newTooltipStylesAvailable` sticky flag, the `new_format_override` YAML section, and `stripSupplementaryPua()` name normalization.
