package org.wynnvets.items;

import com.wynntils.core.text.fonts.FontManager;
import com.wynntils.core.text.fonts.wynnfonts.BannerBoxFont;
import com.wynntils.utils.colors.CustomColor;
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
 * LEGACY box insertion, name line recoloring, name prefixing ("Enchanted",
 * "Underrolled"), the gold reroll box of an underrolled item and font-based
 * tree traversal. Orchestration and old-format fallback remain in
 * {@link LegacyTooltipRenderer}.</p>
 *
 * <p>A new-format tooltip is recognised by its fonts rather than by line
 * position: the name line is drawn in {@code tooltip/emblem/frame}, and the
 * rarity and type chips ({@code [RARE][WAND]}) in {@code banner/box}, a font
 * whose labels are PUA glyph runs (see {@link #LEGACY_BOX_BACKGROUND}).</p>
 */
final class NewFormatRenderer {

    /** Font of Wynncraft's new-format emblem/frame line (lore[0], the duplicate item name). */
    private static final FontDescription EMBLEM_FRAME_FONT =
            new FontDescription.Resource(Identifier.parse("tooltip/emblem/frame"));

    /** Font of Wynncraft's boxed labels: the rarity line's chips and the page headers. */
    static final FontDescription BANNER_BOX_FONT =
            new FontDescription.Resource(Identifier.parse("banner/box"));

    /** Font used by Wynncraft's banner/symbol tier indicators (e.g. ★★★ on ingredients). */
    private static final FontDescription BANNER_SYMBOL_FONT =
            new FontDescription.Resource(Identifier.parse("banner/symbol"));

    /** Font of the dividers between a tooltip's sections. */
    static final FontDescription DIVIDER_FONT =
            new FontDescription.Resource(Identifier.parse("tooltip/divider"));

    /** Font whose glyphs only move the cursor ({@link FontManager#calculateOffset}). */
    static final FontDescription SPACE_FONT =
            new FontDescription.Resource(Identifier.parse("space"));

    /** Font of the reroll badge drawn after the reroll count's box. */
    private static final FontDescription TOOLTIP_BANNER_FONT =
            new FontDescription.Resource(Identifier.parse("tooltip/banner"));

    /** Left half of the reroll badge, in {@link #TOOLTIP_BANNER_FONT}. */
    private static final char REROLL_BADGE_LEFT = (char) 0xE005;

    /**
     * Right half of the reroll badge, and so the last glyph of the reroll line. Wynntils'
     * {@code WynnItemParser} finds the line by it too.
     */
    private static final char REROLL_BADGE_RIGHT = (char) 0xF005;

    /** How far right Wynncraft starts a reroll line with a one-digit count, in pixels. */
    private static final int REROLL_INDENT = 123;

    /**
     * Box half of the gold LEGACY chip in the {@code banner/box} font. Its
     * lettering is {@link #LEGACY_BOX_FOREGROUND}, drawn over it as a nested
     * child by {@link #legacyBox()}.
     *
     * <p>Wynncraft's box font renders rarity labels (RARE, LEGENDARY, etc.) using
     * a two-layer PUA encoding: background letter glyphs provide the coloured box
     * fill, and foreground letter glyphs render the text on top.  Negative-width
     * spacing characters overlap the layers.</p>
     *
     * <p>Byte-level structure of both halves:</p>
     * <pre>
     *   LEGACY_BOX_BACKGROUND
     *   E060                  — box_start (opens the coloured box frame)
     *   [DAFF DFFF  E03B]     — neg_space (U+CFFFF, 1 px left) + bg 'L'  (E030 + 11)
     *   [DAFF DFFF  E034]     — neg_space + bg 'E'  (E030 + 4)
     *   [DAFF DFFF  E036]     — neg_space + bg 'G'  (E030 + 6)
     *   [DAFF DFFF  E030]     — neg_space + bg 'A'  (E030 + 0)
     *   [DAFF DFFF  E032]     — neg_space + bg 'C'  (E030 + 2)
     *   [DAFF DFFF  E048]     — neg_space + bg 'Y'  (E030 + 24)
     *   DAFF DFFF  E062       — neg_space + box_end (closes the coloured box frame)
     *   DAFF DFDA             — back-kern (U+CFFDA, 38 px left): returns the cursor
     *                           into the box so the lettering draws over it; sized
     *                           to the box just drawn
     *   LEGACY_BOX_FOREGROUND
     *   E00B E004 E006        — fg 'L' 'E' 'G'      (E000 + letter offset)
     *   E000 E002 E018        — fg 'A' 'C' 'Y'      (E000 + letter offset)
     *   DB00 DC02             — terminator (U+D0002)
     * </pre>
     *
     * <p>Letter mapping: background = {@code U+E030 + (letter - 'A')},
     * foreground = {@code U+E000 + (letter - 'A')}.</p>
     */
    private static final String LEGACY_BOX_BACKGROUND =
            "\uE060\uDAFF\uDFFF\uE03B\uDAFF\uDFFF\uE034\uDAFF\uDFFF\uE036"
                    + "\uDAFF\uDFFF\uE030\uDAFF\uDFFF\uE032\uDAFF\uDFFF\uE048"
                    + "\uDAFF\uDFFF\uE062\uDAFF\uDFDA";

    /**
     * Lettering half of the LEGACY chip: the letters drawn back over
     * {@link #LEGACY_BOX_BACKGROUND}, then the trailing terminator.
     */
    private static final String LEGACY_BOX_FOREGROUND =
            "\uE00B\uE004\uE006\uE000\uE002\uE018\uDB00\uDC02";

    /**
     * Shadow colour Wynncraft puts on its own boxed labels (white at zero alpha),
     * mirrored so the boxes vetsmod draws match theirs.
     *
     * <p>For visible output this matches {@link Style#withoutShadow()}
     * ({@code withShadowColor(0)}) in vanilla: a shadow colour of exactly 0
     * skips the shadow pass, and vanilla's text shader discards this colour's
     * zero-alpha pass.</p>
     */
    static final int BOX_SHADOW = 0xFFFFFF;

    private static final TextColor GOLD = TextColor.fromLegacyFormat(ChatFormatting.GOLD);

    /**
     * Builds the gold LEGACY chip as two nodes, mirroring how Wynncraft emits its
     * own: the box run, then the lettering as a nested child.
     *
     * <p>The foreground is a child rather than a {@code \u00A70} colour code inlined
     * into the run, matching the two-node shape Wynncraft emits.  The shadow is
     * not the reason: vanilla's {@code Style.applyLegacyFormat} carries the run's
     * shadow colour across an inlined colour code (Wynntils' {@code StyledText}
     * parser, by contrast, drops it, restarting the style from the colour and
     * the inherited font).</p>
     */
    private static MutableComponent legacyBox() {
        return Component.literal(LEGACY_BOX_BACKGROUND)
                .setStyle(
                        Style.EMPTY
                                .withColor(ChatFormatting.GOLD)
                                .withShadowColor(BOX_SHADOW)
                                .withFont(BANNER_BOX_FONT))
                .append(
                        Component.literal(LEGACY_BOX_FOREGROUND)
                                .setStyle(
                                        Style.EMPTY
                                                .withColor(ChatFormatting.BLACK)
                                                .withShadowColor(BOX_SHADOW)));
    }

    private NewFormatRenderer() {}

    /**
     * Returns {@code true} if the item uses Wynncraft's new tooltip format.
     * Detected by the presence of {@code tooltip/emblem/frame} or {@code banner/box}
     * font in a tooltip line.  The latter covers Wynntils-rebuilt tooltips that
     * preserve PUA rarity boxes but use a different font for the emblem/name.
     *
     * <p>On a hit it also sets the sticky
     * {@link LegacyItemHandler#newTooltipStylesAvailable} flag (the
     * PATCH(old-server-compat) block below; drop this sentence with it), so it is
     * not a pure probe.</p>
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
     * Returns the index of the name line, the first line drawn in the
     * emblem/frame font, or {@code -1} if there is none. Scans from line 0
     * because the layouts differ: wynnmod puts the emblem/frame line at index
     * 0, while Wynntils keeps a customName placeholder there and the
     * emblem/frame line at index 1.
     */
    private static int findNameLine(List<Component> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (containsFont(lines.get(i), EMBLEM_FRAME_FONT)) return i;
        }
        return -1;
    }

    /**
     * Returns {@code true} if this node's own content is a literal reading
     * exactly {@code itemName} ({@code LiteralContents.toString()} is
     * {@code literal{text}}).
     */
    private static boolean isNameLiteral(Component component, String itemName) {
        return component.getContents().toString().equals("literal{" + itemName + "}");
    }

    /**
     * Recolors the new-format name line (see {@link #findNameLine}) to gold,
     * targeting the literal node containing the item name.
     */
    static void recolorNewFormatNameLine(List<Component> lines, String itemName) {
        int i = findNameLine(lines);
        if (i < 0) return;
        lines.set(i, deepRecolorName(lines.get(i), itemName, GOLD));
    }

    /**
     * Deep-copies a Component tree, finding the literal containing the exact
     * item name and recoloring it to the target color. Other components
     * (sprites, spacers, fonts) are preserved unchanged.
     */
    private static MutableComponent deepRecolorName(
            Component component, String itemName, TextColor targetColor) {
        MutableComponent copy = component.plainCopy();
        Style style = component.getStyle();

        if (isNameLiteral(component, itemName)) {
            style = style.withColor(targetColor);
        }

        copy.setStyle(style);
        for (Component sibling : component.getSiblings()) {
            copy.append(deepRecolorName(sibling, itemName, targetColor));
        }
        return copy;
    }

    /**
     * Modifies the new-format name line (see {@link #findNameLine}) to show
     * {@code prefix + name} in gold ("Enchanted Leech Bow", "Underrolled Solidified Chestplate"),
     * preserving font structure and PUA spacing.
     */
    static void applyPrefixToNewFormatNameLine(
            List<Component> lines, String itemName, String prefix) {
        int i = findNameLine(lines);
        if (i < 0) {
            VetsLogger.debug(
                    "applyPrefixToNewFormatNameLine: NO emblem/frame line found for '{}'",
                    itemName);
            return;
        }
        VetsLogger.debug(
                "applyPrefixToNewFormatNameLine: found emblem/frame at line {}, itemName='{}'",
                i,
                itemName);
        Component before = lines.get(i);
        lines.set(i, deepPrefixName(before, itemName, prefix, GOLD));
        VetsLogger.debug(
                "applyPrefixToNewFormatNameLine: before='{}' after='{}'",
                before.getString(),
                lines.get(i).getString());
    }

    /**
     * Deep-copies a Component tree, finding the literal containing the exact
     * item name and replacing it with {@code prefix + name} in the target color.
     * Other components (sprites, spacers, fonts) are preserved unchanged.
     */
    private static MutableComponent deepPrefixName(
            Component component, String originalName, String prefix, TextColor targetColor) {
        MutableComponent copy;
        Style style = component.getStyle();

        if (isNameLiteral(component, originalName)) {
            VetsLogger.debug(
                    "deepPrefixName MATCH: '{}' -> '{}{}'", originalName, prefix, originalName);
            copy = Component.literal(prefix + originalName);
            style = style.withColor(targetColor);
        } else {
            copy = component.plainCopy();
        }

        copy.setStyle(style);
        for (Component sibling : component.getSiblings()) {
            copy.append(deepPrefixName(sibling, originalName, prefix, targetColor));
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
    static boolean containsFont(Component root, FontDescription target) {
        boolean[] found = {false};
        root.visit(
                (Style style, String text) -> {
                    if (target.equals(style.getFont())) {
                        found[0] = true;
                        return Optional.of(Boolean.TRUE);
                    }
                    return Optional.empty();
                },
                Style.EMPTY);
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
     * (with inter-box delimiter) at the box layout level. Mutates the tree in place.
     *
     * <p>The box layout level is the node whose children are the chips. It is found in
     * three phases:
     * <ol>
     *   <li>A direct child has {@code banner/box} font: insert before it.</li>
     *   <li>A child has {@code banner/symbol} font (an ingredient's tier stars), which marks
     *       this level even when the {@code banner/box} node is wrapped in a colour node:
     *       insert before the first child that <em>contains</em> {@code banner/box}.</li>
     *   <li>Otherwise, recurse into each child that contains {@code banner/box}.</li>
     * </ol></p>
     */
    private static boolean insertLegacyIntoTree(MutableComponent node) {
        List<Component> siblings = node.getSiblings();

        // Phase 1: a direct banner/box child.
        for (int i = 0; i < siblings.size(); i++) {
            if (BANNER_BOX_FONT.equals(siblings.get(i).getStyle().getFont())) {
                insertLegacyBoxAt(siblings, i);
                return true;
            }
        }

        // Phase 2: a banner/symbol child marks the box level; the box sits in a wrapper.
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
                    insertLegacyBoxAt(siblings, i);
                    return true;
                }
            }
        }

        // Phase 3: recurse into children that contain the banner/box font.
        for (Component sibling : siblings) {
            if (sibling instanceof MutableComponent mc && containsFont(mc, BANNER_BOX_FONT)) {
                if (insertLegacyIntoTree(mc)) return true;
            }
        }
        return false;
    }

    /**
     * Leaves the LEGACY chip at {@code index}, followed by the delimiter Wynncraft puts between
     * chips (U+D0001), ahead of the node that was at {@code index}. The delimiter is added
     * first because both go in at the same index.
     */
    private static void insertLegacyBoxAt(List<Component> siblings, int index) {
        siblings.add(
                index,
                Component.literal("\uDB00\uDC01").setStyle(Style.EMPTY.withFont(BANNER_BOX_FONT)));
        siblings.add(index, legacyBox());
    }

    /**
     * Recolours the reroll line's box and badge gold, keeping the count's black digits and the
     * badge's untinted right half as they are. Returns {@code false} when no line ends in the
     * reroll badge: any page but 1, or a count of 0, which Wynncraft draws no line for.
     */
    static boolean recolorRerollBox(List<Component> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).getString();
            if (!text.isEmpty() && text.charAt(text.length() - 1) == REROLL_BADGE_RIGHT) {
                lines.set(i, deepRecolorReroll(lines.get(i), FontDescription.DEFAULT));
                return true;
            }
        }
        return false;
    }

    /**
     * Deep-copies a Component tree, recolouring gold each node that draws the box (a
     * {@code banner/box} node whose own text holds a box glyph: the boxed alphabet and digits
     * and the caps, U+E030 to U+E062) or the badge's tinted left half. The digits drawn over
     * the box (U+E020 up) are below that range, so they keep their colour. A node that sets no
     * font draws in {@code inherited}, its parent's.
     */
    private static MutableComponent deepRecolorReroll(
            Component component, FontDescription inherited) {
        Style style = component.getStyle();
        FontDescription font =
                FontDescription.DEFAULT.equals(style.getFont()) ? inherited : style.getFont();
        MutableComponent copy = component.plainCopy();
        String text = copy.getString();
        boolean box =
                BANNER_BOX_FONT.equals(font)
                        && text.chars().anyMatch(c -> c >= 0xE030 && c <= 0xE062);
        boolean badge = TOOLTIP_BANNER_FONT.equals(font) && text.indexOf(REROLL_BADGE_LEFT) >= 0;
        if (box || badge) style = style.withColor(GOLD);
        copy.setStyle(style);
        for (Component sibling : component.getSiblings()) {
            copy.append(deepRecolorReroll(sibling, font));
        }
        return copy;
    }

    /**
     * Inserts a reroll line reading 0, its box and badge gold, after the last section
     * divider: where Wynncraft draws the line on page 1 when the count is above 0. Does nothing
     * when the tooltip has no divider.
     */
    static void insertZeroRerollLine(List<Component> lines) {
        int divider = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (containsFont(lines.get(i), DIVIDER_FONT)) divider = i;
        }
        if (divider >= 0) lines.add(divider + 1, zeroRerollLine());
    }

    /**
     * Builds the reroll line for a count of 0, laid out as Wynncraft lays out a one-digit
     * count: an indent, the boxed count (Wynntils' {@link BannerBoxFont#buildMessage}, then a
     * 2 px gap), and the badge's two halves. The left half is the tinted one, gold here; the
     * right half is drawn untinted, as Wynncraft draws it.
     */
    private static MutableComponent zeroRerollLine() {
        Style space = Style.EMPTY.withFont(SPACE_FONT);
        Style badge = Style.EMPTY.withFont(TOOLTIP_BANNER_FONT).withShadowColor(BOX_SHADOW);
        return Component.empty()
                .append(
                        Component.literal(FontManager.calculateOffset(0, REROLL_INDENT))
                                .setStyle(space))
                .append(
                        BannerBoxFont.buildMessage(
                                "0",
                                CustomColor.fromChatFormatting(ChatFormatting.GOLD),
                                CustomColor.fromChatFormatting(ChatFormatting.BLACK),
                                FontManager.calculateOffset(0, 2)))
                .append(Component.literal(FontManager.calculateOffset(0, -1)).setStyle(space))
                .append(
                        Component.literal(String.valueOf(REROLL_BADGE_LEFT))
                                .setStyle(badge.withColor(GOLD)))
                .append(
                        Component.literal(FontManager.calculateOffset(0, -10) + REROLL_BADGE_RIGHT)
                                .setStyle(badge));
    }
}
