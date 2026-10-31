package org.wynnvets.rendering.image;

import java.util.function.IntUnaryOperator;
import org.wynnvets.rendering.colors.ColorMath;

/**
 * An image: its ARGB pixels, {@code 0xAARRGGBB}, row by row from the top left. It needs no running
 * client: {@link PngCodec} connects it to PNG files, {@link PixelDrawing} draws with it and {@link
 * org.wynnvets.rendering.colors.Recolours Recolours} supplies the operators {@link #apply} takes.
 */
public final class Pixels {

    private final int width;
    private final int height;
    private final int[] argb;

    /**
     * An image over {@code argb}, which it keeps rather than copies.
     *
     * @param width the width, at least 1
     * @param height the height, at least 1
     * @param argb {@code width * height} pixels
     * @throws IllegalArgumentException when the sizes do not fit
     */
    public Pixels(int width, int height, int[] argb) {
        if (width < 1 || height < 1 || argb.length != width * height) {
            throw new IllegalArgumentException(
                    "a " + width + "x" + height + " image cannot hold " + argb.length);
        }
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    /**
     * A fully transparent image.
     *
     * @param width the width, at least 1
     * @param height the height, at least 1
     * @return a new image
     */
    public static Pixels blank(int width, int height) {
        return new Pixels(width, height, new int[width * height]);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The pixel at {@code (x, y)}, as {@code 0xAARRGGBB}. */
    public int get(int x, int y) {
        return argb[y * width + x];
    }

    /** Sets the pixel at {@code (x, y)} to {@code argb}. */
    public void set(int x, int y, int argb) {
        this.argb[y * width + x] = argb;
    }

    /** Whether {@code rect} lies wholly inside the image. */
    public boolean contains(Rect rect) {
        return rect.width() > 0
                && rect.height() > 0
                && new Rect(0, 0, width, height).contains(rect);
    }

    /** The pixels themselves, not a copy. */
    public int[] argb() {
        return argb;
    }

    /** A new image with these pixels, sharing nothing with this one. */
    public Pixels copy() {
        return new Pixels(width, height, argb.clone());
    }

    /**
     * Replaces every pixel by what {@code recolour} makes of it.
     *
     * @param recolour the operator on {@code 0xAARRGGBB}
     */
    public void apply(IntUnaryOperator recolour) {
        for (int i = 0; i < argb.length; i++) {
            argb[i] = recolour.applyAsInt(argb[i]);
        }
    }

    /**
     * The mean red, green and blue of the visible pixels, each pixel counting once whatever its
     * alpha.
     *
     * @return {@code {red, green, blue}}, each {@code 0..255}; {@code null} when every pixel is
     *     fully transparent
     */
    public double[] mean() {
        long red = 0;
        long green = 0;
        long blue = 0;
        int visible = 0;
        for (int pixel : argb) {
            if (ColorMath.alpha(pixel) == 0) continue;
            red += ColorMath.red(pixel);
            green += ColorMath.green(pixel);
            blue += ColorMath.blue(pixel);
            visible++;
        }
        if (visible == 0) return null;
        return new double[] {
            (double) red / visible, (double) green / visible, (double) blue / visible
        };
    }
}
