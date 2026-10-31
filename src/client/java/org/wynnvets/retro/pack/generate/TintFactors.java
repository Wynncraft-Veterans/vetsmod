package org.wynnvets.retro.pack.generate;

import java.util.function.IntUnaryOperator;
import org.wynnvets.rendering.colors.ColorMath;
import org.wynnvets.rendering.colors.Recolours;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.retro.manifest.TintFactor;

/**
 * The edit a {@code tints} row makes: the factors it multiplies its source's channels by, and the
 * contrast it changes after them.
 */
final class TintFactors {

    private TintFactors() {}

    /**
     * The factors a {@code tints} row multiplies its source's red, green and blue by. The row's
     * tint gives a ratio for each channel, and its strength moves that ratio toward 1:
     * {@code factor = 1 + strength * (ratio - 1)}. The ratio is, by the kind of tint:
     *
     * <ul>
     *   <li>a colour: the colour's channel over 255, as Minecraft's tints multiply;
     *   <li>{@code match}: the mean of {@code reference} over the mean of {@code source};
     *   <li>{@code relative_to}: the mean of {@code live} over the mean of {@code reference};
     *   <li>{@code auto}: the mean of {@code live} over the mean of {@code source};
     *   <li>no tint ({@link TintFactor.Unchanged}): 1.
     * </ul>
     *
     * <p>A {@code like} ({@link TintFactor.Like}) has no factors of its own: they are those of the
     * row it names, found with that row's tint.
     *
     * <p>Each mean is {@link Pixels#mean}'s, over the visible pixels only.
     *
     * @param factor the row's tint
     * @param strength how much of the ratio is applied, from 0 (none) to 1 (all of it)
     * @param source the image the tint starts from
     * @param reference for {@code match}, the matched texture; for {@code relative_to}, Wynncraft's
     *     live copy of the texture it names; unused otherwise, and may be {@code null}
     * @param live for {@code relative_to} and {@code auto}, Wynncraft's live copy of the tinted
     *     texture; unused otherwise, and may be {@code null}
     * @return {@code {red, green, blue}}, each at least 0; {@code null} when an image the ratio
     *     needs is {@code null} or fully transparent, or a mean it divides by has a channel of 0
     * @throws IllegalArgumentException for a {@link TintFactor.Like}, which has no factors of its
     *     own
     */
    static double[] measure(
            TintFactor factor, double strength, Pixels source, Pixels reference, Pixels live) {
        double[] ratio =
                switch (factor) {
                    case TintFactor.Colour colour ->
                            new double[] {
                                ColorMath.red(colour.rgb()) / 255.0,
                                ColorMath.green(colour.rgb()) / 255.0,
                                ColorMath.blue(colour.rgb()) / 255.0
                            };
                    case TintFactor.Match match -> ratio(reference, source);
                    case TintFactor.RelativeTo relative -> ratio(live, reference);
                    case TintFactor.Auto auto -> ratio(live, source);
                    case TintFactor.Unchanged unchanged -> new double[] {1, 1, 1};
                    case TintFactor.Like like ->
                            throw new IllegalArgumentException(
                                    "a like takes the factors of the row for " + like.texture());
                };
        if (ratio == null) return null;
        for (int channel = 0; channel < ratio.length; channel++) {
            ratio[channel] = 1 + strength * (ratio[channel] - 1);
        }
        return ratio;
    }

    /**
     * The mean of {@code to} over the mean of {@code from}, channel by channel; {@code null} when
     * either is {@code null} or fully transparent, or a channel of {@code from}'s mean is 0.
     */
    private static double[] ratio(Pixels to, Pixels from) {
        double[] over = to == null ? null : to.mean();
        double[] under = from == null ? null : from.mean();
        if (over == null || under == null) return null;
        double[] ratio = new double[3];
        for (int channel = 0; channel < ratio.length; channel++) {
            if (under[channel] == 0) return null;
            ratio[channel] = over[channel] / under[channel];
        }
        return ratio;
    }

    /**
     * A {@code tints} row's edit: each channel multiplied by its factor, then the contrast changed
     * around the mean of the result, so the colour the factors gave stays the mean.
     *
     * @param image the image, changed in place; a fully transparent one has no mean to turn around
     * @param factors {@code {red, green, blue}}, as {@link #measure} gives them
     * @param contrast at least 0; 1 changes nothing
     */
    static void tint(Pixels image, double[] factors, double contrast) {
        image.apply(multiply(factors));
        if (contrast == 1) return;
        double[] mean = image.mean();
        if (mean != null) image.apply(Recolours.contrast(contrast, mean));
    }

    /**
     * A {@code like} row's edit: the change the row it names makes to its own texture. Each channel
     * is multiplied by that row's factors, then the contrast changed around {@code pivot}, that
     * texture's mean after the same factors ({@link #tintedMean}), so a pixel the image shares with
     * that texture comes out as it does there.
     *
     * @param image the image, changed in place
     * @param factors {@code {red, green, blue}}, the named row's
     * @param contrast the named row's, at least 0; 1 changes nothing
     * @param pivot {@code {red, green, blue}}, the named texture's {@link #tintedMean}
     */
    static void tint(Pixels image, double[] factors, double contrast, double[] pivot) {
        image.apply(multiply(factors));
        if (contrast != 1) image.apply(Recolours.contrast(contrast, pivot));
    }

    /**
     * The mean {@link #tint(Pixels, double[], double)}'s contrast turns around: {@link
     * Pixels#mean}'s of the image once multiplied by {@code factors}. The image itself is left as
     * it is.
     *
     * @param image the image
     * @param factors {@code {red, green, blue}}
     * @return {@code {red, green, blue}}; {@code null} when every pixel is fully transparent
     */
    static double[] tintedMean(Pixels image, double[] factors) {
        Pixels multiplied = image.copy();
        multiplied.apply(multiply(factors));
        return multiplied.mean();
    }

    private static IntUnaryOperator multiply(double[] factors) {
        return Recolours.multiply(factors[0], factors[1], factors[2]);
    }
}
