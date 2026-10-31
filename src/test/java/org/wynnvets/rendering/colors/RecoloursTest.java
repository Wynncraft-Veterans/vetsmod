package org.wynnvets.rendering.colors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.colors.Recolours.Outline;

/** Tests for {@link Recolours}' factories, pixel by pixel on literal colours. */
class RecoloursTest {

    /** A ramp whose ends and middle are easy to read off. */
    private static final List<Integer> RAMP = List.of(0x100000, 0x00FF00, 0x0000F0);

    /** {@code pixels}, each after {@code recolour}. */
    private static int[] applied(IntUnaryOperator recolour, int... pixels) {
        return Arrays.stream(pixels).map(recolour).toArray();
    }

    // ----- multiply, tint, scale -----

    @Test
    void aTintMultipliesEachChannelRoundedAndKeepsAlpha() {
        // 128 * 160 / 255 = 80.3, 128 * 101 / 255 = 50.7, 128 * 64 / 255 = 32.1.
        assertArrayEquals(
                new int[] {0xFF503320, 0x80A06540, 0x00000000},
                applied(Recolours.tint(0xA06540), 0xFF808080, 0x80FFFFFF, 0x00000000));
    }

    @Test
    void aWhiteTintChangesNothing() {
        int[] pixels = {0xFF000000, 0xFF000001, 0xFF00000A, 0x80123456, 0x00FFFFFF};

        assertArrayEquals(pixels, applied(Recolours.tint(0xFFFFFF), pixels));
    }

    @Test
    void multiplyClampsEachChannelAt255() {
        assertArrayEquals(
                new int[] {0xFFFF4080, 0x40301030},
                applied(Recolours.multiply(3.0, 0.5, 1.0), 0xFF808080, 0x40102030));
    }

    @Test
    void scaleTurnsOneColourIntoAnotherAndClampsTheBrighter() {
        assertArrayEquals(
                new int[] {0xFFC0C0C0, 0x80C0C0C0, 0xFFFFFFFF},
                applied(Recolours.scale(0x804020, 0xC0C0C0), 0xFF804020, 0x80804020, 0xFFFF8040));
    }

    @Test
    void scaleLeavesAChannelThatIsZeroInTheFromColour() {
        assertArrayEquals(
                new int[] {0xFF105020}, applied(Recolours.scale(0x000000, 0x808080), 0xFF105020));
    }

    // ----- hueOnly -----

    @Test
    void hueOnlyTurnsOnlyTheFromColoursHueAndKeepsItsShade() {
        assertArrayEquals(
                new int[] {0xFFC06000, 0x80603000, 0xFF808080, 0xFF804020},
                applied(
                        Recolours.hueOnly(0x204080, 0xC06000),
                        0xFF204080,
                        0x80102040,
                        0xFF808080,
                        0xFF804020),
                "the blue pixels, at their own brightness; not the grey or the brown");
    }

    @Test
    void hueOnlyReachesAColourWithAZeroChannelAndClamps() {
        assertArrayEquals(
                new int[] {0xFF7540B3}, applied(Recolours.hueOnly(0x00F080, 0x6E3CA8), 0xFF00FF80));
    }

    @Test
    void hueOnlyRefusesAFromWithoutOneStrongestChannel() {
        for (int tied : new int[] {0x808080, 0x000000, 0x808000, 0x008080, 0x800080}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Recolours.hueOnly(tied, 0xC06000),
                    Integer.toHexString(tied));
        }
    }

    // ----- ramp and outline -----

    @Test
    void rampPlacesEachPixelAlongTheRampByItsLumaAndKeepsAlpha() {
        assertArrayEquals(
                new int[] {0xFF100000, 0x8000FE01, 0xFF0000F0, 0x00100000},
                applied(Recolours.ramp(RAMP), 0xFF000000, 0x80808080, 0xFFFFFFFF, 0x00000000),
                "the ends of the ramp, nearly its middle at 128 of 255, and alpha as it was");
    }

    @Test
    void rampRefusesFewerThanTwoColours() {
        assertThrows(IllegalArgumentException.class, () -> Recolours.ramp(List.of(0x808080)));
    }

    @Test
    void withOutlineTurnsAPixelDarkerThanTheCutoffGrey() {
        assertArrayEquals(
                new int[] {0xFF191919, 0xFF131313, 0xFF00FE01},
                applied(
                        Recolours.withOutline(Recolours.ramp(RAMP), Outline.greyBelow(32)),
                        0xFF191919,
                        0xFF201000,
                        0xFF808080),
                "the darks are the grey of their luma, and a brighter pixel still follows the ramp");
    }

    @Test
    void withOutlineKeepsAnOutlinePixelsAlpha() {
        assertArrayEquals(
                new int[] {0x40191919},
                applied(
                        Recolours.withOutline(Recolours.ramp(RAMP), Outline.greyBelow(32)),
                        0x40191919));
    }

    @Test
    void withOutlineAtZeroIsTheInnerRecolourAlone() {
        int[] pixels = {0xFF000000, 0x80808080, 0xFFFFFFFF, 0x00000000};

        assertArrayEquals(
                applied(Recolours.ramp(RAMP), pixels),
                applied(Recolours.withOutline(Recolours.ramp(RAMP), Outline.greyBelow(0)), pixels));
    }

    @Test
    void keepingANeutralOutlineLeavesItsDarkGreysAndDyesAColouredDark() {
        int main = 0xD8D8D8;
        IntUnaryOperator dye =
                Recolours.withOutline(
                        Recolours.scale(main, 0x6CB0F0),
                        Outline.keepNeutralBelow(Recolours.outlineCutoff(main)));

        assertArrayEquals(
                new int[] {0xFF353535, 0x40000000, 0xFF3A3530},
                applied(dye, 0xFF353535, 0x40000000, 0xFF3A3530),
                "dark greys, and a dark within the spread of grey, whatever their alpha");
        assertEquals(
                Recolours.scale(main, 0x6CB0F0).applyAsInt(0xFF3A2020),
                dye.applyAsInt(0xFF3A2020),
                "a dark too far from grey is art");
        assertEquals(
                Recolours.scale(main, 0x6CB0F0).applyAsInt(0xFF808080),
                dye.applyAsInt(0xFF808080),
                "a grey above the cutoff is art");
        assertEquals(0xFF6CB0F0, dye.applyAsInt(0xFFD8D8D8), "the main colour takes the dye");
    }

    @Test
    void theBodyOfDarkArtIsNotItsOutline() {
        int main = 0x3B393B;
        IntUnaryOperator dye =
                Recolours.withOutline(
                        Recolours.scale(main, 0xC96DC6),
                        Outline.keepNeutralBelow(Recolours.outlineCutoff(main)));

        assertEquals(0xFFC96DC6, dye.applyAsInt(0xFF3B393B), "the body");
        assertEquals(
                Recolours.scale(main, 0xC96DC6).applyAsInt(0xFF242424),
                dye.applyAsInt(0xFF242424),
                "a grey that would be outline on lighter art, above half this art's main colour");
        assertEquals(0xFF111111, dye.applyAsInt(0xFF111111), "the line under the body");
    }

    @Test
    void anOutlinesCutoffIsHalfTheMainColourUpTo64() {
        assertEquals(64, Recolours.outlineCutoff(0xFFFFFF));
        assertEquals(64, Recolours.outlineCutoff(0x808080));
        assertEquals(33, Recolours.outlineCutoff(0x424242));
        assertEquals(0, Recolours.outlineCutoff(0x000000));
    }

    @Test
    void greyBelowCountsAPixelWhateverItsSpread() {
        assertEquals(
                0xFF131313,
                Recolours.withOutline(Recolours.ramp(RAMP), Outline.greyBelow(32))
                        .applyAsInt(0xFF201000));
        assertEquals(
                Recolours.ramp(RAMP).applyAsInt(0xFF201000),
                Recolours.withOutline(Recolours.ramp(RAMP), Outline.keepNeutralBelow(32))
                        .applyAsInt(0xFF201000),
                "the same pixel, too far from grey to keep");
    }

    // ----- contrast -----

    @Test
    void contrastAroundAPivotMovesEachVisibleChannelTowardItAndKeepsAlpha() {
        // 50 + 0.5 * (0 - 50) = 25, 100 + 0.5 * (0 - 100) = 50, 0; 50 + 0.5 * (200 - 50) = 125,
        // 100, 0 + 0.5 * 60 = 30. The transparent pixel is left as it is.
        assertArrayEquals(
                new int[] {0xFF193200, 0x807D641E, 0x00FFFFFF},
                applied(
                        Recolours.contrast(0.5, new double[] {50, 100, 0}),
                        0xFF000000,
                        0x80C8643C,
                        0x00FFFFFF));

        // 3 * 100 = 300 stops at 255; 255 + 3 * (200 - 255) = 90; 100 + 3 * (50 - 100) = -50
        // stops at 0.
        assertArrayEquals(
                new int[] {0xFFFF5A00},
                applied(Recolours.contrast(3, new double[] {0, 255, 100}), 0xFF64C832),
                "clamped");
    }
}
