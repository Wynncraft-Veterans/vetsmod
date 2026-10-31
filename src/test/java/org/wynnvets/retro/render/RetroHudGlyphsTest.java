package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for where {@link RetroHudGlyphs} finds the level and the health and mana numbers in the
 * action bar's text. {@code indicesIn} reads a Wynntils {@code StyledText}, and is not tested.
 */
class RetroHudGlyphsTest {

    /** Level 120 in spacers of the range Wynntils' matcher asks for; no real bar is pinned here. */
    private static final String LEVEL_120 =
            glyphs(0xCFFF0, 0xE001, 0xCFFFE, 0xE002, 0xCFFFE, 0xE000, 0xCFFF0);

    /** Mount energy near its fullest: the code point of the digit 0, in its own spacers. */
    private static final String HIGH_ENERGY = glyphs(0xD0008, 0xE000, 0xCFFE7);

    /** The code point of the digit 3 in another font, in spacers just like the level's. */
    private static final String OTHER_FONT_RUN = glyphs(0xCFFF0, 0xE003, 0xCFFF0);

    private static final IntPredicate ALL_IN_FONT = index -> true;

    private static String glyphs(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    /** Health 12/34: digits a negative spacer apart, the slash two wide, as Wynncraft sends it. */
    private static final String HEALTH_12_OF_34 =
            glyphs(
                    0xCFFC0, 0xE011, 0xCFFFF, 0xE012, 0xD0002, 0xE01F, 0xD0002, 0xE013, 0xCFFFF,
                    0xE014, 0xD0020);

    /** Mana 5/6, in the opposite spacers to health's. */
    private static final String MANA_5_OF_6 =
            glyphs(0xD0020, 0xE015, 0xD0002, 0xE01F, 0xD0002, 0xE016, 0xCFFC0);

    /** Mount energy that Wynntils' mana matcher takes for the number and gives up on. */
    private static final String MIDDLE_ENERGY = glyphs(0xD0008, 0xE015, 0xCFFE7);

    @Test
    void eachNumberIsFoundInItsOwnSpacers() {
        String bar = HEALTH_12_OF_34 + "x" + MANA_5_OF_6;
        assertEquals(
                HEALTH_12_OF_34,
                RetroHudGlyphs.findNumber(bar, ALL_IN_FONT, true).orElseThrow().group());
        MatchResult mana = RetroHudGlyphs.findNumber(bar, ALL_IN_FONT, false).orElseThrow();
        assertEquals(MANA_5_OF_6, mana.group());
        assertEquals(HEALTH_12_OF_34.length() + 1, mana.start());
        assertEquals(bar.length(), mana.end());
    }

    @Test
    void mountEnergyBeforeTheManaNumberIsPassedOver() {
        String bar = MIDDLE_ENERGY + MANA_5_OF_6;
        assertEquals(
                MANA_5_OF_6,
                RetroHudGlyphs.findNumber(bar, ALL_IN_FONT, false).orElseThrow().group());
    }

    @Test
    void aDecimalNumberIsFoundThoughWynntilsCannotParseIt() {
        String decimal = glyphs(0xCFFC0, 0xE011, 0xE01E, 0xE012, 0xE01A, 0xE01F, 0xE013, 0xD0020);
        assertEquals(
                decimal,
                RetroHudGlyphs.findNumber(decimal, ALL_IN_FONT, true).orElseThrow().group());
    }

    @Test
    void nothingElseIsTakenForANumber() {
        assertTrue(
                RetroHudGlyphs.findNumber(MIDDLE_ENERGY, ALL_IN_FONT, false).isEmpty(), "no slash");
        assertTrue(
                RetroHudGlyphs.findNumber(MANA_5_OF_6, ALL_IN_FONT, true).isEmpty(),
                "mana's signs");
        assertTrue(RetroHudGlyphs.findNumber(HEALTH_12_OF_34, ALL_IN_FONT, false).isEmpty());
        assertTrue(
                RetroHudGlyphs.findNumber(HEALTH_12_OF_34, index -> false, true).isEmpty(),
                "other font");
        assertTrue(
                RetroHudGlyphs.findNumber(
                                glyphs('a', 0xE011, 0xE01F, 0xE012, 'b'), ALL_IN_FONT, true)
                        .isEmpty(),
                "no spacers");
        assertTrue(RetroHudGlyphs.findNumber("", ALL_IN_FONT, true).isEmpty());
    }

    @Test
    void aNumbersGlyphsLoseOnlyTheOuterSpacers() {
        assertEquals(
                glyphs(0xE015, 0xD0002, 0xE01F, 0xD0002, 0xE016),
                RetroHudGlyphs.numberGlyphs(MANA_5_OF_6));
    }

    @Test
    void theLevelIsFoundInItsSpacers() {
        MatchResult level =
                RetroHudGlyphs.findLevel("a" + LEVEL_120 + "b", ALL_IN_FONT).orElseThrow();
        assertEquals(LEVEL_120, level.group());
        assertEquals(1, level.start());
        assertEquals(1 + LEVEL_120.length(), level.end());
    }

    @Test
    void highMountEnergyBeforeTheLevelIsPassedOver() {
        MatchResult level =
                RetroHudGlyphs.findLevel(HIGH_ENERGY + LEVEL_120, ALL_IN_FONT).orElseThrow();
        assertEquals(LEVEL_120, level.group());
        assertEquals(HIGH_ENERGY.length(), level.start());
    }

    @Test
    void aRunInAnotherFontBeforeTheLevelIsPassedOver() {
        int levelStart = OTHER_FONT_RUN.length();
        MatchResult level =
                RetroHudGlyphs.findLevel(OTHER_FONT_RUN + LEVEL_120, index -> index >= levelStart)
                        .orElseThrow();
        assertEquals(LEVEL_120, level.group());
        assertEquals(levelStart, level.start());
    }

    @Test
    void theSpacerClosingARunInAnotherFontMayOpenTheLevel() {
        String text = glyphs(0xCFFF0, 0xE003, 0xCFFF0, 0xE001, 0xCFFF0);
        int firstDigitsEnd = glyphs(0xCFFF0, 0xE003).length();
        MatchResult level =
                RetroHudGlyphs.findLevel(text, index -> index >= firstDigitsEnd).orElseThrow();
        assertEquals(glyphs(0xCFFF0, 0xE001, 0xCFFF0), level.group());
    }

    @Test
    void nothingElseIsTakenForTheLevel() {
        assertTrue(RetroHudGlyphs.findLevel(HIGH_ENERGY, ALL_IN_FONT).isEmpty(), "mount energy");
        assertTrue(
                RetroHudGlyphs.findLevel(glyphs(0xE001, 0xE002), ALL_IN_FONT).isEmpty(),
                "digits with no spacers");
        assertTrue(RetroHudGlyphs.findLevel("", ALL_IN_FONT).isEmpty());
        assertTrue(
                RetroHudGlyphs.findLevel(LEVEL_120, index -> false).isEmpty(), "in no level font");
        assertTrue(
                RetroHudGlyphs.findLevel(glyphs(0xCFFF0, 0xE001, 0xCFFFE, 0xE001), ALL_IN_FONT)
                        .isEmpty(),
                "a separator is not the closing spacer, which would leave a digit behind");
    }
}
