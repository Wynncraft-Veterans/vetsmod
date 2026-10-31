package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.wynnvets.rendering.image.TestImages.filled;
import static org.wynnvets.rendering.image.TestImages.numbered;

import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.colors.Recolours;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.retro.manifest.TintFactor;

/**
 * Tests for {@link TintFactors}: the factors a {@code tints} row multiplies by, the contrast it
 * changes after them, and a like's contrast around the named texture's tinted mean.
 */
class TintFactorsTest {

    /** Means (100, 50, 200), with a transparent pixel the means ignore. */
    private static final Pixels SOURCE =
            new Pixels(3, 1, new int[] {0xFF5A3CC8, 0xFF6E28C8, 0x00FFFFFF});

    /** Means (50, 100, 50). */
    private static final Pixels REFERENCE = filled(2, 2, 0xFF326432);

    /** Means (25, 25, 100). */
    private static final Pixels LIVE = filled(1, 1, 0xFF191964);

    /** Factors that leave the channels alone, for a contrast by itself. */
    private static final double[] UNCHANGED = {1, 1, 1};

    // ----- Factors -----

    @Test
    void aColoursFactorIsEachChannelOver255() {
        assertArrayEquals(
                new double[] {1, 128 / 255.0, 0},
                TintFactors.measure(new TintFactor.Colour(0xFF8000), 1, SOURCE, null, null),
                1e-9);
    }

    @Test
    void matchDividesTheMatchedMeanByTheSources() {
        // The source takes the matched texture's colour: (50, 100, 50) / (100, 50, 200).
        assertArrayEquals(
                new double[] {0.5, 2, 0.25},
                TintFactors.measure(
                        new TintFactor.Match("minecraft:block/x"), 1, SOURCE, REFERENCE, LIVE),
                1e-9);
    }

    @Test
    void relativeToDividesWynncraftsMeanByItsReferences() {
        // The source changes as Wynncraft's texture changed: (25, 25, 100) / (50, 100, 50); the
        // source's own mean plays no part.
        assertArrayEquals(
                new double[] {0.5, 0.25, 2},
                TintFactors.measure(
                        new TintFactor.RelativeTo("minecraft:block/x"), 1, SOURCE, REFERENCE, LIVE),
                1e-9);
    }

    @Test
    void autoDividesWynncraftsMeanByTheSources() {
        // (25, 25, 100) / (100, 50, 200); the reference plays no part.
        assertArrayEquals(
                new double[] {0.25, 0.5, 0.5},
                TintFactors.measure(new TintFactor.Auto(), 1, SOURCE, REFERENCE, LIVE),
                1e-9);
    }

    @Test
    void strengthMovesEachFactorTowardOne() {
        // factor = 1 + strength * (ratio - 1), from match's (0.5, 2, 0.25).
        TintFactor match = new TintFactor.Match("minecraft:block/x");

        assertArrayEquals(
                new double[] {1, 1, 1},
                TintFactors.measure(match, 0, SOURCE, REFERENCE, null),
                1e-9,
                "none of it");
        assertArrayEquals(
                new double[] {0.65, 1.7, 0.475},
                TintFactors.measure(match, 0.7, SOURCE, REFERENCE, null),
                1e-9);
        assertArrayEquals(
                new double[] {1, 0.5 + 64 / 255.0, 1},
                TintFactors.measure(new TintFactor.Colour(0xFF80FF), 0.5, SOURCE, null, null),
                1e-9,
                "a colour too");
    }

    @Test
    void aFactorWithNothingToMeasureIsNull() {
        Pixels blank = Pixels.blank(2, 2);
        Pixels noRed = filled(1, 1, 0xFF0080FF);

        assertNull(TintFactors.measure(new TintFactor.Match("x:y"), 1, SOURCE, null, null));
        assertNull(TintFactors.measure(new TintFactor.Match("x:y"), 1, SOURCE, blank, null));
        assertNull(TintFactors.measure(new TintFactor.Auto(), 1, SOURCE, null, null), "no live");
        assertNull(
                TintFactors.measure(new TintFactor.RelativeTo("x:y"), 1, SOURCE, null, LIVE),
                "no reference");
        assertNull(
                TintFactors.measure(new TintFactor.Auto(), 1, noRed, null, LIVE),
                "a channel of 0 to divide by");
    }

    @Test
    void aTintMultipliesBySuchFactorsClampedAndKeepsAlpha() {
        // Match's factors, (0.5, 2, 0.25), halve red, double green and quarter blue; a channel
        // stops at 255.
        Pixels image = new Pixels(3, 1, new int[] {0xFF5A3CC8, 0x806E28C8, 0x00FFFFFF});
        double[] factors =
                TintFactors.measure(
                        new TintFactor.Match("minecraft:block/x"), 1, SOURCE, REFERENCE, null);

        TintFactors.tint(image, factors, 1);

        // 90 * 0.5 = 45, 60 * 2 = 120, 200 * 0.25 = 50; 255 * 0.5 = 127.5 rounds to 128, and
        // 255 * 2 stops at 255. Each pixel keeps its alpha.
        assertArrayEquals(new int[] {0xFF2D7832, 0x80375032, 0x0080FF40}, image.argb());
    }

    @Test
    void anAutoTintOfAStripOfFramesTintsEveryFrameAlikeAndKeepsTheStrip() {
        // An animated texture is a strip of frames, one under another. The mean runs over every
        // frame, and every frame takes the same factors, so the strip and its frame count, which
        // its .mcmeta counts on, stay.
        Pixels strip = new Pixels(1, 3, new int[] {0xFF643214, 0xFF6E3C1E, 0xFF785028});
        Pixels live = new Pixels(1, 3, new int[] {0xFF32190A, 0xFF371E0F, 0xFF3C2814});
        double[] factors = TintFactors.measure(new TintFactor.Auto(), 1, strip, null, live);

        // Means (110, 63.3, 30) and (55, 31.7, 15): half of each.
        assertArrayEquals(new double[] {0.5, 0.5, 0.5}, factors, 1e-9);
        TintFactors.tint(strip, factors, 1);
        assertEquals(1, strip.width());
        assertEquals(3, strip.height());
        assertArrayEquals(live.argb(), strip.argb());
    }

    @Test
    void aRowWithoutATintMultipliesByOne() {
        // A contrast-only row: the source keeps its colour, and needs nothing measured.
        assertArrayEquals(
                new double[] {1, 1, 1},
                TintFactors.measure(new TintFactor.Unchanged(), 1, SOURCE, null, null),
                1e-9);
        assertArrayEquals(
                new double[] {1, 1, 1},
                TintFactors.measure(new TintFactor.Unchanged(), 0.5, SOURCE, null, null),
                1e-9,
                "whatever the strength");
    }

    @Test
    void aLikeHasNoFactorsOfItsOwn() {
        // A like takes the factors of the row it names; the generator never asks for its own.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TintFactors.measure(
                                new TintFactor.Like("minecraft:block/x"),
                                1,
                                SOURCE,
                                REFERENCE,
                                LIVE));
    }

    // ----- Contrast around the image's own mean -----

    @Test
    void contrastMovesEachVisibleChannelTowardTheMeanAndKeepsAlpha() {
        // Means (100, 50, 30) over the two visible pixels; the transparent one counts for none
        // and is left as it is. Half the contrast halves each channel's distance to its mean.
        Pixels image = new Pixels(3, 1, new int[] {0xFF000000, 0x80C8643C, 0x00FFFFFF});

        TintFactors.tint(image, UNCHANGED, 0.5);

        // 100 + 0.5 * (0 - 100) = 50, 25, 15; 100 + 0.5 * (200 - 100) = 150, 75, 45.
        assertArrayEquals(new int[] {0xFF32190F, 0x80964B2D, 0x00FFFFFF}, image.argb());
        assertArrayEquals(new double[] {100, 50, 30}, image.mean(), 1e-9, "the mean");
    }

    @Test
    void contrastOneKeepsTheImageZeroFlattensItAndAboveOneClamps() {
        Pixels kept = numbered(3, 2);
        int[] before = kept.argb().clone();
        TintFactors.tint(kept, UNCHANGED, 1);
        assertArrayEquals(before, kept.argb(), "1 changes nothing");

        Pixels flat = new Pixels(2, 1, new int[] {0xFF000000, 0xFFC8643C});
        TintFactors.tint(flat, UNCHANGED, 0);
        assertArrayEquals(new int[] {0xFF64321E, 0xFF64321E}, flat.argb(), "0: the mean");

        Pixels busy = new Pixels(2, 1, new int[] {0xFF000000, 0xFFC8643C});
        TintFactors.tint(busy, UNCHANGED, 3);
        // 100 + 3 * (0 - 100) = -200 stops at 0; 100 + 3 * 100 = 400 stops at 255; 50 + 150 =
        // 200 and 30 + 90 = 120 stay.
        assertArrayEquals(new int[] {0xFF000000, 0xFFFFC878}, busy.argb(), "3: clamped");

        Pixels blank = Pixels.blank(2, 2);
        TintFactors.tint(blank, UNCHANGED, 0.5);
        assertArrayEquals(new int[4], blank.argb(), "nothing visible: nothing to move");
    }

    @Test
    void aTintChangesTheContrastAfterTheColourAroundTheTintedMean() {
        // Red x1.5 first: 100 becomes 150 and 200 stops at 255, a mean of 202.5. Then half the
        // contrast around that mean: 176.25 and 228.75, rounded. Contrast first would give 188
        // and 255 instead. Green and blue keep their factor of 1: 125 and 175 around 150.
        Pixels image = new Pixels(2, 1, new int[] {0xFF646464, 0xFFC8C8C8});

        TintFactors.tint(image, new double[] {1.5, 1, 1}, 0.5);

        assertArrayEquals(new int[] {0xFFB07D7D, 0xFFE5AFAF}, image.argb());
    }

    @Test
    void aTintAtFullContrastIsTheMultiplyAlone() {
        Pixels tinted = new Pixels(3, 1, new int[] {0xFF5A3CC8, 0x806E28C8, 0x00FFFFFF});
        Pixels multiplied = tinted.copy();

        TintFactors.tint(tinted, new double[] {0.5, 2, 0.25}, 1);
        multiplied.apply(Recolours.multiply(0.5, 2, 0.25));

        assertArrayEquals(multiplied.argb(), tinted.argb());
    }

    // ----- Like -----

    @Test
    void contrastAroundTheImagesOwnMeanMatchesThePlainContrast() {
        Pixels own = numbered(3, 2);
        Pixels around = own.copy();

        TintFactors.tint(own, UNCHANGED, 0.45);
        TintFactors.tint(around, UNCHANGED, 0.45, numbered(3, 2).mean());

        assertArrayEquals(own.argb(), around.argb(), "the plain contrast pivots on the mean");
    }

    @Test
    void theTintedMeanIsTheMeanAfterTheClampedMultiplyAndLeavesTheImage() {
        Pixels image = new Pixels(3, 1, new int[] {0xFF5A3CC8, 0x806E28C8, 0x00FFFFFF});
        int[] before = image.argb().clone();

        // Red 90 and 110 halve to 45 and 55; green 60 and 40 times 5 are 300, which stops at
        // 255, and 200; blue 200 quarters to 50. The transparent pixel counts for none.
        assertArrayEquals(
                new double[] {50, 227.5, 50},
                TintFactors.tintedMean(image, new double[] {0.5, 5, 0.25}),
                1e-9);
        assertArrayEquals(before, image.argb(), "the image is left as it was");
        assertNull(TintFactors.tintedMean(Pixels.blank(2, 2), UNCHANGED), "nothing visible");
    }

    @Test
    void aLikeTintChangesThePixelsItSharesAsTheNamedTextureAndTheRestAroundItsMean() {
        // The named texture (a furnace's side): red x1.5, then half its contrast around its own
        // tinted mean, (202.5, 150, 150). The like (a dispenser's front) shares its two pixels
        // and adds a black mouth.
        Pixels named = new Pixels(2, 1, new int[] {0xFF646464, 0xFFC8C8C8});
        Pixels front = new Pixels(3, 1, new int[] {0xFF646464, 0xFFC8C8C8, 0xFF000000});
        double[] factors = {1.5, 1, 1};
        double[] pivot = TintFactors.tintedMean(named, factors);

        TintFactors.tint(named, factors, 0.5);
        TintFactors.tint(front, factors, 0.5, pivot);

        assertArrayEquals(new double[] {202.5, 150, 150}, pivot, 1e-9);
        assertArrayEquals(new int[] {0xFFB07D7D, 0xFFE5AFAF}, named.argb());
        // The shared pixels come out as the named texture's; the mouth moves halfway to the
        // pivot: 101.25 and 75, rounded.
        assertArrayEquals(new int[] {0xFFB07D7D, 0xFFE5AFAF, 0xFF654B4B}, front.argb());

        Pixels own = new Pixels(3, 1, new int[] {0xFF646464, 0xFFC8C8C8, 0xFF000000});
        TintFactors.tint(own, factors, 0.5);
        // Around its own tinted mean, (135, 100, 100), which the mouth pulls down, the shared
        // pixels come out otherwise: red 142.5 and 195 (not 176 and 229), green and blue 100
        // and 150 (not 125 and 175), rounded.
        assertArrayEquals(
                new int[] {0xFF8F6464, 0xFFC39696, 0xFF443232},
                own.argb(),
                "around its own mean, which the like avoids");
    }
}
