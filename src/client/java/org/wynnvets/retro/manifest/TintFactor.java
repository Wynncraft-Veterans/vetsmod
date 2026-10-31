package org.wynnvets.retro.manifest;

/**
 * How a {@code tints} row finds the factor of each channel. Every mean is over the visible pixels
 * of a texture, channel by channel; a texture id may hold {@code $n} as in the row's
 * {@code source}.
 */
public sealed interface TintFactor {

    /** The {@code tints} value that matches Wynncraft's live texture: {@link Auto}. */
    String AUTO = "auto";

    /**
     * No {@code tint}, in a row that only changes the contrast: every factor is 1, so the source
     * keeps its colour.
     */
    record Unchanged() implements TintFactor {}

    /**
     * {@code '#RRGGBB'}: the colour's channels over 255, as Minecraft's own tints multiply.
     *
     * @param rgb the colour, {@code 0xRRGGBB}
     */
    record Colour(int rgb) implements TintFactor {}

    /**
     * {@code {match: <texture>}}: the mean of {@code texture}, read from its own sources as a copy
     * would be, over the mean of the source, so the source takes that texture's colour.
     *
     * @param texture the texture id matched
     */
    record Match(String texture) implements TintFactor {}

    /**
     * {@code {relative_to: <texture>}}: the mean of Wynncraft's live copy of the replaced texture
     * over the mean of Wynncraft's live {@code texture}, so the source changes as Wynncraft changed
     * its own texture from that one.
     *
     * @param texture the texture id Wynncraft's replaced texture is measured against
     */
    record RelativeTo(String texture) implements TintFactor {}

    /**
     * {@code auto}: the mean of Wynncraft's live copy of the replaced texture over the mean of the
     * source, so the source takes the colour Wynncraft's texture has.
     */
    record Auto() implements TintFactor {}

    /**
     * {@code {like: <texture>}}: exactly the change the {@code tints} row matching {@code texture}
     * makes to that texture, applied to the source: that row's factors at its strength, measured on
     * its own images, then its contrast around the mean of {@code texture} after those factors
     * rather than the source's own. A pixel the source shares with {@code texture}'s art therefore
     * comes out as it does in {@code texture}. Exactly one row matches {@code texture}, and it is
     * no {@code like} itself.
     *
     * @param texture the texture id whose row's change is applied, a literal id
     */
    record Like(String texture) implements TintFactor {}
}
