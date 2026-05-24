package org.wynnvets.items;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.logging.VetsLogger;

/**
 * Tooltip rewriting orchestration for legacy items.
 *
 * <p>This class is the visual counterpart to {@link LegacyItemHandler#isLegacyItem}.
 * While {@code isLegacyItem} answers "should this item glow gold?",
 * this class answers "what should the tooltip look like?"</p>
 *
 * <h2>Detection cascade (order matters!)</h2>
 * <p>{@link #processTooltip} checks items against a priority-ordered chain of
 * detection branches. The FIRST matching branch wins and rewrites the tooltip.
 * The branches, in order, are:</p>
 * <ol>
 *   <li><b>Crafted items</b> — skip entirely (player-made, never legacy).</li>
 *   <li><b>Blank first-line fallback</b> — Wynntils' ItemStatInfoFeature can
 *       blank out line 0; recover the name from the hover component and try
 *       to insert the LEGACY box into the PUA rarity line.</li>
 *   <li><b>Exact legacy match</b> — name is in {@code definitions.yml}'s
 *       {@code definitions} section.</li>
 *   <li><b>Misc legacy</b> — name is in {@code misc_definitions} AND the
 *       item has a "Misc. Item" rarity line.</li>
 *   <li><b>No-lore legacy whitelist</b> — name matches the
 *       {@code no_lore_legacy} section in {@code definitions.yml} AND the
 *       item has no lore lines.  This is an explicit whitelist of confirmed
 *       loreless legacy items (old keys, vanilla materials, holiday items).
 *       Rarity is inferred from the {@code tooltip_style} data component or
 *       the §-colour code prefix of the custom name.</li>
 *   <li><b>Beta/alpha legacy marker</b> — lore contains a gold "Lv. min"
 *       line (the old Wynncraft stat format). Alpha vs beta is decided by
 *       whether a standard rarity line exists.</li>
 *   <li><b>Enchanted (foil)</b> — item has the glint effect and is not in
 *       the unenchanted exclusion list. Displayed as "⬡ Enchanted Name".</li>
 *   <li><b>Junk rarity</b> — lore contains "Junk Item" and the name is
 *       not in the notjunk exclusion list.</li>
 *   <li><b>Crafting rarity</b> — lore contains "Crafting Item".</li>
 * </ol>
 *
 * <h2>Old format vs new format</h2>
 * <p>Wynncraft has two item display formats:</p>
 * <ul>
 *   <li><b>Old format:</b> Plain text names with §-colour codes, rarity shown
 *       as a lore line (e.g. "Legendary Item"). Tooltip is rewritten by
 *       replacing line 0 with a gold name and swapping the rarity line for
 *       a "Legacy Item (Legendary)" label.</li>
 *   <li><b>New format:</b> Names wrapped in PUA (Private Use Area) Unicode
 *       for custom fonts/spacing, rarity encoded in the {@code tooltip_style}
 *       data component instead of lore. Tooltip is rewritten by delegating
 *       to {@link NewFormatRenderer} which inserts a LEGACY box into the
 *       PUA emblem/frame line and recolours the name gold.</li>
 * </ul>
 * <p>Each detection branch tries the new-format path first (PUA box insertion),
 * and falls back to old-format rewriting (gold name + rarity line swap) when
 * the item is old-format or the PUA insertion fails.</p>
 *
 * <p>Coordinates detection state from {@link LegacyItemHandler}, delegates
 * new-format PUA-encoded operations to {@link NewFormatRenderer}, and handles
 * old-format rarity line replacement and text helpers locally.</p>
 */
final class LegacyTooltipRenderer {

  /** Matches Minecraft's F3+H debug line showing the item's registry ID (e.g. "minecraft:paper"). */
  private static final Pattern DEBUG_ID_PATTERN = Pattern.compile("^\\w+:\\w[\\w/.-]*$");
  /** Matches Minecraft's F3+H debug line showing component count (e.g. "12 component(s)"). */
  private static final Pattern DEBUG_COMPONENTS_PATTERN =
      Pattern.compile("^\\d+ component\\(s\\)$");
  /** Matches a Wynntils percentage suffix like " [61.7%]" appended to item names. */
  private static final Pattern PERCENT_SUFFIX_PATTERN =
      Pattern.compile("\\s*(\\[\\d+\\.?\\d*%\\])$");
  /** Matches Wynncraft crafted items (e.g. "Crafted Helmet [30/30 Durability]") which are never legacy. */
  private static final Pattern CRAFTED_PATTERN =
      Pattern.compile(
          "^Crafted (?:Helmet|Chestplate|Pants|Boots|Ring|Potion|Scroll|Food|Wand|Spear|Relik|Bow|Dagger|by .+) \\[\\d+/\\d+ Durability\\]$");

  private LegacyTooltipRenderer() {}

  /**
   * Processes and rewrites tooltip lines for legacy/enchanted/junk/crafting items.
   * Returns the unmodified list when {@link org.wynnvets.config.VetsConfig#LEGACY_ITEM_HIGHLIGHTING}
   * is disabled.
   *
   * @param tooltipLines the original tooltip lines
   * @return the (possibly modified) tooltip lines
   */
  static List<Component> processTooltip(List<Component> tooltipLines) {
    // ── Guard clauses ──────────────────────────────────────────────────
    // Reset the flag that tells the mixin whether to apply the gold border.
    LegacyItemHandler.lastProcessedWasLegacy = false;

    if (!org.wynnvets.config.VetsConfig.get(org.wynnvets.config.VetsConfig.LEGACY_ITEM_HIGHLIGHTING)) return tooltipLines;
    if (tooltipLines.isEmpty()) return tooltipLines;
    if (LegacyItemHandler.isBlockedScreen()) return tooltipLines;

    // Crafted items (player-made gear with durability) are never legacy.
    if (isCraftedItem(tooltipLines)) return tooltipLines;

    // ── Name extraction ────────────────────────────────────────────────
    // Get the first tooltip line (the item name), strip formatting codes
    // and PUA characters to get a clean name for pattern matching.
    Component firstLine = tooltipLines.get(0);
    String rawText = firstLine.getString();
    String plainText = LegacyItemHandler.normalizeName(ChatFormatting.stripFormatting(rawText));

    // Extract Wynntils rarity suffix (e.g. "[61.7%]") preserving its original color
    Component raritySuffix = extractColoredSuffix(firstLine, plainText);

    // Strip the Wynntils percentage suffix for clean name matching.
    if (plainText != null) {
      plainText = stripPercentSuffix(plainText);
    }

    // Suppress name-based legacy checks for new-format items whose names
    // also appear in the override list (items that exist in both old and new
    // formats — when seen in new format, they are modern and not legacy).
    boolean newFormatOverridden = !LegacyItemHandler.currentItemStack.isEmpty()
        && LegacyItemHandler.isNewFormatItem(LegacyItemHandler.currentItemStack)
        && plainText != null && ItemDefinitions.isNewFormatOverride(plainText);

    // ── Branch 1: Blank first-line fallback (Wynntils ItemStatInfoFeature) ──
    // Wynntils' ItemStatInfoFeature (when enabled) rebuilds identified-gear
    // tooltips from scratch.  The rebuilt layout starts with an empty spacer
    // line, so line 0 is blank and the item name is no longer there.
    // Detect this and insert the LEGACY box into the rebuilt PUA rarity line.
    boolean firstLineBlank = plainText == null || plainText.isBlank();
    if (firstLineBlank && !LegacyItemHandler.currentItemStack.isEmpty()) {
      String hover = LegacyItemHandler.currentItemStack.getHoverName().getString();
      String fallbackName = LegacyItemHandler.normalizeName(
          ChatFormatting.stripFormatting(hover));
      if (fallbackName != null) {
        fallbackName = stripPercentSuffix(fallbackName);
      }
      if (fallbackName != null && !fallbackName.isBlank()) {
        plainText = fallbackName;
      }
      List<Component> modified = new ArrayList<>(tooltipLines);
      boolean isNew = NewFormatRenderer.isNewFormatItem(modified);
      boolean isLeg = LegacyItemHandler.isLegacyItem(LegacyItemHandler.currentItemStack);
      if (isNew && isLeg) {
        boolean enchanted = LegacyItemHandler.currentItemHasFoil
            && plainText != null && !ItemDefinitions.isUnenchanted(plainText);
        if (plainText != null) {
          if (enchanted) {
            NewFormatRenderer.applyEnchantedToNewFormatNameLine(modified, plainText);
          } else {
            NewFormatRenderer.recolorNewFormatNameLine(modified, plainText);
          }
        }
        if (NewFormatRenderer.insertLegacyBoxLine(modified)) {
          LegacyItemHandler.lastProcessedWasLegacy = true;
          return modified;
        }
      }
      return tooltipLines;
    }

    // ── Branch 2: Exact legacy name match (definitions section) ─────────
    // The item's normalized name matches a pattern in definitions.yml's
    // "definitions" section.  These are known legacy items by name.
    if (!newFormatOverridden && plainText != null && ItemDefinitions.isLegacy(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      if (tryNewFormatRewrite(modified, plainText)) {
        LegacyItemHandler.lastProcessedWasLegacy = true;
        return modified;
      }
      boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
      modified.set(0, buildGoldName(plainText, enchanted, raritySuffix));
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // ── Branch 3: Misc legacy (misc_definitions + "Misc. Item" rarity) ──
    // The item's name matches misc_definitions AND the tooltip contains a
    // "Misc. Item" rarity line.  Both conditions are required because misc
    // items can also exist as modern server items.
    if (!newFormatOverridden && plainText != null && ItemDefinitions.isMiscLegacy(plainText) && LegacyItemHandler.hasMiscRarity(tooltipLines)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      if (tryNewFormatRewrite(modified, plainText)) {
        LegacyItemHandler.lastProcessedWasLegacy = true;
        return modified;
      }
      boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
      modified.set(0, buildGoldName(plainText, enchanted, raritySuffix));
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // ── Branch 4: No-lore legacy whitelist ────────────────────────────────
    // Items whose names match the "no_lore_legacy" whitelist in
    // definitions.yml AND have no lore lines.  This is an explicit list of
    // confirmed loreless legacy items (old keys, vanilla materials, holiday
    // items, etc.) — unlike the old auto-detection system that treated ANY
    // custom-named loreless item as legacy, which produced false positives.
    // Rarity is inferred first from the tooltip_style data component, then
    // from the §-colour code prefix of the custom name when available.
    if (plainText != null && !plainText.isBlank() && ItemDefinitions.isNoLoreLegacy(plainText)
        && LegacyItemHandler.currentItemStack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().isEmpty()) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      modified.set(0, buildGoldName(plainText, false, raritySuffix));
      String rarity = LegacyItemHandler.getTooltipStyleRarity(LegacyItemHandler.currentItemStack);
      if (rarity == null) {
        rarity = LegacyItemHandler.getRarityFromNameColor(LegacyItemHandler.currentItemStack);
      }
      String label = rarity != null ? "Legacy Item (" + rarity + ")" : "Legacy Item";
      modified.add(debugLinesStart(modified), Component.literal(label).withStyle(ChatFormatting.GOLD));
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // ── Branch 4b: Pedestal-wiped vanilla armour/weapon shells ──────────
    // The item is a vanilla armour piece (helmet/chestplate/leggings/boots)
    // or weapon-class proxy (shears/iron_shovel/bow/stick/cocoa_beans/paper), has no lore, and its
    // custom name carries a §a/§b/§d/§e colour-code prefix.  The pedestal item-
    // wipe strips lore from legendary/rare/unique items while preserving the
    // coloured name and vanilla item ID, so these bare shells get a dedicated
    // "Legacy Item (Pedestal-Wiped)" label instead of falling through to the
    // foil/beta-marker branches below.
    if (LegacyItemHandler.isPedestalWipedItem(LegacyItemHandler.currentItemStack)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      modified.set(0, buildGoldName(plainText, false, raritySuffix));
      modified.add(debugLinesStart(modified),
          Component.literal("Legacy Item (Pedestal-Wiped)").withStyle(ChatFormatting.GOLD));
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // ── Branch 5: Beta/alpha legacy marker (gold "Lv. min" in lore) ─────
    // Old Wynncraft beta/alpha items have a gold-coloured "Lv. min: X" line
    // in lore instead of the modern format.  If a standard rarity line also
    // exists, it's beta; if not, it's alpha (predates rarity lines).
    if (LegacyItemHandler.hasBetaLegacyMarker(tooltipLines)) {
      boolean alpha = !LegacyItemHandler.hasRarityLine(tooltipLines);
      List<Component> modified = new ArrayList<>(tooltipLines);
      if (plainText != null) {
        boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
        modified.set(0, buildGoldName(plainText, enchanted, raritySuffix));
      }
      replaceRarityLines(modified, alpha ? "Alpha" : "Beta");
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // ── Branch 6: Enchanted items (foil/glint detection) ────────────────
    // Items with the enchantment glint that aren't in the unenchanted
    // exclusion list.  Modern Wynncraft items never have foil, so any
    // foil-bearing item is legacy.  Displayed as "⬡ Enchanted <name>".
    if (LegacyItemHandler.currentItemHasFoil && plainText != null && !ItemDefinitions.isUnenchanted(plainText)) {
      VetsLogger.debug("processTooltip: foil branch entered, plainText='{}' (len={}), hasFoil={}", plainText, plainText.length(), LegacyItemHandler.currentItemHasFoil);
      StringBuilder hexDump = new StringBuilder();
      for (int ci = 0; ci < plainText.length(); ci++) {
        hexDump.append(String.format("%04x ", (int)plainText.charAt(ci)));
      }
      VetsLogger.debug("processTooltip: plainText hex: {}", hexDump.toString().trim());
      List<Component> modified = new ArrayList<>(tooltipLines);
      boolean newFormat = NewFormatRenderer.isNewFormatItem(modified);
      VetsLogger.debug("processTooltip: foil branch newFormat={}, tooltipSize={}", newFormat, modified.size());
      if (tryNewFormatRewrite(modified, plainText)) {
        LegacyItemHandler.lastProcessedWasLegacy = true;
        return modified;
      }
      modified.set(0, buildGoldName(plainText, true, raritySuffix));
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // ── Branch 7: Junk rarity ──────────────────────────────────────────
    // Items whose lore contains a "Junk Item" rarity line, unless the
    // name is in the notjunk exclusion list (modern junk-tier items).
    if (LegacyItemHandler.hasJunkRarity(tooltipLines) && plainText != null && !ItemDefinitions.isNotJunk(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      modified.set(0, buildGoldName(plainText, false, raritySuffix));
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // ── Branch 8: Crafting rarity ──────────────────────────────────────
    // Items whose lore contains a "Crafting Item" rarity line.
    // All crafting-rarity items are legacy (modern crafting uses a
    // different system).
    if (LegacyItemHandler.hasCraftingRarity(tooltipLines) && plainText != null) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      modified.set(0, buildGoldName(plainText, false, raritySuffix));
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    // No detection branch matched — return the tooltip unmodified.
    return tooltipLines;
  }

  /**
   * Attempts new-format (PUA) tooltip rewriting: recolours the emblem/frame
   * name line gold (with optional "Enchanted" prefix), and inserts the
   * LEGACY box into the banner/box rarity line.
   *
   * <p>Does <em>not</em> mutate line 0 by index. The Wynntils-default tooltip
   * layout uses a customName placeholder at line 0 with the structured PUA
   * name line at line 1; some third-party processors (e.g. wynnmod's
   * {@code DecorateTooltipFeature}) regenerate the tooltip with the PUA name
   * line promoted to line 0 and no placeholder. Both layouts are handled
   * because {@code recolorNewFormatNameLine}, {@code applyEnchantedToNewFormatNameLine},
   * and {@code insertLegacyBoxLine} all locate their target lines by font
   * marker rather than by position.</p>
   *
   * @return {@code true} if the LEGACY box was inserted (the visible signal
   *         that the new-format rewrite succeeded)
   */
  private static boolean tryNewFormatRewrite(List<Component> modified, String plainText) {
    if (!NewFormatRenderer.isNewFormatItem(modified)) return false;
    boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
    if (enchanted) {
      NewFormatRenderer.applyEnchantedToNewFormatNameLine(modified, plainText);
    } else {
      NewFormatRenderer.recolorNewFormatNameLine(modified, plainText);
    }
    return NewFormatRenderer.insertLegacyBoxLine(modified);
  }

  /**
   * Builds the gold-coloured name component for old-format legacy items.
   * When {@code enchanted} is true, prefixes with "⬡ Enchanted".
   */
  private static MutableComponent buildGoldName(String plainText, boolean enchanted, Component raritySuffix) {
    MutableComponent name;
    if (enchanted) {
      name = Component.literal("\u2B21 ")
          .withStyle(ChatFormatting.WHITE)
          .append(Component.literal("Enchanted " + plainText).withStyle(ChatFormatting.GOLD));
    } else {
      name = Component.literal(plainText).withStyle(ChatFormatting.GOLD);
    }
    if (raritySuffix != null) name.append(raritySuffix);
    return name;
  }

  /**
   * Returns {@code true} if the tooltip belongs to a player-crafted item.
   * Crafted items are never legacy and are skipped before any detection logic.
   */
  private static boolean isCraftedItem(List<Component> lines) {
    for (Component line : lines) {
      String plain = ChatFormatting.stripFormatting(line.getString());
      if (plain != null && CRAFTED_PATTERN.matcher(plain).matches()) return true;
    }
    return false;
  }

  /**
   * Finds and replaces the standard rarity line (e.g. "Legendary Item") with
   * a gold "Legacy Item (Legendary)" label.  For old-format items this scans
   * lore bottom-up; for new-format items it falls back to the tooltip_style
   * data component.  If neither source yields a rarity, a plain "Legacy Item"
   * label is used.
   *
   * @param lines  the mutable tooltip lines (rarity line is removed in-place)
   * @param prefix optional prefix like "Alpha" or "Beta" prepended to the label
   */
  private static void replaceRarityLines(List<Component> lines, String prefix) {
    if (lines.isEmpty()) return;

    Component legacyLabel = null;

    // Scan bottom-up to find and remove the rarity line
    for (int i = lines.size() - 1; i >= 1; i--) {
      String plain = ChatFormatting.stripFormatting(lines.get(i).getString());
      if (plain == null) continue;

      Matcher m = LegacyItemHandler.RARITY_PATTERN.matcher(plain);
      if (m.matches()) {
        legacyLabel = buildLegacyLabel(m.group(1), m.group(2), prefix);
        lines.remove(i);
        break;
      }
    }

    // New-format items encode rarity in the tooltip_style data component instead
    // of a plain-text lore line. Fall back to that when no lore rarity was found.
    if (legacyLabel == null && !LegacyItemHandler.currentItemStack.isEmpty()) {
      String rarity = LegacyItemHandler.getTooltipStyleRarity(LegacyItemHandler.currentItemStack);
      if (rarity != null) {
        legacyLabel = buildLegacyLabel(rarity, null, prefix);
      }
    }

    if (legacyLabel == null) {
      String label = prefix != null ? prefix + " Legacy Item" : "Legacy Item";
      legacyLabel = Component.literal(label).withStyle(ChatFormatting.GOLD);
    }

    // Insert before any F3+H debug lines (resource id / component count)
    lines.add(debugLinesStart(lines), legacyLabel);
  }

  /**
   * Returns the index at which to insert the legacy label, just before any
   * F3+H advanced tooltip debug lines (resource ID and component count).
   * If no debug lines are present, returns the end of the list.
   */
  private static int debugLinesStart(List<Component> lines) {
    for (int i = lines.size() - 1; i >= 1; i--) {
      String plain = ChatFormatting.stripFormatting(lines.get(i).getString());
      if (plain == null) continue;
      if (!DEBUG_ID_PATTERN.matcher(plain).matches()
          && !DEBUG_COMPONENTS_PATTERN.matcher(plain).matches()) {
        return i + 1;
      }
    }
    return 1;
  }

  private static String stripPercentSuffix(String plainText) {
    return PERCENT_SUFFIX_PATTERN.matcher(plainText).replaceFirst("");
  }

  /**
   * Extracts a trailing Wynntils percentage suffix (e.g. "[61.7%]") from the original
   * component, preserving its color. Wynntils appends the percentage as a sibling
   * Component with its own Style/TextColor, so we check siblings first.
   * Falls back to scanning for §-codes in legacy-formatted text.
   * Returns null if no colored suffix is present.
   */
  private static Component extractColoredSuffix(Component original, String plainText) {
    if (plainText == null) return null;
    Matcher m = PERCENT_SUFFIX_PATTERN.matcher(plainText);
    if (!m.find()) return null;

    String suffix = m.group(1);

    // Wynntils adds the percentage as a sibling Component with its own Style.
    // Walk siblings last-to-first to find it.
    List<Component> siblings = original.getSiblings();
    for (int i = siblings.size() - 1; i >= 0; i--) {
      Component sibling = siblings.get(i);
      if (sibling.getString().contains(suffix)) {
        return sibling.copy();
      }
    }

    // Fallback: scan raw text for §-codes (legacy formatting)
    String raw = original.getString();
    int suffixStart = raw.lastIndexOf(suffix);
    if (suffixStart < 0) return null;

    for (int i = suffixStart - 1; i >= 1; i--) {
      if (raw.charAt(i - 1) == '\u00A7') {
        ChatFormatting fmt = ChatFormatting.getByCode(raw.charAt(i));
        if (fmt != null && fmt.isColor()) {
          return Component.literal(" " + suffix).withStyle(fmt);
        }
      }
    }

    return null;
  }

  /**
   * Builds the gold "Legacy Item (Rarity)" or "Beta Legacy Item (Rarity [3])" label.
   *
   * @param rarity the rarity name (e.g. "Legendary", "Normal")
   * @param count  optional Wynntils count suffix like "[3]", or null
   * @param prefix optional era prefix like "Alpha" or "Beta", or null
   */
  private static Component buildLegacyLabel(String rarity, String count, String prefix) {
    String base = prefix != null ? prefix + " Legacy Item" : "Legacy Item";
    String label =
        count != null
            ? base + " (" + rarity + " " + count + ")"
            : base + " (" + rarity + ")";
    return Component.literal(label).withStyle(ChatFormatting.GOLD);
  }
}
