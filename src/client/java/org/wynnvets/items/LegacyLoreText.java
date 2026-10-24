package org.wynnvets.items;

import java.util.regex.Pattern;

/**
 * String-level rules the legacy-item code applies to tooltip and lore text, kept apart from
 * {@link LegacyItemHandler} and {@link LegacyEnchantmentRenderer} because both of those load
 * {@code ItemStack} in their static initialisers, which the unit-test runtime cannot do.
 * Nothing here touches Minecraft, so {@code LegacyLoreTextTest} drives it directly.
 */
final class LegacyLoreText {

    /**
     * The beta-era level line. Case-insensitive because the beta items disagree on the
     * capital: most read {@code Lv. min: 26}, Dental Floss reads {@code Lv. Min: 26}.
     * Anchored at {@code Lv}, so the modern {@code Combat Lv. Min: 65} never matches.
     */
    private static final Pattern LV_MIN_PATTERN =
            Pattern.compile("^Lv\\.? min: \\d+$", Pattern.CASE_INSENSITIVE);

    /** First foreground glyph of a {@code banner/box} label: U+E000 is 'A', U+E019 is 'Z'. */
    private static final int BOX_FOREGROUND_A = 0xE000;

    private static final int BOX_FOREGROUND_Z = 0xE019;

    /** What Wynncraft's POWDER SOCKETS header decodes to once its spaces are dropped. */
    private static final String POWDER_SOCKETS_LABEL = "POWDERSOCKETS";

    /** Letter glyph of a {@code banner/ribbon} label: U+E030 is 'A', U+E049 is 'Z'. */
    private static final int RIBBON_A = 0xE030;

    private static final int RIBBON_Z = 0xE049;

    /** What the ribbon Wynncraft puts at the top of a legacy item's lore spells. */
    private static final String VINTAGE_LABEL = "VINTAGE";

    private LegacyLoreText() {}

    /**
     * Reads the text a boxed {@code banner/box} header spells out. The label's letters are
     * foreground glyphs at {@code U+E000 + (c - 'A')}; the box fill (U+E030 and up), the
     * space-font kerns (U+D0000 and up) and everything else are dropped, and so are the
     * label's spaces. A line that is not a boxed header decodes to whatever stray glyphs it
     * holds, so callers check the font as well.
     */
    static String decodeBoxLabel(String lineText) {
        if (lineText == null) return "";
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < lineText.length(); i++) {
            char c = lineText.charAt(i);
            if (c >= BOX_FOREGROUND_A && c <= BOX_FOREGROUND_Z) {
                label.append((char) ('A' + (c - BOX_FOREGROUND_A)));
            }
        }
        return label.toString();
    }

    /**
     * Returns {@code true} if the line's boxed label reads POWDER SOCKETS: the header of a
     * new-format tooltip's powder page, which an item with no sockets at all (0/0) shows
     * without any socket sprite under it.
     */
    static boolean isPowderSocketsHeader(String lineText) {
        return decodeBoxLabel(lineText).contains(POWDER_SOCKETS_LABEL);
    }

    /**
     * Reads the word a {@code banner/ribbon} run spells out. The ribbon's body is one glyph per
     * letter at {@code U+E030 + (c - 'A')}, between the end caps U+E060 and U+E062, with a
     * space-font kern (U+CFFFF) before each; the caps, the kerns and everything else are
     * dropped. The same code points are other glyphs in other fonts (the {@code banner/box}
     * fill run uses them too), so callers pass only the text drawn in {@code banner/ribbon}.
     */
    static String decodeRibbonLabel(String ribbonText) {
        if (ribbonText == null) return "";
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < ribbonText.length(); i++) {
            char c = ribbonText.charAt(i);
            if (c >= RIBBON_A && c <= RIBBON_Z) {
                label.append((char) ('A' + (c - RIBBON_A)));
            }
        }
        return label.toString();
    }

    /**
     * Returns {@code true} if the {@code banner/ribbon} text reads VINTAGE: the ribbon Wynncraft
     * shows at the top of a legacy item's lore (seen on the Llevigar Pinot).
     */
    static boolean isVintageRibbon(String ribbonText) {
        return VINTAGE_LABEL.equals(decodeRibbonLabel(ribbonText));
    }

    /** Returns {@code true} if the formatting-stripped line is a beta-era "Lv. min: N" line. */
    static boolean isBetaLevelLine(String plain) {
        return plain != null && LV_MIN_PATTERN.matcher(plain).matches();
    }
}
