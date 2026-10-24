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

    @Test
    void betaLevelLine_stillRequiresTheAnchorAndANumber() {
        assertFalse(LegacyLoreText.isBetaLevelLine("Combat Lv. Min: 65"), "modern line");
        assertFalse(LegacyLoreText.isBetaLevelLine("Lv. Min: high"));
        assertFalse(LegacyLoreText.isBetaLevelLine("Lv. Min: 26 extra"));
        assertFalse(LegacyLoreText.isBetaLevelLine(null));
    }
}
