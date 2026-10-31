package org.wynnvets.debug.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.wynnvets.rendering.image.TestImages.filled;
import static org.wynnvets.rendering.image.TestImages.numbered;

import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Pixels;

/** Tests for {@link TextureDivergence}'s brightness correlation. */
class TextureDivergenceTest {

    /** A 16x16 opaque checkerboard of {@code dark} and {@code light}, dark at the top left. */
    private static Pixels checker(int dark, int light) {
        Pixels image = Pixels.blank(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) image.set(x, y, (x + y) % 2 == 0 ? dark : light);
        }
        return image;
    }

    @Test
    void theSamePictureCorrelatesFullyInOtherColoursOrAtTwiceTheSize() {
        Pixels picture = numbered(16, 16);
        Pixels doubled = Pixels.blank(32, 32);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) doubled.set(x, y, picture.get(x / 2, y / 2));
        }

        assertEquals(1, TextureDivergence.brightnessCorrelation(picture, picture), 1e-9);
        assertEquals(
                1,
                TextureDivergence.brightnessCorrelation(
                        checker(0xFF000000, 0xFFFFFFFF), checker(0xFF201008, 0xFF80FF40)),
                1e-9,
                "dark and light in other colours");
        assertEquals(
                1, TextureDivergence.brightnessCorrelation(picture, doubled), 1e-9, "scaled down");
    }

    @Test
    void aNegativeCorrelatesAtMinusOne() {
        assertEquals(
                -1,
                TextureDivergence.brightnessCorrelation(
                        checker(0xFF000000, 0xFFFFFFFF), checker(0xFFFFFFFF, 0xFF000000)),
                1e-9);
    }

    @Test
    void aFlatImageCorrelatesFullyOnlyWithAnotherFlatOne() {
        assertEquals(
                1,
                TextureDivergence.brightnessCorrelation(
                        filled(16, 16, 0xFFFF0000), filled(8, 8, 0xFF0000FF)));
        assertEquals(
                0,
                TextureDivergence.brightnessCorrelation(
                        filled(16, 16, 0xFFFF0000), checker(0xFF000000, 0xFFFFFFFF)));
    }

    @Test
    void aStripOfFramesCountsByItsFirstAndATransparentPixelAsBlack() {
        Pixels strip = Pixels.blank(16, 32);
        Pixels first = checker(0x00FFFFFF, 0xFFFFFFFF);
        Pixels second = checker(0xFFFFFFFF, 0xFF000000);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                strip.set(x, y, first.get(x, y));
                strip.set(x, y + 16, second.get(x, y));
            }
        }

        assertEquals(
                1,
                TextureDivergence.brightnessCorrelation(strip, checker(0xFF000000, 0xFFFFFFFF)),
                1e-9,
                "its first frame, whose transparent squares count as black");
    }
}
