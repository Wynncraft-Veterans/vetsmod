package org.wynnvets.items;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

public class LegacyItemHandler {

  /** Set by the highlight mixin before tooltip processing to indicate the hovered item has foil. */
  public static boolean currentItemHasFoil = false;

  private static final Pattern RARITY_PATTERN =
      Pattern.compile(
        "^(Mythic|Fabled|Set|Legendary|Rare|Unique|Normal|Junk|Misc\\.|Crafting) Item(?: (\\[\\d+\\]))?$");

  private static final Pattern LV_MIN_PATTERN = Pattern.compile("^Lv\\.? min: \\d+$");
  private static final Pattern ALPHA_NAME_PATTERN =
      Pattern.compile("^\\d{10,}(?: \\(#\\d+\\))?$");
  private static final Pattern DEBUG_ID_PATTERN = Pattern.compile("^\\w+:\\w[\\w/.-]*$");
  private static final Pattern DEBUG_COMPONENTS_PATTERN =
      Pattern.compile("^\\d+ component\\(s\\)$");
  private static final Pattern CRAFTED_PATTERN =
      Pattern.compile(
          "^Crafted (?:Helmet|Chestplate|Pants|Boots|Ring|Potion|Scroll|Food|Wand|Spear|Relik|Bow|Dagger|by .+) \\[\\d+/\\d+ Durability\\]$");

  public static List<Component> processTooltip(List<Component> tooltipLines) {
    if (tooltipLines.isEmpty()) return tooltipLines;

    if (isCraftedItem(tooltipLines)) return tooltipLines;

    Component firstLine = tooltipLines.get(0);
    String rawText = firstLine.getString();
    String plainText = ChatFormatting.stripFormatting(rawText);

    if (plainText != null && ItemDefinitions.isLegacy(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      modified.set(0, Component.literal(plainText).withStyle(ChatFormatting.GOLD));
      replaceRarityLines(modified, null);
      return modified;
    }

    if (hasBetaLegacyMarker(tooltipLines)) {
      boolean alpha = plainText != null && ALPHA_NAME_PATTERN.matcher(plainText).matches();
      List<Component> modified = new ArrayList<>(tooltipLines);
      if (plainText != null) {
        modified.set(0, Component.literal(plainText).withStyle(ChatFormatting.GOLD));
      }
      replaceRarityLines(modified, alpha ? "Alpha" : "Beta");
      return modified;
    }

    if (currentItemHasFoil && plainText != null && !ItemDefinitions.isUnenchanted(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      modified.set(
          0,
          Component.literal("\u2B21 ")
              .withStyle(ChatFormatting.WHITE)
              .append(
                  Component.literal("Enchanted " + plainText).withStyle(ChatFormatting.GOLD)));
      replaceRarityLines(modified, null);
      return modified;
    }

    if (hasJunkRarity(tooltipLines) && plainText != null && !ItemDefinitions.isNotJunk(plainText)) {
      List<Component> modified = new ArrayList<>(tooltipLines);
      modified.set(0, Component.literal(plainText).withStyle(ChatFormatting.GOLD));
      replaceRarityLines(modified, null);
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

  public static boolean hasJunkRarity(List<Component> lines) {
    for (Component line : lines) {
      String plain = ChatFormatting.stripFormatting(line.getString());
      if ("Junk Item".equals(plain)) return true;
    }
    return false;
  }

  public static boolean hasBetaLegacyMarker(List<Component> lines) {
    for (Component line : lines) {
      String raw = line.getString();
      String plain = ChatFormatting.stripFormatting(raw);
      if (plain == null || !LV_MIN_PATTERN.matcher(plain).matches()) continue;
      if (isExclusivelyGold(line)) return true;
    }
    return false;
  }

  private static boolean isExclusivelyGold(Component component) {
    String raw = component.getString();
    boolean foundColorCode = false;
    for (int i = 0; i < raw.length() - 1; i++) {
      if (raw.charAt(i) == '\u00A7') {
        char code = Character.toLowerCase(raw.charAt(i + 1));
        if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
          foundColorCode = true;
          if (code != '6') return false;
        }
      }
    }
    if (foundColorCode) return true;

    // No section-sign color codes in text — check component style
    TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
    if (gold == null) return false;
    TextColor styleColor = component.getStyle().getColor();
    return gold.equals(styleColor);
  }

  private static void replaceRarityLines(List<Component> lines, String prefix) {
    if (lines.size() < 2) return;

    Component legacyLabel = null;

    // Scan bottom-up to find and remove the rarity line
    for (int i = lines.size() - 1; i >= 1; i--) {
      String plain = ChatFormatting.stripFormatting(lines.get(i).getString());
      if (plain == null) continue;

      Matcher m = RARITY_PATTERN.matcher(plain);
      if (m.matches()) {
        legacyLabel = buildLegacyLabel(m.group(1), m.group(2), prefix);
        lines.remove(i);
        break;
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

  private static Component buildLegacyLabel(String rarity, String count, String prefix) {
    String base = prefix != null ? prefix + " Legacy Item" : "Legacy Item";
    String label =
        count != null
            ? base + " (" + rarity + " " + count + ")"
            : base + " (" + rarity + ")";
    return Component.literal(label).withStyle(ChatFormatting.GOLD);
  }
}
