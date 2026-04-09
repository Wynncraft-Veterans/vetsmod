package org.wynnvets.items;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Detects and re-styles legacy, enchanted, junk, and crafting items in
 * Wynncraft item tooltips.
 *
 * <p>Works in concert with the mixin hooks ({@code LegacyItemTooltipMixin},
 * {@code LegacyItemNameMixin}, {@code LegacyHotbarMixin}, {@code LegacyHighlightMixin})
 * to identify special items by name pattern and rarity line, then rewrites
 * their tooltip display names and rarity labels accordingly.</p>
 */
public class LegacyItemHandler {

  /** Set by the highlight mixin before tooltip processing to indicate the hovered item has foil. */
  public static boolean currentItemHasFoil = false;

  /** Set by the highlight mixin before tooltip processing to provide item context for tooltip_style access. */
  public static ItemStack currentItemStack = ItemStack.EMPTY;

  /**
   * Set by {@link #processTooltip} when it modifies a legacy item's tooltip.
   * The tooltip mixin reads this to override the border colour.
   */
  public static boolean lastProcessedWasLegacy = false;

  /**
   * Set by {@link org.wynnvets.listeners.LegacyTooltipEventHandler} when it
   * processes a tooltip via the Wynntils event bus.  Cleared by
   * {@code LegacyItemTooltipMixin} so the mixin skips redundant re-processing
   * of the same tooltip list.
   */
  public static boolean eventProcessedTooltip = false;

  // BEGIN PATCH(old-server-compat): Remove field once all servers use new tooltip borders.
  /**
   * Sticky server-session flag: set to {@code true} when any item with a
   * {@code tooltip_style} data component or new-format emblem/frame font is
   * encountered.  When {@code false} (i.e. on old servers that lack the new
   * resource pack), the gold tooltip border override is suppressed to avoid
   * garish fallback colours.
   * <p>Reset on disconnect by {@link org.wynnvets.listeners.ServerConnectionListener}.</p>
   */
  public static boolean newTooltipStylesAvailable = false;
  // END PATCH(old-server-compat)

  /** Gold tooltip border identifier — matches the vanilla "unique" rarity border. */
  public static final Identifier LEGACY_BORDER = Identifier.parse("unique");

  /**
   * Screen titles where legacy-item processing is skipped entirely.
   * Some Wynncraft menus (e.g. "Island Rules") apply enchantment glints as
   * UI selectors, which the foil-based detection misidentifies as legacy items.
   */
  private static final List<String> BLOCKED_SCREEN_TITLES = List.of("Island Rules", "Move here!");

  /**
   * Returns {@code true} if the current screen title matches a known menu that
   * should never be treated as containing legacy items.
   */
  public static boolean isBlockedScreen() {
    Screen screen = Minecraft.getInstance().screen;
    if (screen == null) return false;
    String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
    return title != null && BLOCKED_SCREEN_TITLES.contains(title);
  }

  /**
   * Returns {@code true} if the item uses Wynncraft's new display format,
   * identified by supplementary PUA characters (U+10000+) in the custom name
   * component.  Used with {@link ItemDefinitions#isNewFormatOverride} to suppress
   * legacy name matches for specific items that exist in both old and new formats.
   */
  public static boolean isNewFormatItem(ItemStack stack) {
    Component customName = stack.get(DataComponents.CUSTOM_NAME);
    if (customName == null) return false;
    String raw = customName.getString();
    for (int i = 0; i < raw.length(); ) {
      int cp = raw.codePointAt(i);
      if (cp >= 0x10000) return true;
      i += Character.charCount(cp);
    }
    return false;
  }

  /** Returns true if the given ItemStack should be treated as a legacy item.
   *  Always returns false when {@link org.wynnvets.config.VetsConfig#LEGACY_ITEM_HIGHLIGHTING}
   *  is disabled. */
  public static boolean isLegacyItem(ItemStack stack) {
    if (!org.wynnvets.config.VetsConfig.get(org.wynnvets.config.VetsConfig.LEGACY_ITEM_HIGHLIGHTING)) return false;
    if (stack.isEmpty()) return false;
    if (isBlockedScreen()) return false;

    String rawHover = stack.getHoverName().getString();
    String stripped = ChatFormatting.stripFormatting(rawHover);
    String name = normalizeName(stripped);
    boolean newFormatOverridden = name != null && isNewFormatItem(stack) && ItemDefinitions.isNewFormatOverride(name);

    if (!newFormatOverridden && name != null && ItemDefinitions.isLegacy(name)) return true;

    List<Component> lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();

    if (!newFormatOverridden && name != null && ItemDefinitions.isMiscLegacy(name) && hasMiscRarity(lore)) return true;

    if (hasBetaLegacyMarker(lore)) return true;

    if (stack.hasFoil() && !ItemDefinitions.isEnchantExcludedItem(stack) && (name == null || !ItemDefinitions.isUnenchanted(name))) return true;

    if (hasJunkRarity(lore) && (name == null || !ItemDefinitions.isNotJunk(name))) return true;

    if (hasCraftingRarity(lore)) return true;

    return false;
  }

  /**
   * Normalizes item names for pattern matching by stripping:
   * <ul>
   *   <li>Supplementary PUA characters (U+F0000-U+10FFFF) used by Wynncraft's
   *       new item format as invisible spacing/formatting glyphs</li>
   *   <li>Trailing À (U+00C0) appended in trade market listings</li>
   * </ul>
   */
  public static String normalizeName(String name) {
    if (name == null) return null;
    String result = stripSupplementaryPua(name);
    if (result.endsWith("\u00C0")) {
      result = result.substring(0, result.length() - 1).stripTrailing();
    }
    return result;
  }

  /**
   * Strips all supplementary Unicode code points (U+10000 and above) from text.
   * Wynncraft's new item format wraps item names with invisible spacing and
   * font-switching glyphs from various supplementary planes (observed at
   * U+CF000 and in Supplementary PUA-A/B). Since item display names only
   * contain BMP characters, all supplementary code points are removed
   * before name pattern matching.
   */
  private static String stripSupplementaryPua(String text) {
    StringBuilder sb = null;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      int charCount = Character.charCount(cp);
      if (cp >= 0x10000) {
        if (sb == null) {
          sb = new StringBuilder(text.length());
          sb.append(text, 0, i);
        }
      } else if (sb != null) {
        sb.appendCodePoint(cp);
      }
      i += charCount;
    }
    return sb != null ? sb.toString().strip() : text;
  }

  static final Pattern RARITY_PATTERN =
      Pattern.compile(
        "^(Mythic|Fabled|Set|Legendary|Rare|Unique|Normal|Junk|Misc\\.|Crafting) Item(?: (\\[\\d+\\]))?$");

  private static final Pattern LV_MIN_PATTERN = Pattern.compile("^Lv\\.? min: \\d+$");

  /**
   * Processes and rewrites tooltip lines for legacy/enchanted/junk/crafting items.
   * Returns the unmodified list when {@link org.wynnvets.config.VetsConfig#LEGACY_ITEM_HIGHLIGHTING}
   * is disabled.
   *
   * @param tooltipLines the original tooltip lines
   * @return the (possibly modified) tooltip lines
   */
  public static List<Component> processTooltip(List<Component> tooltipLines) {
    return LegacyTooltipRenderer.processTooltip(tooltipLines);
  }

  /** Returns {@code true} if any tooltip line starts with "Misc. Item". */
  public static boolean hasMiscRarity(List<Component> lines) {
    for (Component line : lines) {
      String plain = ChatFormatting.stripFormatting(line.getString());
      if (plain != null && plain.startsWith("Misc. Item")) return true;
    }
    return false;
  }

  /** Returns {@code true} if any tooltip line reads "Junk Item". */
  public static boolean hasJunkRarity(List<Component> lines) {
    for (Component line : lines) {
      String plain = ChatFormatting.stripFormatting(line.getString());
      if ("Junk Item".equals(plain)) return true;
    }
    return false;
  }

  /** Returns {@code true} if any tooltip line reads "Crafting Item". */
  public static boolean hasCraftingRarity(List<Component> lines) {
    for (Component line : lines) {
      String plain = ChatFormatting.stripFormatting(line.getString());
      if ("Crafting Item".equals(plain)) return true;
    }
    return false;
  }

  /** Returns {@code true} if any tooltip line matches the standard rarity pattern. */
  public static boolean hasRarityLine(List<Component> lines) {
    for (Component line : lines) {
      String plain = ChatFormatting.stripFormatting(line.getString());
      if (plain != null && RARITY_PATTERN.matcher(plain).matches()) return true;
    }
    return false;
  }

  /** Returns {@code true} if a gold-coloured "Lv. min" line is present (beta legacy marker). */
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

  /** Maps {@code tooltip_style} data component paths to human-readable rarity names. */
  private static final Map<String, String> TOOLTIP_STYLE_TO_RARITY = Map.of(
      "common", "Normal",
      "unique", "Unique",
      "rare", "Rare",
      "set", "Set",
      "legendary", "Legendary",
      "fabled", "Fabled",
      "mythic", "Mythic",
      "crafted", "Crafted");

  /**
   * Extracts the item rarity from the {@code tooltip_style} data component.
   * New-format Wynncraft items encode their rarity tier as a {@code tooltip_style}
   * identifier (e.g. {@code minecraft:rare}, {@code minecraft:unique}) rather than
   * including a plain-text rarity line in the lore.
   *
   * @return the human-readable rarity name, or {@code null} if unavailable
   */
  public static String getTooltipStyleRarity(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return null;
    Identifier tooltipStyle = stack.get(DataComponents.TOOLTIP_STYLE);
    if (tooltipStyle == null) return null;
    return TOOLTIP_STYLE_TO_RARITY.get(tooltipStyle.getPath());
  }
}
