package org.wynnvets.rendering.colors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for the two RGB lerps in this package — {@link GradientTextBuilder}'s
 * {@code interpolateRgb} and {@link AnimatedGradientSequence}'s
 * {@code interpolateColor} — plus their equivalence to each other.
 *
 * <p>These two and {@code NametagAnimator.interpolateColor} (covered by the
 * sibling {@code org.wynnvets.rendering.nametag.ColorLerpTest}) are genuinely
 * identical, so unifying them really is mechanical. What is <b>not</b>
 * mechanical is the shared quirk: <b>none of the three clamps {@code t}</b>.
 * Outside {@code [0, 1]} each channel rounds to a value outside {@code 0..255}
 * and the shift-and-or recombination lets it bleed into the neighbouring
 * channel, or — for a negative {@code t} — into the sign bit. A unified helper
 * that helpfully adds a clamp is a silent behaviour change, so the out-of-range
 * results are pinned as literals below.</p>
 *
 * <p>All three callers today feed {@code t} from a normalised progress value,
 * so the out-of-range cases are not reachable in production. They are pinned
 * anyway: "unreachable" is a property of the callers, and the point of
 * extracting a helper is that new callers can appear.</p>
 *
 * <p>NOTE, corrected by the verify pass — the obvious reading of this class's
 * loadability is wrong twice over. {@link AnimatedGradientSequence} does
 * <em>not</em> resolve {@link ShaderColorPalette} at runtime: {@code DARK_AQUA}
 * is a compile-time constant, so javac folds it into a {@code ConstantValue}
 * attribute and the palette class is never loaded. Its real static initializer
 * is one {@code ThreadLocal}. But the class <em>does</em> pull in Minecraft
 * regardless, because it {@code implements FormattedCharSequence} and the JVM
 * loads superinterfaces when it loads the class. That is harmless here only
 * because {@code build.gradle} puts the client runtime classpath on the test
 * source set — it is not the "no Minecraft at class-init" situation the sibling
 * {@code rendering.nametag.ColorLerpTest} genuinely has. Wynntils is absent from
 * both.</p>
 */
class ColorLerpTest {

    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    // ----- In-range behaviour -----

    @Test
    void endpointsReturnTheirInputColour() {
        assertEquals(BLACK, GradientTextBuilder.interpolateRgb(BLACK, WHITE, 0f));
        assertEquals(WHITE, GradientTextBuilder.interpolateRgb(BLACK, WHITE, 1f));
    }

    @Test
    void theMidpointRoundsHalfUp() {
        // 255 * 0.5 = 127.5, and Math.round is floor(x + 0.5), so each channel
        // lands on 128 rather than 127.
        assertEquals(0x808080, GradientTextBuilder.interpolateRgb(BLACK, WHITE, 0.5f));
        assertEquals(0x800000, GradientTextBuilder.interpolateRgb(BLACK, 0xFF0000, 0.5f));
    }

    @Test
    void channelsAreInterpolatedIndependently() {
        assertEquals(0x283848, GradientTextBuilder.interpolateRgb(0x102030, 0x405060, 0.5f));
    }

    @Test
    void anAlphaByteOnTheInputIsDiscarded() {
        // Each channel is masked to 8 bits after the shift, and the result is
        // reassembled without an alpha byte, so an ARGB input silently becomes
        // opaque-agnostic RGB.
        assertEquals(0x123456, GradientTextBuilder.interpolateRgb(0xFF123456, 0xFF123456, 0f));
        assertEquals(
                0x000000,
                GradientTextBuilder.interpolateRgb(0xFF000000, 0xFF000000, 1f),
                "the alpha bits never reach a channel");
    }

    // ----- No clamping: the shared quirk -----

    @Test
    void tAboveOneOverShootsAndBleedsIntoTheNeighbouringChannel() {
        // 255 * 1.5 = 382.5 → 383 (0x17F) per channel. Shifting a 9-bit value
        // into an 8-bit slot puts its top bit into the next channel up, so the
        // result is 0x017F7F7F rather than anything resembling white.
        assertEquals(0x017F7F7F, GradientTextBuilder.interpolateRgb(BLACK, WHITE, 1.5f));
        assertEquals(0x01FFFFFE, GradientTextBuilder.interpolateRgb(BLACK, WHITE, 2f));
    }

    @Test
    void tBelowZeroUnderShootsIntoNegativeChannelsAndTheSignBit() {
        // 255 * -0.5 = -127.5 → -127. A negative channel is all-ones in its
        // high bits, so the or-together collapses the whole word.
        assertEquals(0xFFFFFF81, GradientTextBuilder.interpolateRgb(BLACK, WHITE, -0.5f));
    }

    // ----- The two copies agree -----

    @Test
    void theTwoCopiesAgreeAcrossTheNormalRange() {
        for (int step = 0; step <= 100; step++) {
            float t = step / 100f;
            assertEquals(
                    GradientTextBuilder.interpolateRgb(0x6699BB, 0xDDF0FF, t),
                    AnimatedGradientSequence.interpolateColor(0x6699BB, 0xDDF0FF, t),
                    "t = " + t);
        }
    }

    @Test
    void theTwoCopiesAgreeOutsideTheNormalRangeToo() {
        // The equivalence a collapse relies on has to hold where the behaviour
        // is surprising, not just where it is sensible.
        for (float t : new float[] {-2f, -0.5f, 1.5f, 2f}) {
            assertEquals(
                    GradientTextBuilder.interpolateRgb(BLACK, WHITE, t),
                    AnimatedGradientSequence.interpolateColor(BLACK, WHITE, t),
                    "t = " + t);
        }
    }
}
