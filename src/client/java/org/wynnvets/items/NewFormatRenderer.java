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
import org.wynnvets.logging.VetsLogger;

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
   * PUA-encoded "LEGACY" text for the {@code banner/box} font.
   *
   * <p>Wynncraft's box font renders rarity labels (RARE, LEGENDARY, etc.) using
   * a two-layer PUA encoding: background letter glyphs provide the coloured box
   * fill, and foreground letter glyphs render the text on top.  Negative-width
   * spacing characters overlap the layers.</p>
   *
   * <p>Byte-level structure of this constant:</p>
   * <pre>
   *   E060                  — box_start (opens the coloured box frame)
   *   [DAFF DFFF  E03B]     — neg_space + bg 'L'  (E030 + 11)
   *   [DAFF DFFF  E034]     — neg_space + bg 'E'  (E030 + 4)
   *   [DAFF DFFF  E036]     — neg_space + bg 'G'  (E030 + 6)
   *   [DAFF DFFF  E030]     — neg_space + bg 'A'  (E030 + 0)
   *   [DAFF DFFF  E032]     — neg_space + bg 'C'  (E030 + 2)
   *   [DAFF DFFF  E048]     — neg_space + bg 'Y'  (E030 + 24)
   *   DAFF DFFF  E062       — neg_space + box_end (closes the coloured box frame)
   *   DAFF DFDA             — inter-box spacing (same width class as 6-letter words like COMMON)
   *   00A7 30               — §0 = black colour code (section sign + '0')
   *   E00B E004 E006        — fg 'L' 'E' 'G'      (E000 + letter offset)
   *   E000 E002 E018        — fg 'A' 'C' 'Y'      (E000 + letter offset)
   *   DB00 DC02             — terminator (surrogate pair)
   * </pre>
   *
   * <p>Letter mapping: background = {@code U+E030 + (letter - 'A')},
   * foreground = {@code U+E000 + (letter - 'A')}.</p>
   */
  private static final String LEGACY_BOX_BACKGROUND =
      "\uE060\uDAFF\uDFFF\uE03B\uDAFF\uDFFF\uE034\uDAFF\uDFFF\uE036"
      + "\uDAFF\uDFFF\uE030\uDAFF\uDFFF\uE032\uDAFF\uDFFF\uE048"
      + "\uDAFF\uDFFF\uE062\uDAFF\uDFDA";

  /** Foreground half of the same constant: the letters drawn back over the box. */
  private static final String LEGACY_BOX_FOREGROUND =
      "\uE00B\uE004\uE006\uE000\uE002\uE018\uDB00\uDC02";

  /**
   * Shadow colour Wynncraft puts on its own rarity chips \u2014 white at zero alpha.
   *
   * <p>{@link Style#withoutShadow()} is <em>not</em> equivalent: it compiles to
   * {@code withShadowColor(0)}, black at zero alpha, which leaves a black drop
   * shadow under the chip's black lettering and reads as bold beside the
   * server's own UNIQUE/BOW chips.</p>
   */
  private static final int BOX_SHADOW = 0xFFFFFF;

  /**
   * Builds the gold LEGACY chip as two nodes, mirroring how Wynncraft emits its
   * own: the box run, then the lettering as a nested child.
   *
   * <p>The foreground must be a child rather than a {@code \u00A70} colour code
   * inlined into the run \u2014 a legacy colour code resets the style mid-string and
   * takes the shadow setting with it, so the lettering would draw its shadow
   * regardless of what the run asked for.</p>
   */
  private static MutableComponent legacyBox() {
    return Component.literal(LEGACY_BOX_BACKGROUND)
        .setStyle(Style.EMPTY
            .withColor(ChatFormatting.GOLD)
            .withShadowColor(BOX_SHADOW)
            .withFont(BANNER_BOX_FONT))
        .append(Component.literal(LEGACY_BOX_FOREGROUND)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.BLACK)
                .withShadowColor(BOX_SHADOW)));
  }

  private NewFormatRenderer() {}

  /**
   * Returns {@code true} if the item uses Wynncraft's new tooltip format.
   * Detected by the presence of {@code tooltip/emblem/frame} or {@code banner/box}
   * font in a tooltip line.  The latter covers Wynntils-rebuilt tooltips that
   * preserve PUA rarity boxes but use a different font for the emblem/name.
   */
  static boolean isNewFormatItem(List<Component> lines) {
    for (Component line : lines) {
      if (containsFont(line, EMBLEM_FRAME_FONT) || containsFont(line, BANNER_BOX_FONT)) {
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
   * targeting the literal node containing the item name. Scans from line 0
   * so wynnmod-decorated layouts (which promote the emblem/frame line to
   * index 0) are handled identically to the Wynntils-default layout (which
   * keeps a customName placeholder at index 0 and the emblem/frame line at
   * index 1).
   */
  static void recolorNewFormatNameLine(List<Component> lines, String itemName) {
    TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
    for (int i = 0; i < lines.size(); i++) {
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
   * Scans from line 0 so wynnmod-decorated layouts (which promote the
   * emblem/frame line to index 0) are handled identically to the
   * Wynntils-default layout (which keeps a customName placeholder at
   * index 0 and the emblem/frame line at index 1).
   */
  static void applyEnchantedToNewFormatNameLine(List<Component> lines, String itemName) {
    TextColor gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
    for (int i = 0; i < lines.size(); i++) {
      if (containsFont(lines.get(i), EMBLEM_FRAME_FONT)) {
        VetsLogger.debug("applyEnchantedToNewFormatNameLine: found emblem/frame at line {}, itemName='{}'", i, itemName);
        Component before = lines.get(i);
        lines.set(i, deepEnchantName(lines.get(i), itemName, gold));
        VetsLogger.debug("applyEnchantedToNewFormatNameLine: before='{}' after='{}'", before.getString(), lines.get(i).getString());
        return;
      }
    }
    VetsLogger.debug("applyEnchantedToNewFormatNameLine: NO emblem/frame line found for '{}'", itemName);
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
      VetsLogger.debug("deepEnchantName MATCH: '{}' -> 'Enchanted {}'", originalName, originalName);
      copy = Component.literal("Enchanted " + originalName);
      style = style.withColor(targetColor);
    } else {
      if (contentStr.contains(originalName)) {
        VetsLogger.debug("deepEnchantName NEAR-MISS: contentStr='{}' (len={}), looking for='literal{{}}'", contentStr, contentStr.length(), originalName);
        StringBuilder hexC = new StringBuilder();
        for (int ci = 0; ci < contentStr.length(); ci++) hexC.append(String.format("%04x ", (int)contentStr.charAt(ci)));
        VetsLogger.debug("deepEnchantName NEAR-MISS hex: {}", hexC.toString().trim());
      }
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
   * in new-format tooltips (e.g. {@code [LEGACY][RARE][WAND]} on a single line).
   * Returns {@code true} if such a line was found and modified.
   *
   * <p>The Component tree for a rarity line is complex: the banner/box font
   * node may be a direct child, wrapped in a colour node, or nested deeper.
   * {@link #insertLegacyIntoTree} handles all three layouts via a 3-phase
   * search (direct child → symbol-sibling heuristic → recursive descent).</p>
   */
  static boolean insertLegacyBoxLine(List<Component> lines) {
    for (int i = 0; i < lines.size(); i++) {
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
        siblings.add(i, Component.literal("\uDB00\uDC01")
            .setStyle(Style.EMPTY.withFont(BANNER_BOX_FONT)));
        siblings.add(i, legacyBox());
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
          siblings.add(i, Component.literal("\uDB00\uDC01")
              .setStyle(Style.EMPTY.withFont(BANNER_BOX_FONT)));
          siblings.add(i, legacyBox());
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
