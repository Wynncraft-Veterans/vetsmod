package org.wynnvets.retro.manifest;

import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.wynnvets.rendering.colors.ColorMath;
import org.wynnvets.rendering.colors.Recolours;
import org.wynnvets.rendering.colors.Recolours.Outline;

/**
 * A {@code recipes} row: a named way one piece of art takes a colour, so that a row, or code, asks
 * for that art in a colour with the colour alone.
 *
 * @param art the texture id recoloured
 * @param kind how it takes the colour
 * @param from the colour of the art, {@code 0xRRGGBB}, that comes out as the colour; 0 for {@link
 *     Kind#MULTIPLY}
 */
public record Recipe(String art, Kind kind, int from) {

    /** The colour a multiply takes as the art's main: it is the one a tint leaves as it is. */
    private static final int WHITE = 0xFFFFFF;

    /** How a recipe's art takes a colour. */
    public enum Kind {
        /** Each channel is multiplied by the colour's: {@link Recolours#tint}. */
        MULTIPLY,
        /**
         * Each channel is scaled so {@code from} comes out as the colour: {@link Recolours#scale}.
         */
        SCALE,
        /**
         * As {@link #SCALE}, on the pixels of {@code from}'s hue alone: {@link Recolours#hueOnly}.
         */
        HUE_ONLY
    }

    /** The art alone: a copy of the file, or multiplied by a colour. */
    static Recipe multiply(String art) {
        return new Recipe(art, Kind.MULTIPLY, 0);
    }

    /**
     * The recipe a row's {@code scale_from} or {@code hue_of} asks of {@code art}; with neither,
     * {@link #multiply}.
     *
     * @throws IllegalArgumentException for both, a bad colour, or a {@code hue_of} without one
     *     strongest channel
     */
    static Recipe read(String art, Map<?, ?> fields, String where) {
        Object scaleFrom = fields.get("scale_from");
        Object hueOf = fields.get("hue_of");
        if (scaleFrom != null && hueOf != null) {
            throw new IllegalArgumentException(where + ": scale_from or hue_of, not both");
        }
        if (scaleFrom != null) {
            return new Recipe(
                    art, Kind.SCALE, ManifestYaml.colour(scaleFrom, where + ": scale_from"));
        }
        if (hueOf == null) return multiply(art);
        int from = ManifestYaml.colour(hueOf, where + ": hue_of");
        if (ColorMath.strongestChannel(from) < 0) {
            throw new IllegalArgumentException(
                    where + ": hue_of needs a colour with one strongest channel");
        }
        return new Recipe(art, Kind.HUE_ONLY, from);
    }

    /**
     * What this recipe does to a pixel of its art. A multiply and a scale leave the art's outline
     * undyed; a hue already passes over grey.
     *
     * @param colour the colour the art takes, {@code 0xRRGGBB}
     * @return the operator on one {@code 0xAARRGGBB} pixel
     */
    public IntUnaryOperator recolour(int colour) {
        return switch (kind) {
            case MULTIPLY -> keepingOutline(Recolours.tint(colour), WHITE);
            case SCALE -> keepingOutline(Recolours.scale(from, colour), from);
            case HUE_ONLY -> Recolours.hueOnly(from, colour);
        };
    }

    private static IntUnaryOperator keepingOutline(IntUnaryOperator recolour, int main) {
        return Recolours.withOutline(
                recolour, Outline.keepNeutralBelow(Recolours.outlineCutoff(main)));
    }
}
