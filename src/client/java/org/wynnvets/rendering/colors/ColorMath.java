package org.wynnvets.rendering.colors;

/**
 * The mod's shared RGB interpolation.
 *
 * <p>Three classes each declared their own private lerp &mdash; byte-identical modulo local
 * variable names: {@link GradientTextBuilder}'s {@code interpolateRgb},
 * {@link AnimatedGradientSequence}'s {@code interpolateColor}, and
 * {@code org.wynnvets.rendering.nametag.NametagAnimator}'s {@code interpolateColor}. All four
 * call sites read this one instead.</p>
 *
 * <p>It lives here rather than in {@code org.wynnvets.util} because three of the four call
 * sites are already in this package, next to {@link ShaderColorPalette}; {@code util} is
 * documented as the home for helpers with no home, and this one has one.</p>
 *
 * <p><b>{@code NametagAnimator}'s {@code mixToward}, {@code lighten} and {@code darken} are
 * deliberately not residents.</b> They compute the same weighted average and then <i>clamp</i>
 * each channel into {@code 0..255} before recombining. Folding them in here would either
 * silently clamp this method's four call sites or silently unclamp their four &mdash; a
 * behaviour change disguised as a de-duplication, in both directions at once.</p>
 *
 * <p>No static fields, no supertypes, so no {@code <clinit>} and no Minecraft class loads when
 * this one does.</p>
 */
public final class ColorMath {

    private ColorMath() {}

    /**
     * Linearly interpolates each of the three 8-bit channels of two {@code 0xRRGGBB} colours.
     *
     * <p><b>{@code t} is not clamped</b>, and that is the contract, not an oversight. Outside
     * {@code [0, 1]} a channel rounds past {@code 0..255}, and the shift-and-or recombination
     * lets it bleed into the neighbouring channel or, for a negative {@code t}, into the sign
     * bit: {@code t = 1.5} on black&rarr;white gives {@code 0x017F7F7F}, {@code t = 2} gives
     * {@code 0x01FFFFFE}, and {@code t = -0.5} gives {@code 0xFFFFFF81}. All three copies
     * behaved this way and all four call sites feed a normalised {@code t}; the literals are
     * pinned in {@code ColorMathTest} so that adding a clamp has to be an argued change rather
     * than a tidy-up.</p>
     *
     * <p>An alpha byte on either input is discarded: each channel is masked to 8 bits after its
     * shift and the result is reassembled without one.</p>
     *
     * @param startRgb the colour at {@code t = 0} (0xRRGGBB)
     * @param endRgb   the colour at {@code t = 1} (0xRRGGBB)
     * @param t        the interpolation fraction, normally in {@code [0, 1]}
     * @return the interpolated colour (0xRRGGBB)
     */
    public static int interpolateRgb(int startRgb, int endRgb, float t) {
        int startR = (startRgb >> 16) & 0xFF;
        int startG = (startRgb >> 8) & 0xFF;
        int startB = startRgb & 0xFF;

        int endR = (endRgb >> 16) & 0xFF;
        int endG = (endRgb >> 8) & 0xFF;
        int endB = endRgb & 0xFF;

        int r = Math.round(startR + (endR - startR) * t);
        int g = Math.round(startG + (endG - startG) * t);
        int b = Math.round(startB + (endB - startB) * t);

        return (r << 16) | (g << 8) | b;
    }
}
