package org.wynnvets.rendering.colors;

import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Recolours: operators on one {@code 0xAARRGGBB} pixel, for {@link
 * org.wynnvets.rendering.image.Pixels#apply(IntUnaryOperator) Pixels.apply} to run over an image.
 * Each keeps the pixel's alpha and leaves its channels in {@code 0..255}.
 *
 * <p>They sit beside {@link ColorMath}, not in it, because they clamp and it does not. No static
 * fields and no Minecraft imports, so this class loads nothing else.
 */
public final class Recolours {

    private Recolours() {}

    /**
     * Which pixels {@link #withOutline} counts as an outline, and what becomes of them.
     *
     * @param belowLuma the luma, 0 to 255, below which a pixel can be outline
     * @param maxSpread how far apart a pixel's strongest and weakest channels can be, 0 to 255, for
     *     it to be outline
     * @param treatment what becomes of an outline pixel
     */
    public record Outline(int belowLuma, int maxSpread, Treatment treatment) {

        /** What becomes of an outline pixel. */
        public enum Treatment {
            /** It stays as it is. */
            KEEP,
            /** It turns the grey of its luma. */
            GREY
        }

        /** An outline of the pixels darker than {@code luma}, which turn the grey of their luma. */
        public static Outline greyBelow(int luma) {
            return new Outline(luma, 255, Treatment.GREY);
        }

        /**
         * An outline of the near-grey pixels darker than {@code luma}, which stay as they are, so a
         * dye passes over a black line. A dark pixel with a colour of its own is not outline.
         */
        public static Outline keepNeutralBelow(int luma) {
            return new Outline(luma, 12, Treatment.KEEP);
        }
    }

    /**
     * Multiplies red, green and blue by a factor each, rounding and clamping at 255.
     *
     * @param red the factor for red, at least 0
     * @param green the factor for green, at least 0
     * @param blue the factor for blue, at least 0
     */
    public static IntUnaryOperator multiply(double red, double green, double blue) {
        return pixel ->
                alphaOf(pixel)
                        | ColorMath.rgb(
                                scaled(ColorMath.red(pixel), red),
                                scaled(ColorMath.green(pixel), green),
                                scaled(ColorMath.blue(pixel), blue));
    }

    /**
     * Multiplies each channel by a colour's, as Minecraft's tints do: a channel becomes {@code
     * channel * tint / 255}, rounded.
     *
     * @param rgb the tint, {@code 0xRRGGBB}; white changes nothing
     */
    public static IntUnaryOperator tint(int rgb) {
        return multiply(
                ColorMath.red(rgb) / 255.0,
                ColorMath.green(rgb) / 255.0,
                ColorMath.blue(rgb) / 255.0);
    }

    /**
     * Scales each channel so that one colour of the art comes out as another: it is multiplied by
     * {@code to / from}. Unlike {@link #tint}, this can lighten the art; the pixels brighter than
     * {@code from} clamp. A channel that is 0 in {@code from} is left as it is.
     *
     * @param from the colour of the art, {@code 0xRRGGBB}
     * @param to the colour it becomes, {@code 0xRRGGBB}
     */
    public static IntUnaryOperator scale(int from, int to) {
        return multiply(
                factor(ColorMath.red(from), ColorMath.red(to)),
                factor(ColorMath.green(from), ColorMath.green(to)),
                factor(ColorMath.blue(from), ColorMath.blue(to)));
    }

    /**
     * Turns the pixels of one colour's hue into another colour, each as bright as it was against
     * the first: a pixel whose strongest channel, strictly, is {@code from}'s strongest becomes
     * {@code to} scaled by that channel over {@code from}'s, clamped. Other pixels, the grey of a
     * book's pages or of a bucket among them, are left as they are.
     *
     * @param from the colour of the art, {@code 0xRRGGBB}, with one strongest channel
     * @param to the colour it becomes, {@code 0xRRGGBB}
     * @throws IllegalArgumentException when two channels of {@code from} tie for the strongest
     */
    public static IntUnaryOperator hueOnly(int from, int to) {
        int channel = ColorMath.strongestChannel(from);
        if (channel < 0) {
            throw new IllegalArgumentException(
                    Integer.toHexString(from) + " has no one strongest channel");
        }
        double strength = ColorMath.channel(from, channel);
        return pixel -> {
            if (ColorMath.strongestChannel(pixel) != channel) return pixel;
            double brightness = ColorMath.channel(pixel, channel) / strength;
            return alphaOf(pixel)
                    | ColorMath.rgb(
                            scaled(ColorMath.red(to), brightness),
                            scaled(ColorMath.green(to), brightness),
                            scaled(ColorMath.blue(to), brightness));
        };
    }

    /**
     * Redraws in a ramp of colours: a pixel becomes the colour its luma reaches along the ramp,
     * whose colours are spaced evenly from black to white, so the art's light and shade survive in
     * another material.
     *
     * @param colours at least two colours, {@code 0xRRGGBB}, darkest first
     * @throws IllegalArgumentException when there are fewer than two colours
     */
    public static IntUnaryOperator ramp(List<Integer> colours) {
        if (colours.size() < 2) throw new IllegalArgumentException("a ramp needs two colours");
        int[] stops = colours.stream().mapToInt(Integer::intValue).toArray();
        int segments = stops.length - 1;
        return pixel -> {
            double place = ColorMath.luma(pixel) / 255.0 * segments;
            int low = Math.min((int) place, segments - 1);
            return alphaOf(pixel)
                    | ColorMath.interpolateRgb(stops[low], stops[low + 1], place - low);
        };
    }

    /**
     * Moves a visible pixel's red, green and blue toward a pivot, or away from it: a channel
     * becomes {@code pivot + contrast * (channel - pivot)}, rounded and clamped. Below 1 the art grows
     * flatter, above 1 busier. A fully transparent pixel is left as it is.
     *
     * @param contrast at least 0; 1 changes nothing
     * @param pivot {@code {red, green, blue}}, the values each channel moves toward
     */
    public static IntUnaryOperator contrast(double contrast, double[] pivot) {
        return pixel -> {
            if (ColorMath.alpha(pixel) == 0) return pixel;
            return alphaOf(pixel)
                    | ColorMath.rgb(
                            contrasted(ColorMath.red(pixel), pivot[0], contrast),
                            contrasted(ColorMath.green(pixel), pivot[1], contrast),
                            contrasted(ColorMath.blue(pixel), pivot[2], contrast));
        };
    }

    /**
     * {@code inner}, except on the pixels {@code outline} counts: those take its treatment.
     *
     * @param inner the recolour for every other pixel
     * @param outline which pixels are outline
     */
    public static IntUnaryOperator withOutline(IntUnaryOperator inner, Outline outline) {
        return pixel -> {
            double luma = ColorMath.luma(pixel);
            if (luma >= outline.belowLuma() || spread(pixel) > outline.maxSpread()) {
                return inner.applyAsInt(pixel);
            }
            if (outline.treatment() == Outline.Treatment.KEEP) return pixel;
            int grey = (int) Math.round(luma);
            return alphaOf(pixel) | ColorMath.rgb(grey, grey, grey);
        };
    }

    /**
     * The luma below which a near-grey pixel is a line drawn on art rather than the art itself:
     * half the art's main colour, and never above 64. Being relative, it leaves the body of dark
     * art out of the outline.
     *
     * @param from the art's main colour, {@code 0xRRGGBB}
     */
    public static int outlineCutoff(int from) {
        return (int) Math.round(Math.min(64, ColorMath.luma(from) / 2));
    }

    private static int alphaOf(int pixel) {
        return pixel & 0xFF000000;
    }

    private static int spread(int pixel) {
        int red = ColorMath.red(pixel);
        int green = ColorMath.green(pixel);
        int blue = ColorMath.blue(pixel);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
    }

    private static int scaled(int channel, double factor) {
        return (int) Math.min(255, Math.round(channel * factor));
    }

    private static double factor(int from, int to) {
        return from == 0 ? 1 : to / (double) from;
    }

    private static int contrasted(int channel, double pivot, double contrast) {
        long value = Math.round(pivot + contrast * (channel - pivot));
        return (int) Math.max(0, Math.min(255, value));
    }
}
