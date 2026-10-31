package org.wynnvets.rendering.colors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ColorMath}: its channel helpers, luma, strongest channel and interpolation.
 *
 * <p>The interpolation's literals are the point of the class. <b>{@code interpolateRgb} does not
 * clamp {@code t}</b>: outside {@code [0, 1]} each channel rounds past {@code 0..255} and the
 * shift-and-or recombination bleeds it into the neighbouring channel or the sign bit. The
 * out-of-range tests make adding a clamp a loud change.
 *
 * <p>{@link ColorMath} has no supertypes, no static fields and no Minecraft imports, so this class
 * loads zero Minecraft.
 */
class ColorMathTest {

    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    // ----- In-range behaviour -----

    @Test
    void endpointsReturnTheirInputColour() {
        assertEquals(BLACK, ColorMath.interpolateRgb(BLACK, WHITE, 0f));
        assertEquals(WHITE, ColorMath.interpolateRgb(BLACK, WHITE, 1f));
    }

    @Test
    void theMidpointRoundsHalfUp() {
        // 255 * 0.5 = 127.5, and Math.round is floor(x + 0.5), so each channel
        // lands on 128 rather than 127.
        assertEquals(0x808080, ColorMath.interpolateRgb(BLACK, WHITE, 0.5f));
        assertEquals(0x800000, ColorMath.interpolateRgb(BLACK, 0xFF0000, 0.5f));
    }

    @Test
    void channelsAreInterpolatedIndependently() {
        assertEquals(0x283848, ColorMath.interpolateRgb(0x102030, 0x405060, 0.5f));
    }

    @Test
    void anAlphaByteOnTheInputIsDiscarded() {
        // Each channel is masked to 8 bits after the shift, and the result is
        // reassembled without an alpha byte, so an ARGB input silently becomes
        // opaque-agnostic RGB.
        assertEquals(0x123456, ColorMath.interpolateRgb(0xFF123456, 0xFF123456, 0f));
        assertEquals(
                0x000000,
                ColorMath.interpolateRgb(0xFF000000, 0xFF000000, 1f),
                "the alpha bits never reach a channel");
    }

    // ----- No clamping: the quirk carried forward verbatim -----

    @Test
    void tAboveOneOverShootsAndBleedsIntoTheNeighbouringChannel() {
        // 255 * 1.5 = 382.5 → 383 (0x17F) per channel. Shifting a 9-bit value
        // into an 8-bit slot puts its top bit into the next channel up, so the
        // result is 0x017F7F7F rather than anything resembling white.
        assertEquals(0x017F7F7F, ColorMath.interpolateRgb(BLACK, WHITE, 1.5f));
        assertEquals(0x01FFFFFE, ColorMath.interpolateRgb(BLACK, WHITE, 2f));
    }

    @Test
    void tBelowZeroUnderShootsIntoNegativeChannelsAndTheSignBit() {
        // 255 * -0.5 = -127.5 → -127. A negative channel is all-ones in its
        // high bits, so the or-together collapses the whole word.
        assertEquals(0xFFFFFF81, ColorMath.interpolateRgb(BLACK, WHITE, -0.5f));
    }

    // ----- Double precision -----

    @Test
    void theDoubleOverloadRoundsLikeTheFloatOneInRange() {
        assertEquals(0x808080, ColorMath.interpolateRgb(BLACK, WHITE, 0.5));
        assertEquals(0x283848, ColorMath.interpolateRgb(0x102030, 0x405060, 0.5));
        assertEquals(WHITE, ColorMath.interpolateRgb(BLACK, WHITE, 1.0));
    }

    @Test
    void theDoubleOverloadDoesNotClampEither() {
        assertEquals(0x017F7F7F, ColorMath.interpolateRgb(BLACK, WHITE, 1.5));
        assertEquals(0xFFFFFF81, ColorMath.interpolateRgb(BLACK, WHITE, -0.5));
    }

    @Test
    void theDoubleOverloadDropsAnAlphaByte() {
        assertEquals(0x123456, ColorMath.interpolateRgb(0xFF123456, 0xFF123456, 0.25));
    }

    // ----- Channels -----

    @Test
    void channelsReadTheirOwnEightBits() {
        assertEquals(0x80, ColorMath.alpha(0x80123456));
        assertEquals(0xFF, ColorMath.alpha(0xFF123456), "the sign bit does not spread");
        assertEquals(0x12, ColorMath.red(0xFF123456));
        assertEquals(0x34, ColorMath.green(0xFF123456));
        assertEquals(0x56, ColorMath.blue(0xFF123456));
        assertEquals(0x34, ColorMath.channel(0xFF123456, 8));
    }

    @Test
    void rgbPacksTheChannels() {
        assertEquals(0x123456, ColorMath.rgb(0x12, 0x34, 0x56));
    }

    @Test
    void lumaWeighsTheChannelsAndIgnoresAlpha() {
        assertEquals(255, ColorMath.luma(0xFFFFFF), 1e-9);
        assertEquals(0.299 * 255, ColorMath.luma(0xFF0000), 1e-9);
        assertEquals(0.587 * 255, ColorMath.luma(0x00FF00), 1e-9);
        assertEquals(0.114 * 255, ColorMath.luma(0x0000FF), 1e-9);
        assertEquals(ColorMath.luma(0x123456), ColorMath.luma(0x80123456));
    }

    @Test
    void theStrongestChannelIsItsShiftOrMinusOneOnATie() {
        assertEquals(16, ColorMath.strongestChannel(0x804020));
        assertEquals(8, ColorMath.strongestChannel(0x408020));
        assertEquals(0, ColorMath.strongestChannel(0x204080));
        assertEquals(-1, ColorMath.strongestChannel(0x808080), "a grey");
        assertEquals(-1, ColorMath.strongestChannel(0x808000), "red and green tie");
        assertEquals(-1, ColorMath.strongestChannel(0x800080), "red and blue tie");
        assertEquals(-1, ColorMath.strongestChannel(0x008080), "green and blue tie");
        assertEquals(16, ColorMath.strongestChannel(0xFF800000), "alpha is ignored");
    }
}
