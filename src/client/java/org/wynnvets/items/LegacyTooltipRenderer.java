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
import org.wynnvets.logging.VetsLogger;

/**
 * Tooltip rewriting orchestration for legacy items.
 *
 * <p>Coordinates detection state from {@link LegacyItemHandler}, delegates
 * new-format PUA-encoded operations to {@link NewFormatRenderer}, and handles
 * old-format rarity line replacement and text helpers locally.</p>
 */
final class LegacyTooltipRenderer {

  private static final Pattern DEBUG_ID_PATTERN = Pattern.compile("^\\w+:\\w[\\w/.-]*$");
  private static final Pattern DEBUG_COMPONENTS_PATTERN =
      Pattern.compile("^\\d+ component\\(s\\)$");
  private static final Pattern PERCENT_SUFFIX_PATTERN =
      Pattern.compile("\\s*(\\[\\d+\\.?\\d*%\\])$");
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
    LegacyItemHandler.lastProcessedWasLegacy = false;
    if (!org.wynnvets.config.VetsConfig.get(org.wynnvets.config.VetsConfig.LEGACY_ITEM_HIGHLIGHTING)) return tooltipLines;
    if (tooltipLines.isEmpty()) return tooltipLines;
    if (LegacyItemHandler.isBlockedScreen()) return tooltipLines;

    if (isCraftedItem(tooltipLines)) return tooltipLines;

    Component firstLine = tooltipLines.get(0);
    String rawText = firstLine.getString();
    String plainText = LegacyItemHandler.normalizeName(ChatFormatting.stripFormatting(rawText));

    // Extract Wynntils rarity suffix (e.g. "[61.7%]") preserving its original color
    Component raritySuffix = extractColoredSuffix(firstLine, plainText);

    // Strip the Wynntils percentage suffix for clean name matching.
    if (plainText != null) {
      plainText = stripPercentSuffix(plainText);
    }

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
        if (NewFormatRenderer.insertLegacyBoxLine(modified)) {
          LegacyItemHandler.lastProcessedWasLegacy = true;
          return modified;
        }
      }
      return tooltipLines;
    }

    if (plainText != null && ItemDefinitions.isLegacy(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      boolean newFormat = NewFormatRenderer.isNewFormatItem(modified);
      if (newFormat) {
        // Restore PUA-wrapped custom name as line 0 to preserve spacer layout.
        // The visible name is in the emblem/frame lore line, not the hover name.
        Component customName = LegacyItemHandler.currentItemStack.get(DataComponents.CUSTOM_NAME);
        boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
        if (customName != null) {
          modified.set(0, enchanted
              ? NewFormatRenderer.deepEnchantName(customName, plainText, TextColor.fromLegacyFormat(ChatFormatting.GOLD))
              : customName);
        }
        if (enchanted) {
          NewFormatRenderer.applyEnchantedToNewFormatNameLine(modified, plainText);
        } else {
          NewFormatRenderer.recolorNewFormatNameLine(modified, plainText);
        }
        if (NewFormatRenderer.insertLegacyBoxLine(modified)) {
          LegacyItemHandler.lastProcessedWasLegacy = true;
          return modified;
        }
      }
      MutableComponent name;
      if (LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText)) {
        name = Component.literal("\u2B21 ")
            .withStyle(ChatFormatting.WHITE)
            .append(Component.literal("Enchanted " + plainText).withStyle(ChatFormatting.GOLD));
      } else {
        name = Component.literal(plainText).withStyle(ChatFormatting.GOLD);
      }
      if (raritySuffix != null) name.append(raritySuffix);
      modified.set(0, name);
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    if (plainText != null && ItemDefinitions.isMiscLegacy(plainText) && LegacyItemHandler.hasMiscRarity(tooltipLines)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      boolean newFormat = NewFormatRenderer.isNewFormatItem(modified);
      if (newFormat) {
        Component customName = LegacyItemHandler.currentItemStack.get(DataComponents.CUSTOM_NAME);
        boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
        if (customName != null) {
          modified.set(0, enchanted
              ? NewFormatRenderer.deepEnchantName(customName, plainText, TextColor.fromLegacyFormat(ChatFormatting.GOLD))
              : customName);
        }
        if (enchanted) {
          NewFormatRenderer.applyEnchantedToNewFormatNameLine(modified, plainText);
        } else {
          NewFormatRenderer.recolorNewFormatNameLine(modified, plainText);
        }
        if (NewFormatRenderer.insertLegacyBoxLine(modified)) {
          LegacyItemHandler.lastProcessedWasLegacy = true;
          return modified;
        }
      }
      MutableComponent name;
      if (LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText)) {
        name = Component.literal("\u2B21 ")
            .withStyle(ChatFormatting.WHITE)
            .append(Component.literal("Enchanted " + plainText).withStyle(ChatFormatting.GOLD));
      } else {
        name = Component.literal(plainText).withStyle(ChatFormatting.GOLD);
      }
      if (raritySuffix != null) name.append(raritySuffix);
      modified.set(0, name);
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    if (LegacyItemHandler.hasBetaLegacyMarker(tooltipLines)) {
      boolean alpha = !LegacyItemHandler.hasRarityLine(tooltipLines);
      List<Component> modified = new ArrayList<>(tooltipLines);
      if (plainText != null) {
        MutableComponent name;
        if (LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText)) {
          name = Component.literal("\u2B21 ")
              .withStyle(ChatFormatting.WHITE)
              .append(Component.literal("Enchanted " + plainText).withStyle(ChatFormatting.GOLD));
        } else {
          name = Component.literal(plainText).withStyle(ChatFormatting.GOLD);
        }
        if (raritySuffix != null) name.append(raritySuffix);
        modified.set(0, name);
      }
      replaceRarityLines(modified, alpha ? "Alpha" : "Beta");
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

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
      if (newFormat) {
        Component customName = LegacyItemHandler.currentItemStack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
          modified.set(0, NewFormatRenderer.deepEnchantName(customName, plainText,
              TextColor.fromLegacyFormat(ChatFormatting.GOLD)));
        }
        NewFormatRenderer.applyEnchantedToNewFormatNameLine(modified, plainText);
        if (NewFormatRenderer.insertLegacyBoxLine(modified)) {
          LegacyItemHandler.lastProcessedWasLegacy = true;
          return modified;
        }
      }
      MutableComponent name =
          Component.literal("\u2B21 ")
              .withStyle(ChatFormatting.WHITE)
              .append(
                  Component.literal("Enchanted " + plainText).withStyle(ChatFormatting.GOLD));
      if (raritySuffix != null) name.append(raritySuffix);
      modified.set(0, name);
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    if (LegacyItemHandler.hasJunkRarity(tooltipLines) && plainText != null && !ItemDefinitions.isNotJunk(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      MutableComponent name = Component.literal(plainText).withStyle(ChatFormatting.GOLD);
      if (raritySuffix != null) name.append(raritySuffix);
      modified.set(0, name);
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    if (LegacyItemHandler.hasCraftingRarity(tooltipLines) && plainText != null) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      MutableComponent name = Component.literal(plainText).withStyle(ChatFormatting.GOLD);
      if (raritySuffix != null) name.append(raritySuffix);
      modified.set(0, name);
      replaceRarityLines(modified, null);
      LegacyItemHandler.lastProcessedWasLegacy = true;
      return modified;
    }

    return tooltipLines;
  }

  private static boolean isCraftedItem(List<Component> lines) {
    for (Component line : lines) {
      String plain = ChatFormatting.stripFormatting(line.getString());
      if (plain != null && CRAFTED_PATTERN.matcher(plain).matches()) return true;
    }
    return false;
  }

  private static void replaceRarityLines(List<Component> lines, String prefix) {
    if (lines.size() < 2) return;

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

  private static Component buildLegacyLabel(String rarity, String count, String prefix) {
    String base = prefix != null ? prefix + " Legacy Item" : "Legacy Item";
    String label =
        count != null
            ? base + " (" + rarity + " " + count + ")"
            : base + " (" + rarity + ")";
    return Component.literal(label).withStyle(ChatFormatting.GOLD);
  }
}
