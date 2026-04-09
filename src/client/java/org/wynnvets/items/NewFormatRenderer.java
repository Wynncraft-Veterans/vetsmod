package org.wynnvets.items;

import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

/**
 * New-format (PUA-encoded) tooltip manipulation for legacy items.
 *
 * <p>Handles Component tree operations on Wynncraft's new tooltip format:
 * LEGACY box insertion, name line recoloring, enchanted name rewriting,
 * and font-based tree traversal. Orchestration and old-format fallback
 * remain in {@link LegacyTooltipRenderer}.</p>
 */
final class NewFormatRenderer {

  /** Font used by Wynncraft's new-format emblem/frame line (lore[0] — the duplicate item name). */
  private static final FontDescription EMBLEM_FRAME_FONT =
      new FontDescription.Resource(Identifier.parse("tooltip/emblem/frame"));

  /** Font used by Wynncraft's new-format banner/box rarity line (lore[1] — RARE / WAND boxes). */
  private static final FontDescription BANNER_BOX_FONT =
      new FontDescription.Resource(Identifier.parse("banner/box"));

  /** Font used by Wynncraft's banner/symbol tier indicators (e.g. ★★★ on ingredients). */
  private static final FontDescription BANNER_SYMBOL_FONT =
      new FontDescription.Resource(Identifier.parse("banner/symbol"));

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

  private NewFormatRenderer() {}

  /**
   * Returns {@code true} if the item uses Wynncraft's new tooltip format.
   * Detected by the presence of the {@code tooltip/emblem/frame} font in a lore line.
   */
  static boolean isNewFormatItem(List<Component> lines) {
    for (Component line : lines) {
      if (containsFont(line, EMBLEM_FRAME_FONT)) {
        // BEGIN PATCH(old-server-compat)
        LegacyItemHandler.newTooltipStylesAvailable = true;
        // END PATCH(old-server-compat)
        return true;
      }
    }
    return false;
  }

  /**
   * Recolors the new-format emblem/wynncraft-font name line to gold,
   * targeting the literal node containing the item name.
   */
  static void recolorNewFormatNameLine(List<Component> lines, String itemName) {
    TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
    for (int i = 1; i < lines.size(); i++) {
      if (containsFont(lines.get(i), EMBLEM_FRAME_FONT)) {
        lines.set(i, deepRecolorName(lines.get(i), itemName, gold));
        return;
      }
    }
  }

  /**
   * Deep-copies a Component tree, finding the literal containing the exact
   * item name and recoloring it to the target color. Other components
   * (sprites, spacers, fonts) are preserved unchanged.
   */
  private static MutableComponent deepRecolorName(Component component, String itemName, TextColor targetColor) {
    String contentStr = component.getContents().toString();
    MutableComponent copy = component.plainCopy();
    Style style = component.getStyle();

    if (contentStr.equals("literal{" + itemName + "}")) {
      style = style.withColor(targetColor);
    }

    copy.setStyle(style);
    for (Component sibling : component.getSiblings()) {
      copy.append(deepRecolorName(sibling, itemName, targetColor));
    }
    return copy;
  }

  /**
   * Modifies the new-format emblem/frame name line to show
   * "Enchanted [name]" in yellow, preserving font structure and PUA spacing.
   */
  static void applyEnchantedToNewFormatNameLine(List<Component> lines, String itemName) {
    TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
    for (int i = 1; i < lines.size(); i++) {
      if (containsFont(lines.get(i), EMBLEM_FRAME_FONT)) {
        lines.set(i, deepEnchantName(lines.get(i), itemName, gold));
        return;
      }
    }
  }

  /**
   * Deep-copies a Component tree, finding the literal containing the exact
   * item name and replacing it with "Enchanted [name]" in the target color.
   * Other components (sprites, spacers, fonts) are preserved unchanged.
   */
  static MutableComponent deepEnchantName(Component component, String originalName, TextColor targetColor) {
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
  static boolean insertLegacyBoxLine(List<Component> lines) {
    for (int i = 1; i < lines.size(); i++) {
      if (containsFont(lines.get(i), BANNER_BOX_FONT)) {
        MutableComponent lineCopy = mutableDeepCopy(lines.get(i));
        if (insertLegacyIntoTree(lineCopy)) {
          lines.set(i, lineCopy);
          return true;
        }
      }
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
   * Deep-copies a Component tree into a fully mutable MutableComponent clone.
   * Unlike {@code Component.copy()}, the returned tree has mutable sibling
   * lists at every level, and all styles (including fonts) are preserved.
   */
  private static MutableComponent mutableDeepCopy(Component component) {
    MutableComponent copy = component.plainCopy().withStyle(component.getStyle());
    for (Component sibling : component.getSiblings()) {
      copy.append(mutableDeepCopy(sibling));
    }
    return copy;
  }

  /**
   * Walks a mutable-deep-copied MutableComponent tree and inserts a gold LEGACY box
   * (with inter-box delimiter) at the correct box layout level. Mutates the tree in place.
   *
   * <p>The "box layout level" is detected in two ways:
   * <ol>
   *   <li>A direct child has {@code banner/box} font — insert before it (e.g. Harmstick).</li>
   *   <li>A sibling has {@code banner/symbol} font (tier stars), indicating this level
   *       contains box elements even if {@code banner/box} is wrapped in a color node —
   *       insert before the first child that <em>contains</em> {@code banner/box}
   *       (e.g. Pigman Meat's INGREDIENT wrapper).</li>
   * </ol></p>
   */
  private static boolean insertLegacyIntoTree(MutableComponent node) {
    List<Component> siblings = node.getSiblings();

    // Phase 1: Direct banner/box child — insert before it.
    for (int i = 0; i < siblings.size(); i++) {
      if (BANNER_BOX_FONT.equals(siblings.get(i).getStyle().getFont())) {
        siblings.add(i, Component.literal("\uDB00\uDC01"));
        siblings.add(i, Component.literal(LEGACY_BOX_TEXT)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withoutShadow()
                .withFont(BANNER_BOX_FONT)));
        return true;
      }
    }

    // Phase 2: banner/symbol sibling indicates this is the box layout level.
    // Insert before the first child wrapping banner/box (color wrapper node).
    boolean hasSymbolSibling = false;
    for (Component child : siblings) {
      if (BANNER_SYMBOL_FONT.equals(child.getStyle().getFont())) {
        hasSymbolSibling = true;
        break;
      }
    }
    if (hasSymbolSibling) {
      for (int i = 0; i < siblings.size(); i++) {
        if (containsFont(siblings.get(i), BANNER_BOX_FONT)) {
          siblings.add(i, Component.literal("\uDB00\uDC01"));
          siblings.add(i, Component.literal(LEGACY_BOX_TEXT)
              .setStyle(Style.EMPTY
                  .withColor(ChatFormatting.GOLD)
                  .withoutShadow()
                  .withFont(BANNER_BOX_FONT)));
          return true;
        }
      }
    }

    // Phase 3: Recurse into children that contain the banner/box font.
    for (Component sibling : siblings) {
      if (sibling instanceof MutableComponent mc && containsFont(mc, BANNER_BOX_FONT)) {
        if (insertLegacyIntoTree(mc)) return true;
      }
    }
    return false;
  }
}
