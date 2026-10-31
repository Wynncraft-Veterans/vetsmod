package org.wynnvets.rendering.image;

import java.util.List;
import java.util.Set;
import org.wynnvets.rendering.colors.ColorMath;

/**
 * Drawing on {@link Pixels}: guarded fills, an art's edge and outline, nine-slice stretches and
 * stacks of frames.
 */
public final class PixelDrawing {

    /** The alpha from which a pixel counts as part of the art when tracing its shape. */
    private static final int HALF_OPAQUE = 0x80;

    private PixelDrawing() {}

    /**
     * Paints {@code rect} one colour, unless it holds a colour outside {@code palette}. The guard
     * keeps an edit written for one layout of a texture from painting over another: a rectangle
     * that leaves the image, or holds a translucent pixel or an RGB value the palette lacks, is left
     * alone.
     *
     * @param image the image, changed in place
     * @param rect the rectangle to paint
     * @param argb the colour
     * @param palette the RGB values ({@code 0xRRGGBB}) the rectangle may hold; every pixel in it
     *     must also be fully opaque
     * @return whether the rectangle was painted
     */
    public static boolean fill(Pixels image, Rect rect, int argb, Set<Integer> palette) {
        if (!image.contains(rect)) return false;
        for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
            for (int x = rect.x(); x < rect.x() + rect.width(); x++) {
                int pixel = image.get(x, y);
                if (ColorMath.alpha(pixel) != 0xFF || !palette.contains(pixel & 0xFFFFFF)) {
                    return false;
                }
            }
        }
        for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
            for (int x = rect.x(); x < rect.x() + rect.width(); x++) {
                image.set(x, y, argb);
            }
        }
        return true;
    }

    /**
     * The art's edge alone, in one colour: a pixel of at least half opacity with a side on a
     * transparent pixel or the image's border. Everything else is transparent.
     *
     * @param art the art
     * @param colour the edge's colour, {@code 0xAARRGGBB}
     * @return the new image, the art's size
     */
    public static Pixels edge(Pixels art, int colour) {
        return edge(art, colour, 0);
    }

    /**
     * The art's edge, as {@link #edge(Pixels, int)}, and the lines drawn inside it: every pixel of
     * at least half opacity darker than a brightness, so a shape the art only shades shows too.
     *
     * @param art the art
     * @param colour the colour, {@code 0xAARRGGBB}
     * @param linesBelowLuma the brightness, 0 to 255, below which an inner pixel is drawn; 0 for
     *     none
     * @return the new image, the art's size
     */
    public static Pixels edge(Pixels art, int colour, int linesBelowLuma) {
        int width = art.width();
        int height = art.height();
        Pixels drawn = Pixels.blank(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!solid(art.get(x, y))) continue;
                boolean inside =
                        x > 0
                                && y > 0
                                && x < width - 1
                                && y < height - 1
                                && solid(art.get(x - 1, y))
                                && solid(art.get(x + 1, y))
                                && solid(art.get(x, y - 1))
                                && solid(art.get(x, y + 1));
                if (!inside || ColorMath.luma(art.get(x, y)) < linesBelowLuma) {
                    drawn.set(x, y, colour);
                }
            }
        }
        return drawn;
    }

    /**
     * The art with an outline round it, on a canvas grown by the outline's thickness on each side:
     * every pixel within {@code thickness} of an opaque one of the art, in a circle, is the colour.
     * Pixels of the art lie over it as they were. A pixel of less than half opacity counts as none.
     *
     * @param art the art
     * @param thickness the outline's thickness, in the art's pixels
     * @param colour the outline's colour, {@code 0xAARRGGBB}
     * @return the new image
     */
    public static Pixels outlined(Pixels art, int thickness, int colour) {
        Pixels drawn = Pixels.blank(art.width() + 2 * thickness, art.height() + 2 * thickness);
        for (int y = 0; y < art.height(); y++) {
            for (int x = 0; x < art.width(); x++) {
                if (!solid(art.get(x, y))) continue;
                for (int dy = -thickness; dy <= thickness; dy++) {
                    for (int dx = -thickness; dx <= thickness; dx++) {
                        if (dx * dx + dy * dy > thickness * thickness) continue;
                        drawn.set(x + thickness + dx, y + thickness + dy, colour);
                    }
                }
            }
        }
        for (int y = 0; y < art.height(); y++) {
            for (int x = 0; x < art.width(); x++) {
                int pixel = art.get(x, y);
                if (solid(pixel)) drawn.set(x + thickness, y + thickness, pixel);
            }
        }
        return drawn;
    }

    private static boolean solid(int pixel) {
        return ColorMath.alpha(pixel) >= HALF_OPAQUE;
    }

    /**
     * Stretches a region of {@code source} to a new size the way Minecraft draws a nine-slice GUI
     * sprite: the corners are copied, and the edges and the middle are tiled from the region's own.
     * As in Minecraft, a border wider than half the new size shrinks to fit.
     *
     * @param source the image holding the region
     * @param region the region
     * @param border the width of the region's corners and edges
     * @param width the new width, at least 1
     * @param height the new height, at least 1
     * @return a new image
     * @throws IllegalArgumentException when the region leaves {@code source}, or its borders leave
     *     no middle to tile
     */
    public static Pixels nineSlice(Pixels source, Rect region, int border, int width, int height) {
        if (!source.contains(region)
                || border < 0
                || region.width() <= 2 * border
                || region.height() <= 2 * border) {
            throw new IllegalArgumentException(
                    region
                            + " with border "
                            + border
                            + " does not fit a "
                            + source.width()
                            + "x"
                            + source.height()
                            + " image");
        }
        Pixels out = Pixels.blank(width, height);
        for (int y = 0; y < height; y++) {
            int sourceY = region.y() + sliced(y, height, region.height(), border);
            for (int x = 0; x < width; x++) {
                int sourceX = region.x() + sliced(x, width, region.width(), border);
                out.set(x, y, source.get(sourceX, sourceY));
            }
        }
        return out;
    }

    /**
     * Along one axis, the coordinate in a nine-slice source of length {@code from} that the
     * coordinate {@code at} in one of length {@code to} shows.
     */
    private static int sliced(int at, int to, int from, int border) {
        int edge = Math.min(border, to / 2);
        if (at < edge) return at;
        if (at >= to - edge) return from - (to - at);
        return border + (at - edge) % (from - 2 * border);
    }

    /**
     * Stacks images of one width top to bottom.
     *
     * @param frames the images, top first; at least one
     * @return a new image
     * @throws IllegalArgumentException when there are none or their widths differ
     */
    public static Pixels stack(List<Pixels> frames) {
        if (frames.isEmpty()) throw new IllegalArgumentException("nothing to stack");
        int width = frames.get(0).width();
        int height = 0;
        for (Pixels frame : frames) {
            if (frame.width() != width) {
                throw new IllegalArgumentException(
                        "frames of widths " + width + " and " + frame.width());
            }
            height += frame.height();
        }
        int[] argb = new int[width * height];
        int at = 0;
        for (Pixels frame : frames) {
            System.arraycopy(frame.argb(), 0, argb, at, frame.argb().length);
            at += frame.argb().length;
        }
        return new Pixels(width, height, argb);
    }
}
