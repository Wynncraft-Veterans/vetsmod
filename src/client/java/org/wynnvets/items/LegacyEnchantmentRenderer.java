package org.wynnvets.items;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.wynnvets.logging.VetsLogger;

/**
 * Names the specific vanilla enchantment a legacy item carries: a
 * "LEGACY ENCHANTMENTS" block on page 2 (the powder page) of Wynncraft's
 * new-format tooltips, or a plain text entry on tooltips that have no such
 * page.  See {@link #showEnchantment} for which form goes where.
 *
 * <h2>Why this data still exists</h2>
 * <p>Wynncraft applies a real {@code minecraft:enchantments} component to
 * legacy enchanted items and then suppresses it from the tooltip via
 * {@code minecraft:tooltip_display}'s {@code hiddenComponents} set.  That
 * suppression is <em>render-time only</em> — the component is still on the
 * stack the client holds, on every page of the item.  Modern items ship the
 * same component with an <em>empty</em> map, so a non-empty map is itself a
 * reliable legacy signal, independent of {@link ItemStack#hasFoil()} (which is
 * also true for items that merely set {@code enchantment_glint_override}).</p>
 *
 * <h2>Layout</h2>
 * <p>The block mirrors Wynncraft's own POWDER SOCKETS block so it reads as
 * native: a boxed header with a right-aligned count, a blank line, then one
 * socket-sprite entry.  The count is always {@code 1/1} — only ever one
 * enchantment could be applied to an item.</p>
 *
 * <pre>
 *   POWDER SOCKETS        1/1     &lt;- Wynncraft's
 *
 *   ◈ Water II
 *                                 &lt;- inserted from here down
 *   LEGACY ENCHANTMENTS   1/1
 *
 *   ◈ Knockback I
 * </pre>
 *
 * <h2>PUA encoding</h2>
 * <p>Two glyph alphabets drive the boxed header, the same pair documented on
 * {@code NewFormatRenderer.LEGACY_BOX_TEXT}: a background letter at
 * {@code U+E030 + (c - 'A')} draws the coloured box segment behind each
 * character, and a foreground letter at {@code U+E000 + (c - 'A')} draws the
 * text on top.  A back-kern between the two layers returns the cursor to the
 * box interior.  Horizontal movement uses the {@code minecraft:space} font,
 * where the codepoint {@code U+D0000 + n} advances the cursor by {@code n}
 * pixels (negative {@code n} lands below {@code U+D0000} and moves left).</p>
 *
 * <p>Both the back-kern and the count's right-alignment padding are
 * <em>measured at runtime</em> via {@link Font#width} rather than hardcoded,
 * because each background glyph is exactly as wide as the letter it sits
 * behind — so a label of a different length needs different spacing, and the
 * widths themselves come from Wynncraft's resource pack.</p>
 */
final class LegacyEnchantmentRenderer {

    private static final FontDescription BANNER_BOX_FONT =
            new FontDescription.Resource(Identifier.parse("banner/box"));
    private static final FontDescription SPACE_FONT =
            new FontDescription.Resource(Identifier.parse("space"));
    private static final FontDescription SOCKET_FONT =
            new FontDescription.Resource(Identifier.parse("tooltip/socket"));
    private static final FontDescription PAGE_FONT =
            new FontDescription.Resource(Identifier.parse("tooltip/page"));
    private static final FontDescription WYNNCRAFT_FONT =
            new FontDescription.Resource(Identifier.parse("language/wynncraft"));
    private static final FontDescription DEFAULT_FONT =
            new FontDescription.Resource(Identifier.parse("default"));

    /** Base of the {@code minecraft:space} font's signed-advance range. */
    private static final int SPACE_BASE = 0xD0000;

    /** Box frame glyphs in the {@code banner/box} font. */
    private static final char BOX_START = '';

    private static final char BOX_SPACE = '';
    private static final char BOX_END = '';

    /** Background (box fill) alphabet origin — {@code U+E030} is 'A'. */
    private static final char BOX_BG_A = '';

    /** Foreground (text) alphabet origin — {@code U+E000} is 'A'. */
    private static final char BOX_FG_A = '';

    /** Trailing terminator Wynncraft appends after a boxed label. */
    private static final String BOX_TERMINATOR = "󐀂";

    /** Header label. Uppercase only — the box alphabets cover A-Z and space. */
    private static final String HEADER_LABEL = "LEGACY ENCHANTMENTS";

    /** Colour of Wynncraft's own POWDER SOCKETS box fill. */
    private static final int BOX_FILL = 0xFFF2B3;

    /** Indent Wynntils' item-guess block gives its child lines. */
    private static final String GUESS_INDENT = "    ";

    /** Colour of the leading bullet on Wynntils' item-guess lines. */
    private static final ChatFormatting BULLET_COLOR = ChatFormatting.GREEN;

    /**
     * Shadow colour Wynncraft puts on its boxed headers — white at zero alpha.
     *
     * <p>Deliberately not {@link Style#withoutShadow()}, which compiles to
     * {@code withShadowColor(0)}: black at zero alpha.  The alpha does not
     * suppress the shadow, so that leaves a black drop shadow under black label
     * text, doubling every glyph into something that reads as bold.  White
     * disappears into the box fill instead.</p>
     */
    private static final int BOX_SHADOW = 0xFFFFFF;

    /**
     * The powder element whose sprite and colour a given enchantment borrows.
     * Wynncraft's skill-to-element mapping is the bridge: strength is Earth,
     * intelligence is Water, defence is Fire — so an enchantment described as
     * "the strength colour" renders with the Earth socket sprite.
     */
    private enum Element {
        EARTH('', ChatFormatting.DARK_GREEN),
        WATER('', ChatFormatting.AQUA),
        FIRE('', ChatFormatting.RED);

        final char sprite;
        final ChatFormatting color;

        Element(char sprite, ChatFormatting color) {
            this.sprite = sprite;
            this.color = color;
        }
    }

    /**
     * The six enchantments Wynncraft ever applied to items, mapped to the
     * element whose colour they are shown in.  Keyed by the enchantment's
     * registry path so an unexpected enchantment falls through to a neutral
     * rendering rather than being silently dropped.
     */
    private static final Map<String, Element> ENCHANT_ELEMENTS =
            Map.of(
                    "sharpness", Element.EARTH,
                    "thorns", Element.EARTH,
                    "knockback", Element.WATER,
                    "feather_falling", Element.WATER,
                    "fire_aspect", Element.FIRE,
                    "blast_protection", Element.FIRE);

    private LegacyEnchantmentRenderer() {}

    // ── Public entry point ─────────────────────────────────────────────

    /**
     * Surfaces the specific enchantment, whichever tooltip format the item uses.
     *
     * <p>New-format items get the LEGACY ENCHANTMENTS block on the powder page.
     * Older items (the V2 era) have no pages at all, but they also do not hide
     * {@code minecraft:enchantments} behind {@code tooltip_display} — their
     * {@code hiddenComponents} covers only attribute modifiers and unbreakable —
     * so Minecraft already draws a vanilla enchantment line. That line is missing
     * its numeral, and is corrected in place rather than duplicated.</p>
     *
     * <p>Unidentified items fall between the two: they are new-format, so
     * {@code tooltip_display} hides the component and there is no vanilla line to
     * correct, but they have no pages either, so there is no powder block to hang
     * the boxed header off.  Those get a plain text pair instead, matching the
     * shape of Wynntils' item-guess block above it.</p>
     *
     * @return {@code true} if the tooltip was changed
     */
    static boolean showEnchantment(List<Component> lines, ItemStack stack) {
        if (insertEnchantmentBlock(lines, stack)) return true;
        if (addLevelToVanillaLine(lines, stack)) return true;
        return appendTextEntry(lines, stack);
    }

    /**
     * Appends the enchantment as a plain two-line text entry at the end of a
     * pageless tooltip, styled to match Wynntils' item-guess block:
     *
     * <pre>
     *   - Possibilities:                                    &lt;- Wynntils'
     *       - Lv. 8 [7 ✦]: Bolt, Fatigue, Thief's Dagger
     *   - Legacy:                                           &lt;- inserted
     *       - Enchantment: Thorns II
     * </pre>
     *
     * <p>The insertion point is the end of the tooltip's content, before any F3+H
     * debug lines — which on an unidentified item is exactly below the item-guess
     * block, since that is the last thing anything adds.  Anchoring on position
     * rather than on the word "Possibilities" keeps this working when Wynntils is
     * absent, when its item-guess feature is off, and in every locale.</p>
     *
     * <p>Restricted to pageless tooltips.  Pages 1 and 3 of a paged item reach
     * here as well — no socket line, and no vanilla line either — and must not be
     * given the entry, or a three-page item would name its enchantment on all
     * three pages instead of only in the powder block.  Every page of a paged item
     * carries the page-indicator font; a pageless tooltip carries none.</p>
     *
     * @return {@code true} if the entry was appended
     */
    private static boolean appendTextEntry(List<Component> lines, ItemStack stack) {
        Holder<Enchantment> holder = firstEnchantment(stack);
        if (holder == null) return false;

        for (Component line : lines) {
            if (containsFont(line, PAGE_FONT)) return false;
        }

        int level =
                stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                        .getLevel(holder);
        String name = enchantmentLabel(holder, level);
        Element element = elementFor(holder);

        int at = LegacyTooltipRenderer.debugLinesStart(lines);
        lines.add(at, bulletLine("", "Legacy:", null, null));
        lines.add(at + 1, bulletLine(GUESS_INDENT, "Enchantment: ", name, element.color));
        VetsLogger.debug("appendTextEntry: named '{}' at line {}", name, at);
        return true;
    }

    /**
     * Builds one "- Label: value" line in Wynntils' item-guess styling: a green
     * bullet, a grey label, and an optional value in the enchantment's element
     * colour.
     */
    private static MutableComponent bulletLine(
            String indent, String label, String value, ChatFormatting valueColor) {
        MutableComponent text = Component.literal(label).withStyle(ChatFormatting.GRAY);
        if (value != null) {
            text.append(Component.literal(value).withStyle(valueColor));
        }
        return Component.literal(indent)
                .setStyle(Style.EMPTY.withFont(DEFAULT_FONT))
                .append(Component.literal("- ").withStyle(BULLET_COLOR))
                .append(text);
    }

    /**
     * Rewrites Minecraft's own enchantment line to include the level.
     *
     * <p>Vanilla builds that line with {@link Enchantment#getFullname}, which
     * appends the level through the {@code enchantment.level.N} translation keys.
     * Wynncraft's resource pack blanks those, so a Sharpness II item renders as
     * {@code "Sharpness "} — trailing space, no numeral. The level is still on the
     * stack, so the line is replaced with a correctly numbered one.</p>
     *
     * @return {@code true} if a matching line was found and replaced
     */
    private static boolean addLevelToVanillaLine(List<Component> lines, ItemStack stack) {
        Holder<Enchantment> holder = firstEnchantment(stack);
        if (holder == null) return false;

        int level =
                stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                        .getLevel(holder);
        String bare = holder.value().description().getString().strip();
        String full = enchantmentLabel(holder, level);
        if (bare.equals(full)) return false;

        for (int i = 0; i < lines.size(); i++) {
            String plain = ChatFormatting.stripFormatting(lines.get(i).getString());
            if (plain == null || !plain.strip().equals(bare)) continue;

            // Keep vanilla's own styling; only fall back to grey if it carried none.
            Style style = lines.get(i).getStyle();
            if (style.getColor() == null) style = style.withColor(ChatFormatting.GRAY);
            lines.set(i, Component.literal(full).setStyle(style));
            VetsLogger.debug("addLevelToVanillaLine: '{}' -> '{}' at line {}", bare, full, i);
            return true;
        }
        return false;
    }

    /**
     * Inserts the LEGACY ENCHANTMENTS block after the powder block, if this
     * tooltip is page 2 and the stack carries an enchantment.
     *
     * @return {@code true} if the block was inserted
     */
    private static boolean insertEnchantmentBlock(List<Component> lines, ItemStack stack) {
        Holder<Enchantment> enchantment = firstEnchantment(stack);
        if (enchantment == null) return false;

        // Page 2 is identified by the powder socket sprites; pages 1 and 3 have
        // none.  The banner/box line above them is the POWDER SOCKETS header,
        // whose rendered width is the alignment reference for our own header.
        int firstSocket = -1;
        int lastSocket = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (containsFont(lines.get(i), SOCKET_FONT)) {
                if (firstSocket < 0) firstSocket = i;
                lastSocket = i;
            }
        }
        if (firstSocket < 0) return false;

        int header = -1;
        for (int i = firstSocket - 1; i >= 0; i--) {
            if (containsFont(lines.get(i), BANNER_BOX_FONT)) {
                header = i;
                break;
            }
        }
        if (header < 0) return false;

        Font font = Minecraft.getInstance().font;
        if (font == null) return false;

        int level =
                stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                        .getLevel(enchantment);
        String name = enchantmentLabel(enchantment, level);
        Element element = elementFor(enchantment);

        int targetWidth = font.width(lines.get(header));
        List<Component> block =
                List.of(
                        Component.empty(),
                        buildHeaderLine(font, targetWidth),
                        Component.empty(),
                        buildEntryLine(name, element));

        lines.addAll(lastSocket + 1, block);
        VetsLogger.debug(
                "insertEnchantmentBlock: inserted '{}' after line {} (header at {}, targetWidth={})",
                name,
                lastSocket,
                header,
                targetWidth);
        return true;
    }

    // ── Enchantment lookup ─────────────────────────────────────────────

    /**
     * Returns the stack's enchantment holder, or {@code null} when it has none.
     * Legacy items only ever carry one; if a stack somehow carries several, the
     * first in iteration order wins.
     */
    static Holder<Enchantment> firstEnchantment(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null || enchantments.isEmpty()) return null;
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            return holder;
        }
        return null;
    }

    /**
     * Returns a plain "Sharpness I"-style label for the stack's enchantment, or
     * {@code null} when it has none.  Used by the item-dump debug tool.
     */
    static String enchantmentLabel(ItemStack stack) {
        Holder<Enchantment> holder = firstEnchantment(stack);
        if (holder == null) return null;
        int level =
                stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                        .getLevel(holder);
        return enchantmentLabel(holder, level);
    }

    /**
     * Formats an enchantment as "Sharpness I".
     *
     * <p>Deliberately not {@link Enchantment#getFullname} — that appends the
     * level via the {@code enchantment.level.N} translation keys, which
     * Wynncraft's resource pack blanks out.  Through it, a level-1 Sharpness
     * comes back as {@code "Sharpness "}: trailing space, no numeral.  The
     * numeral is built here instead so it survives the pack.</p>
     */
    private static String enchantmentLabel(Holder<Enchantment> holder, int level) {
        return holder.value().description().getString().strip() + " " + roman(level);
    }

    /**
     * Roman numeral for an enchantment level. Vanilla enchantments cap at V, and
     * the six Wynncraft ever applied cap lower still; anything beyond falls back
     * to the plain number rather than rendering nothing.
     */
    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }

    /** Falls back to Earth for an enchantment outside the known six. */
    private static Element elementFor(Holder<Enchantment> holder) {
        String path = holder.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
        return ENCHANT_ELEMENTS.getOrDefault(path, Element.EARTH);
    }

    // ── Line construction ──────────────────────────────────────────────

    /**
     * Builds the boxed header with its right-aligned "1/1" count, padded so the
     * count's right edge lands exactly where the POWDER SOCKETS count does.
     */
    private static MutableComponent buildHeaderLine(Font font, int targetWidth) {
        String background = boxBackground(HEADER_LABEL);
        String foreground = boxForeground(HEADER_LABEL);

        // Measure each layer separately and derive the assembled advance. Measuring
        // the assembled box instead gives a wrong answer, because its background
        // advance is cancelled by an equal negative back-kern and Font#width does
        // not sum those layered negative advances the way rendering lays them out.
        int startWidth = font.width(styled(String.valueOf(BOX_START), BANNER_BOX_FONT));
        int backgroundWidth = font.width(styled(background, BANNER_BOX_FONT));
        int foregroundWidth = font.width(styled(foreground, BANNER_BOX_FONT));

        // Font#width comes back one pixel short of what the background actually
        // draws, so the back-kern needs that pixel added. Without it the foreground
        // lands 1px right of the background; both layers are the same atlas, so the
        // letters overdraw themselves offset by one and read as bold. Confirmed
        // against Wynncraft's POWDER SOCKETS (-84 where we measure 83) and
        // Wynntils' WYNNPOOL box (-50 for 8 letters).
        int backKern = -(backgroundWidth + 1);
        int boxAdvance = startWidth + backgroundWidth + backKern + foregroundWidth;

        MutableComponent box =
                Component.empty()
                        .setStyle(Style.EMPTY.withColor(BOX_FILL).withShadowColor(BOX_SHADOW))
                        .append(
                                Component.literal(BOX_START + background + spacer(backKern))
                                        .setStyle(Style.EMPTY.withFont(BANNER_BOX_FONT))
                                        // Inherits banner/box from the run above, which is where
                                        // the letter
                                        // alphabet lives. minecraft:default was tried and its
                                        // U+E000 block
                                        // is something else entirely — ~3.5px glyphs rather than
                                        // the 6px
                                        // letters, drawing as unmapped fragments. Wynncraft's own
                                        // back-kern
                                        // (-84 over 14 characters) confirms 6px, so their headers
                                        // use this
                                        // alphabet too.
                                        .append(
                                                Component.literal(foreground)
                                                        .setStyle(
                                                                Style.EMPTY
                                                                        .withColor(
                                                                                ChatFormatting
                                                                                        .BLACK)
                                                                        .withShadowColor(
                                                                                BOX_SHADOW))));

        MutableComponent count =
                Component.literal("1/1")
                        .setStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.GRAY)
                                        .withFont(WYNNCRAFT_FONT));

        int padding = targetWidth - boxAdvance - font.width(count);

        return Component.empty()
                .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withFont(DEFAULT_FONT))
                .append(box)
                .append(
                        Component.literal(spacer(padding))
                                .setStyle(Style.EMPTY.withFont(SPACE_FONT)))
                .append(count);
    }

    /** Wraps a raw PUA run in the font needed to measure or draw it. */
    private static MutableComponent styled(String text, FontDescription font) {
        return Component.literal(text).setStyle(Style.EMPTY.withFont(font));
    }

    /**
     * Builds the single entry line: element socket sprite, a space, then the
     * enchantment name in that element's colour.
     */
    private static MutableComponent buildEntryLine(String name, Element element) {
        return Component.empty()
                .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE).withFont(WYNNCRAFT_FONT))
                .append(
                        Component.empty()
                                .setStyle(
                                        Style.EMPTY
                                                .withColor(ChatFormatting.WHITE)
                                                .withFont(DEFAULT_FONT))
                                .append(
                                        Component.literal(element.sprite + spacer(-1))
                                                .setStyle(Style.EMPTY.withFont(SOCKET_FONT)))
                                .append(Component.literal(" "))
                                .append(
                                        Component.literal(name)
                                                .setStyle(
                                                        Style.EMPTY
                                                                .withColor(element.color)
                                                                .withFont(DEFAULT_FONT))));
    }

    // ── PUA encoding helpers ───────────────────────────────────────────

    /**
     * Encodes the background layer: the bracket-and-fill run that draws the box
     * itself, one segment per character, each preceded by a 1px back-kern so the
     * segments tile seamlessly.  Excludes the opening bracket, which is measured
     * on its own — everything here is what the back-kern must cancel.
     */
    private static String boxBackground(String label) {
        StringBuilder background = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            background.append(spacer(-1)).append(boxGlyph(label.charAt(i), BOX_BG_A, BOX_SPACE));
        }
        return background.append(spacer(-1)).append(BOX_END).toString();
    }

    /**
     * Encodes the foreground layer: the label's letters, drawn on top of the box
     * once the back-kern has returned the cursor to the bracket's interior.
     *
     * <p>Wynncraft emits these as a nested child carrying only {@code color=black}
     * and a zeroed {@code shadowColor}, inheriting {@code banner/box} from the run
     * above.  Both details matter.  Inlining a {@code §0} colour code into the run
     * instead resets the style mid-string and restores the text shadow — black
     * glyphs over a black shadow are what read as "bold".  Setting
     * {@code minecraft:default} on the child instead picks up that font's
     * unrelated {@code U+E000} block rather than the letter alphabet.</p>
     */
    private static String boxForeground(String label) {
        StringBuilder foreground = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            foreground.append(boxGlyph(label.charAt(i), BOX_FG_A, ' '));
        }
        return foreground.append(BOX_TERMINATOR).toString();
    }

    /**
     * Maps an uppercase letter onto one of the box alphabets. Anything that is
     * not A-Z renders as the alphabet's space glyph.
     */
    private static char boxGlyph(char c, char origin, char space) {
        if (c >= 'A' && c <= 'Z') return (char) (origin + (c - 'A'));
        return space;
    }

    /**
     * Encodes a horizontal cursor movement of {@code advance} pixels as a
     * {@code minecraft:space} font codepoint. Negative values move left.
     */
    private static String spacer(int advance) {
        return new String(Character.toChars(SPACE_BASE + advance));
    }

    /** Returns {@code true} if any node in the tree resolves to {@code target}. */
    private static boolean containsFont(Component root, FontDescription target) {
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
}
