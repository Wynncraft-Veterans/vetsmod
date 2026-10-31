package org.wynnvets.rendering.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link Pixels}: its size guard, rectangles, per-pixel apply, copies and mean. */
class PixelsTest {

    @Test
    void aBlankImageIsFullyTransparent() {
        Pixels blank = Pixels.blank(3, 2);

        assertEquals(3, blank.width());
        assertEquals(2, blank.height());
        assertArrayEquals(new int[6], blank.argb());
    }

    @Test
    void anImageMustHoldExactlyItsPixels() {
        assertThrows(IllegalArgumentException.class, () -> new Pixels(2, 2, new int[3]));
        assertThrows(IllegalArgumentException.class, () -> new Pixels(0, 2, new int[0]));
    }

    @Test
    void containsTakesOnlyRectanglesWhollyInside() {
        Pixels image = Pixels.blank(4, 3);

        assertTrue(image.contains(new Rect(0, 0, 4, 3)));
        assertTrue(image.contains(new Rect(3, 2, 1, 1)));
        assertFalse(image.contains(new Rect(3, 2, 2, 1)), "leaves on the right");
        assertFalse(image.contains(new Rect(-1, 0, 1, 1)), "starts outside");
        assertFalse(image.contains(new Rect(0, 0, 0, 1)), "empty");
    }

    @Test
    void theMeanCountsEachVisiblePixelOnce() {
        Pixels image = new Pixels(3, 1, new int[] {0xFF000000, 0x80FF0000, 0x00FFFFFF});

        assertArrayEquals(new double[] {127.5, 0, 0}, image.mean());
        assertNull(Pixels.blank(2, 2).mean(), "nothing visible");
    }

    @Test
    void applyReplacesEveryPixelInPlace() {
        Pixels image = new Pixels(2, 1, new int[] {1, 2});

        image.apply(pixel -> pixel + 10);

        assertArrayEquals(new int[] {11, 12}, image.argb());
    }

    @Test
    void aCopySharesNothingWithItsOriginal() {
        Pixels image = new Pixels(2, 1, new int[] {1, 2});

        Pixels copy = image.copy();
        copy.set(0, 0, 9);

        assertEquals(2, copy.width());
        assertEquals(1, copy.height());
        assertEquals(1, image.get(0, 0));
    }

    @Test
    void aRectWritesItselfAsAYamlFlowList() {
        assertEquals("[97, 17, 74, 36]", new Rect(97, 17, 74, 36).toYaml());
    }
}
