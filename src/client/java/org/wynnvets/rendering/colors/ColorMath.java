package org.wynnvets.rendering.colors;

/**
 * Arithmetic on one colour: its channels, its luma, its strongest channel, and the mod's RGB
 * interpolation. Whole-image recolours, which clamp each channel, are {@link Recolours}.
 *
 * <p>{@code NametagAnimator}'s {@code mixToward}, {@code lighten} and {@code darken} stay out of
 * this class: they compute a weighted average and then clamp each channel into {@code 0..255},
 * which {@link #interpolateRgb(int, int, float)} deliberately does not.
 *
 * <p>No static fields, no supertypes, so no {@code <clinit>} and no Minecraft class loads when this
 * one does.
 */
public final class ColorMath {

    private ColorMath() {}

    /**
     * One channel of a colour.
     *
     * @param colour the colour; an alpha byte is ignored unless {@code shift} is 24
     * @param shift the channel's shift: 16 red, 8 green, 0 blue
     * @return the channel, {@code 0..255}
     */
    public static int channel(int colour, int shift) {
        return (colour >> shift) & 0xFF;
    }

    /** The alpha of {@code 0xAARRGGBB}, {@code 0..255}. */
    public static int alpha(int argb) {
        return argb >>> 24;
    }

    /** The red of {@code 0xRRGGBB} or {@code 0xAARRGGBB}, {@code 0..255}. */
    public static int red(int colour) {
        return channel(colour, 16);
    }

    /** The green of {@code 0xRRGGBB} or {@code 0xAARRGGBB}, {@code 0..255}. */
    public static int green(int colour) {
        return channel(colour, 8);
    }

    /** The blue of {@code 0xRRGGBB} or {@code 0xAARRGGBB}, {@code 0..255}. */
    public static int blue(int colour) {
        return channel(colour, 0);
    }

    /**
     * Packs three channels into {@code 0xRRGGBB}. The channels are not masked: one outside {@code
     * 0..255} bleeds into its neighbour, which {@link #interpolateRgb(int, int, float)}'s contract
     * relies on.
     */
    public static int rgb(int red, int green, int blue) {
        return red << 16 | green << 8 | blue;
    }

    /**
     * A colour's luma: {@code 0.299} red, {@code 0.587} green, {@code 0.114} blue.
     *
     * @param colour {@code 0xRRGGBB} or {@code 0xAARRGGBB}; alpha is ignored
     * @return the luma, {@code 0..255}
     */
    public static double luma(int colour) {
        return 0.299 * red(colour) + 0.587 * green(colour) + 0.114 * blue(colour);
    }

    /**
     * The shift of a colour's one strongest channel, for {@link #channel}.
     *
     * @param colour {@code 0xRRGGBB} or {@code 0xAARRGGBB}; alpha is ignored
     * @return 16, 8 or 0; -1 when two channels tie for the strongest, as in any grey
     */
    public static int strongestChannel(int colour) {
        int red = red(colour);
        int green = green(colour);
        int blue = blue(colour);
        if (red > green && red > blue) return 16;
        if (green > red && green > blue) return 8;
        return blue > red && blue > green ? 0 : -1;
    }

    /**
     * Linearly interpolates each of the three 8-bit channels of two {@code 0xRRGGBB} colours.
     *
     * <p><b>{@code t} is not clamped</b>, and that is the contract. Outside {@code [0, 1]} a
     * channel rounds past {@code 0..255}, and the shift-and-or recombination lets it bleed into the
     * neighbouring channel or, for a negative {@code t}, into the sign bit: {@code t = 1.5} on
     * black&rarr;white gives {@code 0x017F7F7F}, {@code t = 2} gives {@code 0x01FFFFFE}, and {@code
     * t = -0.5} gives {@code 0xFFFFFF81}. {@code ColorMathTest} pins these literals, and every
     * caller feeds a normalised {@code t}.
     *
     * <p>An alpha byte on either input is discarded.
     *
     * @param startRgb the colour at {@code t = 0} (0xRRGGBB)
     * @param endRgb the colour at {@code t = 1} (0xRRGGBB)
     * @param t the interpolation fraction, normally in {@code [0, 1]}
     * @return the interpolated colour (0xRRGGBB)
     */
    public static int interpolateRgb(int startRgb, int endRgb, float t) {
        int startR = red(startRgb);
        int startG = green(startRgb);
        int startB = blue(startRgb);
        return rgb(
                Math.round(startR + (red(endRgb) - startR) * t),
                Math.round(startG + (green(endRgb) - startG) * t),
                Math.round(startB + (blue(endRgb) - startB) * t));
    }

    /**
     * {@link #interpolateRgb(int, int, float)} in double precision, for a fraction that is already
     * a {@code double}: the same contract, but a float would round some channels a level apart.
     *
     * @param startRgb the colour at {@code t = 0} (0xRRGGBB)
     * @param endRgb the colour at {@code t = 1} (0xRRGGBB)
     * @param t the interpolation fraction, normally in {@code [0, 1]}
     * @return the interpolated colour (0xRRGGBB)
     */
    public static int interpolateRgb(int startRgb, int endRgb, double t) {
        int startR = red(startRgb);
        int startG = green(startRgb);
        int startB = blue(startRgb);
        return rgb(
                (int) Math.round(startR + (red(endRgb) - startR) * t),
                (int) Math.round(startG + (green(endRgb) - startG) * t),
                (int) Math.round(startB + (blue(endRgb) - startB) * t));
    }
}
