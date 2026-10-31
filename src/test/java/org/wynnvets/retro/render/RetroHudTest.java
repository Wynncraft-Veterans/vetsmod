package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.render.RetroHud.Fill;

/**
 * Tests for {@link RetroHud}'s logic on plain values: how much of a row of hearts or shanks a value
 * fills, how full each icon is, how wide the experience bar's fill is, and where the level is in
 * the action bar's text. Drawing needs a running client and Wynntils' models, and is not tested;
 * the class's static fields are sprite ids, a pattern and a flag, so loading it builds no Wynntils
 * object.
 */
class RetroHudTest {

    @Test
    void onlyABarFromSinceTheLastWorldCounts() {
        Object old = new Object();
        Object fresh = new Object();

        assertFalse(RetroHud.isCurrentBar(null, null), "no bar");
        assertFalse(RetroHud.isCurrentBar(null, old), "the old one removed");
        assertFalse(RetroHud.isCurrentBar(old, old), "the one a disconnect left behind");
        assertTrue(RetroHud.isCurrentBar(fresh, null), "none left behind");
        assertTrue(RetroHud.isCurrentBar(fresh, old), "a new one after it");
    }

    /** Level 120 in spacers of the range Wynntils' matcher asks for; no real bar is pinned here. */
    private static final String LEVEL_120 =
            glyphs(0xCFFF0, 0xE001, 0xCFFFE, 0xE002, 0xCFFFE, 0xE000, 0xCFFF0);

    /** Mount energy near its fullest: the code point of the digit 0, in its own spacers. */
    private static final String HIGH_ENERGY = glyphs(0xD0008, 0xE000, 0xCFFE7);

    /** The code point of the digit 3 in another font, in spacers just like the level's. */
    private static final String OTHER_FONT_RUN = glyphs(0xCFFF0, 0xE003, 0xCFFF0);

    private static final IntPredicate ALL_IN_LEVEL_FONT = index -> true;

    private static String glyphs(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void theLevelIsFoundInItsSpacers() {
        MatchResult level =
                RetroHud.findLevel("a" + LEVEL_120 + "b", ALL_IN_LEVEL_FONT).orElseThrow();
        assertEquals(LEVEL_120, level.group());
        assertEquals(1, level.start());
        assertEquals(1 + LEVEL_120.length(), level.end());
    }

    @Test
    void highMountEnergyBeforeTheLevelIsPassedOver() {
        MatchResult level =
                RetroHud.findLevel(HIGH_ENERGY + LEVEL_120, ALL_IN_LEVEL_FONT).orElseThrow();
        assertEquals(LEVEL_120, level.group());
        assertEquals(HIGH_ENERGY.length(), level.start());
    }

    @Test
    void aRunInAnotherFontBeforeTheLevelIsPassedOver() {
        int levelStart = OTHER_FONT_RUN.length();
        MatchResult level =
                RetroHud.findLevel(OTHER_FONT_RUN + LEVEL_120, index -> index >= levelStart)
                        .orElseThrow();
        assertEquals(LEVEL_120, level.group());
        assertEquals(levelStart, level.start());
    }

    @Test
    void theSpacerClosingARunInAnotherFontMayOpenTheLevel() {
        String text = glyphs(0xCFFF0, 0xE003, 0xCFFF0, 0xE001, 0xCFFF0);
        int firstDigitsEnd = glyphs(0xCFFF0, 0xE003).length();
        MatchResult level =
                RetroHud.findLevel(text, index -> index >= firstDigitsEnd).orElseThrow();
        assertEquals(glyphs(0xCFFF0, 0xE001, 0xCFFF0), level.group());
    }

    @Test
    void nothingElseIsTakenForTheLevel() {
        assertTrue(RetroHud.findLevel(HIGH_ENERGY, ALL_IN_LEVEL_FONT).isEmpty(), "mount energy");
        assertTrue(
                RetroHud.findLevel(glyphs(0xE001, 0xE002), ALL_IN_LEVEL_FONT).isEmpty(),
                "digits with no spacers");
        assertTrue(RetroHud.findLevel("", ALL_IN_LEVEL_FONT).isEmpty());
        assertTrue(RetroHud.findLevel(LEVEL_120, index -> false).isEmpty(), "in no level font");
        assertTrue(
                RetroHud.findLevel(glyphs(0xCFFF0, 0xE001, 0xCFFFE, 0xE001), ALL_IN_LEVEL_FONT)
                        .isEmpty(),
                "a separator is not the closing spacer, which would leave a digit behind");
    }

    @Test
    void aFullValueFillsTheRowAndNothingLeftFillsNone() {
        assertEquals(RetroHud.HALVES, RetroHud.halves(100, 100));
        assertEquals(RetroHud.HALVES, RetroHud.halves(150, 100), "above its maximum");
        assertEquals(0, RetroHud.halves(0, 100));
        assertEquals(0, RetroHud.halves(-5, 100));
        assertEquals(0, RetroHud.halves(5, 0), "no maximum, nothing to draw");
    }

    @Test
    void anyLossShowsAndAnythingLeftShowsAHalf() {
        assertEquals(19, RetroHud.halves(99, 100), "a point lost takes a half");
        assertEquals(19, RetroHud.halves(999_999, 1_000_000));
        assertEquals(1, RetroHud.halves(1, 1_000), "never empty while anything is left");
        assertEquals(1, RetroHud.halves(9, 100));
    }

    @Test
    void inBetweenARowFillsTheHalvesTheValueCoversInFull() {
        assertEquals(10, RetroHud.halves(50, 100));
        assertEquals(10, RetroHud.halves(54, 100));
        assertEquals(11, RetroHud.halves(55, 100));
        assertEquals(19, RetroHud.halves(2_000_000_000, 2_100_000_000), "no overflow");
    }

    @Test
    void overhealthFillsARowPerMaximumAndAnyOfItShows() {
        assertEquals(0, RetroHud.overhealthHalves(200, 200), "at its maximum there is none");
        assertEquals(0, RetroHud.overhealthHalves(100, 200));
        assertEquals(0, RetroHud.overhealthHalves(5, 0), "no maximum");
        assertEquals(10, RetroHud.overhealthHalves(300, 200));
        assertEquals(20, RetroHud.overhealthHalves(400, 200));
        assertEquals(30, RetroHud.overhealthHalves(500, 200));
        assertEquals(60, RetroHud.overhealthHalves(800, 200));
        assertEquals(60, RetroHud.overhealthHalves(5_000, 200), "every colour's row, no more");
        assertEquals(1, RetroHud.overhealthHalves(17_959, 17_815), "any of it takes a half");
        assertEquals(60, RetroHud.overhealthHalves(Integer.MAX_VALUE, 1), "no overflow");
    }

    @Test
    void eachOverhealthColourFillsOverTheOneBeforeIt() {
        assertEquals(10, RetroHud.overhealthColourHalves(0, 10), "300/200: half orange");
        assertEquals(0, RetroHud.overhealthColourHalves(1, 10));
        assertEquals(20, RetroHud.overhealthColourHalves(0, 30), "500/200: orange under");
        assertEquals(10, RetroHud.overhealthColourHalves(1, 30), "half yellow over it");
        assertEquals(0, RetroHud.overhealthColourHalves(2, 30));
        assertEquals(20, RetroHud.overhealthColourHalves(1, 60), "800/200: yellow under");
        assertEquals(20, RetroHud.overhealthColourHalves(2, 60), "all green over it");
    }

    @Test
    void iconsFillFromTheFirstAsVanillasHeartsDo() {
        for (int icon = 0; icon < 10; icon++) {
            assertEquals(Fill.FULL, RetroHud.fill(icon, RetroHud.HALVES), "icon " + icon);
            assertEquals(Fill.EMPTY, RetroHud.fill(icon, 0), "icon " + icon);
        }
        assertEquals(Fill.FULL, RetroHud.fill(2, 7));
        assertEquals(Fill.HALF, RetroHud.fill(3, 7));
        assertEquals(Fill.EMPTY, RetroHud.fill(4, 7));
        assertEquals(Fill.HALF, RetroHud.fill(0, 1));
        assertEquals(Fill.EMPTY, RetroHud.fill(1, 2));
    }

    @Test
    void theExperienceFillIsVanillasProgressTimes183WithinTheBar() {
        assertEquals(91, RetroHud.progressWidth(1, 2));
        assertEquals(181, RetroHud.progressWidth(99, 100));
        assertEquals(1, RetroHud.progressWidth(1, 183));
        assertEquals(0, RetroHud.progressWidth(1, 184), "cut to whole pixels");
        assertEquals(182, RetroHud.progressWidth(1, 1), "never wider than the bar");
        assertEquals(182, RetroHud.progressWidth(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
    }

    @Test
    void noProgressOrNoLevelDrawsNoFill() {
        assertEquals(0, RetroHud.progressWidth(0, 100));
        assertEquals(0, RetroHud.progressWidth(-1, 100));
        assertEquals(0, RetroHud.progressWidth(5, 0));
    }
}
