package org.wynnvets.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Tooltip rewriting and Component tree manipulation for legacy items.
 *
 * <p>Handles the visual transformation of tooltip lines — recoloring names,
 * inserting LEGACY box markers, replacing rarity lines, and managing the
 * new-format PUA-encoded tooltip structure. Detection logic remains in
 * {@link LegacyItemHandler}.</p>
 */
final class LegacyTooltipRenderer {

  /** Font used by Wynncraft's new-format emblem/frame line (lore[0] — the duplicate item name). */
  private static final FontDescription EMBLEM_FRAME_FONT =
      new FontDescription.Resource(Identifier.parse("tooltip/emblem/frame"));

  /** Font used by Wynncraft's new-format banner/box rarity line (lore[1] — RARE / WAND boxes). */
  private static final FontDescription BANNER_BOX_FONT =
      new FontDescription.Resource(Identifier.parse("banner/box"));

  /** Font used by Wynncraft's spacing characters (leading spacer on rarity/emblem lines). */
  private static final FontDescription SPACE_FONT =
      new FontDescription.Resource(Identifier.parse("space"));

  /**
   * PUA-encoded "LEGACY" text for the banner/box font.
   * Structure: box_start + [neg_space + letter_bg]×6 + box_end + spacing + §0(black) + foreground_letters + terminator.
   * Letter mapping: background = U+E030 + (letter - 'A'), foreground = U+E000 + (letter - 'A').
   * Spacing byte U+CFFDA matches 6-letter words (same width class as COMMON).
   */
  private static final String LEGACY_BOX_TEXT =
      "\uE060\uDAFF\uDFFF\uE03B\uDAFF\uDFFF\uE034\uDAFF\uDFFF\uE036"
      + "\uDAFF\uDFFF\uE030\uDAFF\uDFFF\uE032\uDAFF\uDFFF\uE048"
      + "\uDAFF\uDFFF\uE062\uDAFF\uDFDA\u00A70\uE00B\uE004\uE006"
      + "\uE000\uE002\uE018\uDB00\uDC02";

  private static final Pattern DEBUG_ID_PATTERN = Pattern.compile("^\\w+:\\w[\\w/.-]*$");
  private static final Pattern DEBUG_COMPONENTS_PATTERN =
      Pattern.compile("^\\d+ component\\(s\\)$");
  private static final Pattern PERCENT_SUFFIX_PATTERN =
      Pattern.compile("\\s*(\\[\\d+\\.?\\d*%\\])$");
  private static final String ENCHANTED_PREFIX = "\u2B21 Enchanted ";
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

    // Strip our own "⬡ Enchanted " prefix (added by the getHoverName mixin) so we
    // don't apply it twice, and strip the Wynntils percentage for clean name matching.
    if (plainText != null) {
      if (plainText.startsWith(ENCHANTED_PREFIX)) {
        plainText = plainText.substring(ENCHANTED_PREFIX.length());
      }
      plainText = stripPercentSuffix(plainText);
    }

    if (plainText != null && ItemDefinitions.isLegacy(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      boolean newFormat = isNewFormatItem(modified);
      if (newFormat) {
        // Restore PUA-wrapped custom name as line 0 to preserve spacer layout.
        // The visible name is in the emblem/frame lore line, not the hover name.
        Component customName = LegacyItemHandler.currentItemStack.get(DataComponents.CUSTOM_NAME);
        boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
        if (customName != null) {
          modified.set(0, enchanted
              ? deepEnchantName(customName, plainText, TextColor.fromLegacyFormat(ChatFormatting.YELLOW))
              : customName);
        }
        if (enchanted) {
          applyEnchantedToNewFormatNameLine(modified, plainText);
        } else {
          recolorNewFormatNameLine(modified);
        }
        if (insertLegacyBoxLine(modified)) {
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
      boolean newFormat = isNewFormatItem(modified);
      if (newFormat) {
        Component customName = LegacyItemHandler.currentItemStack.get(DataComponents.CUSTOM_NAME);
        boolean enchanted = LegacyItemHandler.currentItemHasFoil && !ItemDefinitions.isUnenchanted(plainText);
        if (customName != null) {
          modified.set(0, enchanted
              ? deepEnchantName(customName, plainText, TextColor.fromLegacyFormat(ChatFormatting.YELLOW))
              : customName);
        }
        if (enchanted) {
          applyEnchantedToNewFormatNameLine(modified, plainText);
        } else {
          recolorNewFormatNameLine(modified);
        }
        if (insertLegacyBoxLine(modified)) {
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
      List<Component> modified = new ArrayList<>(tooltipLines);
      boolean newFormat = isNewFormatItem(modified);
      if (newFormat) {
        Component customName = LegacyItemHandler.currentItemStack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
          modified.set(0, deepEnchantName(customName, plainText,
              TextColor.fromLegacyFormat(ChatFormatting.YELLOW)));
        }
        applyEnchantedToNewFormatNameLine(modified, plainText);
        if (insertLegacyBoxLine(modified)) {
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

  /**
   * Returns {@code true} if the given Component tree contains any node whose
   * resolved font matches {@code target}.
   */
  private static boolean containsFont(Component root, FontDescription target) {
    boolean[] found = {false};
    root.visit((Style style, String text) -> {
      if (target.equals(style.getFont())) {
        found[0] = true;
        return Optional.of(Boolean.TRUE);
      }
      return Optional.empty();
    }, Style.EMPTY);
    return found[0];
  }

  /**
   * Returns {@code true} if the item uses Wynncraft's new tooltip format.
   * Detected by the presence of the {@code tooltip/emblem/frame} font in a lore line.
   */
  private static boolean isNewFormatItem(List<Component> lines) {
    for (Component line : lines) {
      if (containsFont(line, EMBLEM_FRAME_FONT)) return true;
    }
    return false;
  }

  /**
   * Recolors the new-format emblem/wynncraft-font name line from pink (#FF55FF)
   * to gold, preserving the emblem frame, sprites, and wynncraft font.
   */
  private static void recolorNewFormatNameLine(List<Component> lines) {
    TextColor pink = TextColor.fromRgb(0xFF55FF);
    TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
    for (int i = 1; i < lines.size(); i++) {
      if (containsFont(lines.get(i), EMBLEM_FRAME_FONT)) {
        lines.set(i, deepRecolor(lines.get(i), pink, gold));
        return;
      }
    }
  }

  /**
   * Recursively deep-copies a Component tree, replacing all occurrences of
   * {@code from} color with {@code to} color.
   */
  private static MutableComponent deepRecolor(Component component, TextColor from, TextColor to) {
    MutableComponent copy = component.plainCopy();
    Style style = component.getStyle();
    if (from.equals(style.getColor())) {
      style = style.withColor(to);
    }
    copy.setStyle(style);
    for (Component sibling : component.getSiblings()) {
      copy.append(deepRecolor(sibling, from, to));
    }
    return copy;
  }

  /**
   * Modifies the new-format emblem/frame name line to show
   * "Enchanted [name]" in yellow, preserving font structure and PUA spacing.
   */
  private static void applyEnchantedToNewFormatNameLine(List<Component> lines, String itemName) {
    TextColor yellow = TextColor.fromLegacyFormat(ChatFormatting.YELLOW);
    for (int i = 1; i < lines.size(); i++) {
      if (containsFont(lines.get(i), EMBLEM_FRAME_FONT)) {
        lines.set(i, deepEnchantName(lines.get(i), itemName, yellow));
        return;
      }
    }
  }

  /**
   * Deep-copies a Component tree, finding the literal containing the exact
   * item name and replacing it with "Enchanted [name]" in the target color.
   * Other components (sprites, spacers, fonts) are preserved unchanged.
   */
  private static MutableComponent deepEnchantName(Component component, String originalName, TextColor targetColor) {
    String contentStr = component.getContents().toString();
    MutableComponent copy;
    Style style = component.getStyle();

    if (contentStr.equals("literal{" + originalName + "}")) {
      copy = Component.literal("Enchanted " + originalName);
      style = style.withColor(targetColor);
    } else {
      copy = component.plainCopy();
    }

    copy.setStyle(style);
    for (Component sibling : component.getSiblings()) {
      copy.append(deepEnchantName(sibling, originalName, targetColor));
    }
    return copy;
  }

  /**
   * Prepends a gold-colored LEGACY box to the existing banner/box rarity line
   * in new-format tooltips (e.g. [LEGACY][RARE][WAND] on a single line).
   * Returns {@code true} if such a line was found and modified.
   */
  private static boolean insertLegacyBoxLine(List<Component> lines) {
    for (int i = 1; i < lines.size(); i++) {
      if (containsFont(lines.get(i), BANNER_BOX_FONT)) {
        Component original = lines.get(i);
        List<Component> origSiblings = original.getSiblings();

        // Build combined line: leading spacer + LEGACY box + inter-box space + original boxes
        MutableComponent combined = Component.empty().withStyle(original.getStyle());

        // Copy leading spacer (first sibling is the horizontal offset in space font)
        if (!origSiblings.isEmpty()) {
          combined.append(origSiblings.get(0).copy());
        }

        // Gold LEGACY box
        combined.append(Component.literal(LEGACY_BOX_TEXT)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withoutShadow()
                .withFont(BANNER_BOX_FONT)));

        // Inter-box space (U+D0001 in space font, same as between RARE and WAND)
        combined.append(Component.literal("\uDB00\uDC01")
            .setStyle(Style.EMPTY.withFont(SPACE_FONT)));

        // Remaining siblings (the actual rarity/type box content)
        for (int j = 1; j < origSiblings.size(); j++) {
          combined.append(origSiblings.get(j).copy());
        }

        lines.set(i, combined);
        return true;
      }
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
