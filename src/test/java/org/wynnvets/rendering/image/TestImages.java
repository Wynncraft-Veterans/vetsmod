package org.wynnvets.rendering.image;

import java.util.Arrays;

/** Small hand-built images for the pixel tests. */
public final class TestImages {

    private TestImages() {}

    /** A {@code width} by {@code height} image filled with {@code argb}. */
    public static Pixels filled(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, argb);
        return new Pixels(width, height, pixels);
    }

    /** A {@code width} by {@code height} opaque image whose pixel at (x, y) is x + 10y. */
    public static Pixels numbered(int width, int height) {
        Pixels image = Pixels.blank(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.set(x, y, 0xFF000000 | (x + 10 * y));
            }
        }
        return image;
    }
}
