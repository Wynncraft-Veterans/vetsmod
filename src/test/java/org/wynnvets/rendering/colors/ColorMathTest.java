package org.wynnvets.rendering.colors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ColorMath#interpolateRgb} — the one surviving RGB lerp.
 *
 * <p>It replaces two classes that pinned three copies: {@code rendering.colors.ColorLerpTest}
 * (eight tests over {@code GradientTextBuilder} and {@code AnimatedGradientSequence}, two of
 * them cross-copy equivalence loops) and {@code rendering.nametag.ColorLerpTest} (six over
 * {@code NametagAnimator}, a strict subset). The six kept here are the {@code colors}
 * superset; the equivalence loops are not carried over, because with one copy left there is
 * nothing to compare it to.</p>
 *
 * <p>The literals below are the point of the class. <b>{@code interpolateRgb} does not clamp
 * {@code t}</b>: outside {@code [0, 1]} each channel rounds past {@code 0..255} and the
 * shift-and-or recombination bleeds it into the neighbouring channel or the sign bit. That
 * quirk was shared by all three original copies, so a unification that helpfully added a clamp
 * would be a silent behaviour change. The two out-of-range tests are what make it a loud
 * one.</p>
 *
 * <p>NOTE, and it is a harness improvement worth recording: {@link ColorMath} has no
 * supertypes, no static fields and no Minecraft imports, so <b>this class loads zero
 * Minecraft</b>. Neither predecessor could say that. {@code colors.ColorLerpTest} dragged in
 * {@code FormattedCharSequence} — {@code AnimatedGradientSequence} implements it, and the JVM
 * loads superinterfaces with the class — and worked only because {@code build.gradle} puts the
 * client runtime classpath on the test source set. ({@code nametag.ColorLerpTest} did already
 * load none: {@code NametagAnimator} has no supertypes and its static state is {@code int} and
 * {@code float} constants.)</p>
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
}
