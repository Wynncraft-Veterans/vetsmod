package org.wynnvets.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LegacyLoreText}, the string-level rules split out of
 * {@link LegacyItemHandler} and {@link LegacyEnchantmentRenderer} so that they can run without
 * the Minecraft bootstrap those two classes need at class-init.
 */
class LegacyLoreTextTest {

    @Test
    void betaLevelLine_acceptsBothCapitalisationsOfMin() {
        assertTrue(LegacyLoreText.isBetaLevelLine("Lv. min: 26"));
        assertTrue(LegacyLoreText.isBetaLevelLine("Lv. Min: 26"), "Dental Floss's spelling");
        assertTrue(LegacyLoreText.isBetaLevelLine("Lv min: 5"), "the dot is optional");
    }

    /** Builds a boxed-header line the way Wynncraft does: fill run, back-kern, letters. */
    private static String boxedHeader(String label) {
        StringBuilder fill = new StringBuilder("\uE060");
        StringBuilder letters = new StringBuilder();
        for (char c : label.toCharArray()) {
            fill.appendCodePoint(0xCFFFF).append(c == ' ' ? '\uE05F' : (char) (0xE030 + c - 'A'));
            letters.append(c == ' ' ? ' ' : (char) (0xE000 + c - 'A'));
        }
        return fill + "\uE062" + new String(Character.toChars(0xCFFE9)) + letters;
    }

    @Test
    void decodeBoxLabel_readsTheForegroundLettersAndDropsTheRest() {
        assertEquals("POWDERSOCKETS", LegacyLoreText.decodeBoxLabel(boxedHeader("POWDER SOCKETS")));
        assertEquals("LEGACYRARE", LegacyLoreText.decodeBoxLabel(boxedHeader("LEGACY RARE")));
        assertEquals("", LegacyLoreText.decodeBoxLabel("This item has no available"));
        assertEquals("", LegacyLoreText.decodeBoxLabel(null));
        assertEquals("AZ", LegacyLoreText.decodeBoxLabel("\uE000x\uE019"), "A and Z are the ends");
        assertEquals("", LegacyLoreText.decodeBoxLabel("\uE01A"), "one past Z is not a letter");
    }

    @Test
    void powderSocketsHeader_isRecognisedAndNothingElseIs() {
        assertTrue(LegacyLoreText.isPowderSocketsHeader(boxedHeader("POWDER SOCKETS") + "0/0"));
        assertTrue(LegacyLoreText.isPowderSocketsHeader(boxedHeader("POWDER SOCKETS") + "0/3"));
        assertFalse(
                LegacyLoreText.isPowderSocketsHeader(boxedHeader("LEGACY ENCHANTMENTS") + "1/1"),
                "vetsmod's own header is not Wynncraft's");
        assertFalse(LegacyLoreText.isPowderSocketsHeader(boxedHeader("LEGACY RARE CHESTPLATE")));
        assertFalse(LegacyLoreText.isPowderSocketsHeader("This item has no available"));
        assertFalse(
                LegacyLoreText.isPowderSocketsHeader("Powder Sockets"), "plain text is not a box");
        assertFalse(LegacyLoreText.isPowderSocketsHeader(null));
    }

    /**
     * The {@code banner/ribbon} run of the old Llevigar Pinot's first lore line, as dumped:
     * caps U+E060 and U+E062, letters from U+E030, a U+CFFFF kern before each glyph and a
     * U+CFFD0 kern after.
     */
    private static final String CAPTURED_VINTAGE_RIBBON =
            "\uE060\uDAFF\uDFFF\uE045\uDAFF\uDFFF\uE038\uDAFF\uDFFF\uE03D\uDAFF\uDFFF\uE043"
                    + "\uDAFF\uDFFF\uE030\uDAFF\uDFFF\uE036\uDAFF\uDFFF\uE034\uDAFF\uDFFF\uE062"
                    + "\uDAFF\uDFD0";

    /** Builds a ribbon run the way Wynncraft draws one: cap, kerned letter glyphs, cap. */
    private static String ribbon(String label) {
        StringBuilder run = new StringBuilder("\uE060");
        for (char c : label.toCharArray()) {
            run.appendCodePoint(0xCFFFF).append((char) (0xE030 + c - 'A'));
        }
        return run.appendCodePoint(0xCFFFF).append("\uE062").toString();
    }

    @Test
    void decodeRibbonLabel_readsTheLetterGlyphsAndDropsTheRest() {
        assertEquals("VINTAGE", LegacyLoreText.decodeRibbonLabel(CAPTURED_VINTAGE_RIBBON));
        assertEquals("LEGACY", LegacyLoreText.decodeRibbonLabel(ribbon("LEGACY")));
        assertEquals("", LegacyLoreText.decodeRibbonLabel("Vintage"));
        assertEquals("", LegacyLoreText.decodeRibbonLabel(null));
        assertEquals(
                "AZ", LegacyLoreText.decodeRibbonLabel("\uE030x\uE049"), "A and Z are the ends");
        assertEquals(
                "", LegacyLoreText.decodeRibbonLabel("\uE015\uE008"), "the black overlay letters");
    }

    @Test
    void vintageRibbon_isRecognisedAndNothingElseIs() {
        assertTrue(LegacyLoreText.isVintageRibbon(CAPTURED_VINTAGE_RIBBON));
        assertTrue(LegacyLoreText.isVintageRibbon(ribbon("VINTAGE")));
        assertFalse(LegacyLoreText.isVintageRibbon(ribbon("VINTAGES")), "the whole word only");
        assertFalse(LegacyLoreText.isVintageRibbon(ribbon("LEGACY")));
        assertFalse(LegacyLoreText.isVintageRibbon("VINTAGE"), "plain text is not a ribbon");
        assertFalse(LegacyLoreText.isVintageRibbon(""));
        assertFalse(LegacyLoreText.isVintageRibbon(null));
    }

    @Test
    void betaLevelLine_stillRequiresTheAnchorAndANumber() {
        assertFalse(LegacyLoreText.isBetaLevelLine("Combat Lv. Min: 65"), "modern line");
        assertFalse(LegacyLoreText.isBetaLevelLine("Lv. Min: high"));
        assertFalse(LegacyLoreText.isBetaLevelLine("Lv. Min: 26 extra"));
        assertFalse(LegacyLoreText.isBetaLevelLine(null));
    }
}
