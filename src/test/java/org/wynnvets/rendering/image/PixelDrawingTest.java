package org.wynnvets.rendering.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.rendering.image.TestImages.filled;
import static org.wynnvets.rendering.image.TestImages.numbered;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link PixelDrawing}: guarded fills, edges, outlines, nine-slices and stacks. */
class PixelDrawingTest {

    private static final int SLOT = 0xFF8B8B8B;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int PANEL = 0xFFC6C6C6;

    /** The slot colours as RGB, the way a fill's palette holds them. */
    private static final Set<Integer> SLOT_PALETTE = Set.of(0x8B8B8B, 0xFFFFFF);

    // ----- Fills -----

    @Test
    void aFillPaintsARectangleThatHoldsOnlyPaletteColours() {
        Pixels image = filled(4, 4, SLOT);
        image.set(2, 2, LIGHT);

        assertTrue(PixelDrawing.fill(image, new Rect(1, 1, 2, 2), PANEL, SLOT_PALETTE));

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                boolean inside = x >= 1 && x <= 2 && y >= 1 && y <= 2;
                assertEquals(inside ? PANEL : SLOT, image.get(x, y), x + "," + y);
            }
        }
    }

    @Test
    void aFillLeavesARectangleWithAColourOutsideThePaletteAlone() {
        // The texture's layout has changed: painting would cover other art.
        Pixels image = filled(4, 4, SLOT);
        image.set(2, 1, 0xFF123456);
        int[] before = image.argb().clone();

        assertFalse(PixelDrawing.fill(image, new Rect(1, 1, 2, 2), PANEL, SLOT_PALETTE));
        assertArrayEquals(before, image.argb());
    }

    @Test
    void aFillLeavesATranslucentPixelAlone() {
        Pixels image = filled(4, 4, SLOT);
        image.set(1, 1, 0x808B8B8B);
        int[] before = image.argb().clone();

        assertFalse(PixelDrawing.fill(image, new Rect(1, 1, 2, 2), PANEL, SLOT_PALETTE));
        assertArrayEquals(before, image.argb());
    }

    @Test
    void aFillLeavesARectangleThatLeavesTheImageAlone() {
        Pixels image = filled(4, 4, SLOT);
        int[] before = image.argb().clone();

        assertFalse(PixelDrawing.fill(image, new Rect(3, 3, 2, 2), PANEL, SLOT_PALETTE));
        assertArrayEquals(before, image.argb());
    }

    // ----- Edges and outlines -----

    @Test
    void edgeKeepsOnlyThePixelsBesideTransparencyInOneColour() {
        int o = 0xFF123456;
        Pixels art =
                new Pixels(
                        5,
                        5,
                        new int[] {
                            0, 0, 0, 0, 0,
                            0, o, o, o, 0,
                            0, o, o, o, 0,
                            0, o, o, o, 0,
                            0, 0, 0, 0, 0
                        });

        int e = 0xFF555555;
        assertArrayEquals(
                new int[] {
                    0, 0, 0, 0, 0,
                    0, e, e, e, 0,
                    0, e, 0, e, 0,
                    0, e, e, e, 0,
                    0, 0, 0, 0, 0
                },
                PixelDrawing.edge(art, e).argb());
    }

    @Test
    void edgeCountsTheImagesBorderAsTransparent() {
        Pixels art = new Pixels(1, 1, new int[] {0xFF123456});

        assertArrayEquals(new int[] {0xFF555555}, PixelDrawing.edge(art, 0xFF555555).argb());
    }

    @Test
    void edgeAlsoDrawsTheInnerPixelsDarkerThanTheBrightnessGiven() {
        int l = 0xFFC8C8C8;
        int d = 0xFF181818;
        Pixels art =
                new Pixels(
                        5,
                        5,
                        new int[] {
                            0, 0, 0, 0, 0,
                            0, l, l, l, 0,
                            0, l, d, l, 0,
                            0, l, l, l, 0,
                            0, 0, 0, 0, 0
                        });

        int e = 0xFF373737;
        assertArrayEquals(
                new int[] {
                    0, 0, 0, 0, 0,
                    0, e, e, e, 0,
                    0, e, e, e, 0,
                    0, e, e, e, 0,
                    0, 0, 0, 0, 0
                },
                PixelDrawing.edge(art, e, 30).argb());
        assertArrayEquals(PixelDrawing.edge(art, e).argb(), PixelDrawing.edge(art, e, 0x18).argb());
    }

    @Test
    void outlinedGrowsTheCanvasAndRingsTheArtInACircle() {
        Pixels art = new Pixels(1, 1, new int[] {0xFF123456});

        Pixels one = PixelDrawing.outlined(art, 1, 0xFF191919);

        assertArrayEquals(
                new int[] {
                    0, 0xFF191919, 0,
                    0xFF191919, 0xFF123456, 0xFF191919,
                    0, 0xFF191919, 0
                },
                one.argb(),
                "the corners are outside the circle");
    }

    @Test
    void outlinedIgnoresAPixelOfLessThanHalfOpacity() {
        Pixels art = new Pixels(2, 1, new int[] {0x40FFFFFF, 0xFF123456});

        Pixels two = PixelDrawing.outlined(art, 1, 0xFF191919);

        assertArrayEquals(
                new int[] {
                    0, 0, 0xFF191919, 0,
                    0, 0xFF191919, 0xFF123456, 0xFF191919,
                    0, 0, 0xFF191919, 0
                },
                two.argb());
    }

    // ----- Nine-slice -----

    @Test
    void nineSliceCopiesTheCornersAndTilesTheEdgesAndMiddle() {
        // A 5x5 source with a 1-pixel border has a 3x3 middle, tiled across the 7x4 output.
        Pixels out = PixelDrawing.nineSlice(numbered(5, 5), new Rect(0, 0, 5, 5), 1, 7, 4);
        int[] columns = {0, 1, 2, 3, 1, 2, 4};
        int[] rows = {0, 1, 2, 4};

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 7; x++) {
                assertEquals(0xFF000000 | (columns[x] + 10 * rows[y]), out.get(x, y), x + "," + y);
            }
        }
    }

    @Test
    void nineSliceReadsOnlyItsRegion() {
        Pixels out = PixelDrawing.nineSlice(numbered(6, 6), new Rect(2, 1, 3, 3), 1, 3, 3);

        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(0xFF000000 | (2 + x + 10 * (1 + y)), out.get(x, y), x + "," + y);
            }
        }
    }

    @Test
    void nineSliceShrinksABorderWiderThanHalfTheOutput() {
        // As Minecraft does: a 2x2 output of a 2-pixel border shows the four corner pixels.
        Pixels out = PixelDrawing.nineSlice(numbered(6, 6), new Rect(0, 0, 6, 6), 2, 2, 2);

        assertArrayEquals(
                new int[] {0xFF000000, 0xFF000005, 0xFF000000 | 50, 0xFF000000 | 55}, out.argb());
    }

    @Test
    void nineSliceRefusesARegionItCannotTile() {
        Pixels source = numbered(5, 5);

        assertThrows(
                IllegalArgumentException.class,
                () -> PixelDrawing.nineSlice(source, new Rect(1, 1, 5, 5), 1, 4, 4),
                "the region leaves the image");
        assertThrows(
                IllegalArgumentException.class,
                () -> PixelDrawing.nineSlice(source, new Rect(0, 0, 4, 4), 2, 4, 4),
                "the borders leave no middle");
    }

    // ----- Stacks -----

    @Test
    void stackPutsTheFramesTopToBottom() {
        Pixels top = filled(2, 1, SLOT);
        Pixels bottom = filled(2, 2, PANEL);

        Pixels stacked = PixelDrawing.stack(List.of(top, bottom));

        assertEquals(2, stacked.width());
        assertEquals(3, stacked.height());
        assertArrayEquals(new int[] {SLOT, SLOT, PANEL, PANEL, PANEL, PANEL}, stacked.argb());
    }

    @Test
    void stackRefusesNoFramesOrFramesOfDifferentWidths() {
        assertThrows(IllegalArgumentException.class, () -> PixelDrawing.stack(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> PixelDrawing.stack(List.of(Pixels.blank(2, 1), Pixels.blank(3, 1))));
    }
}
