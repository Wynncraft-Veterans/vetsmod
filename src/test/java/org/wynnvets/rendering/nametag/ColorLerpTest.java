package org.wynnvets.rendering.nametag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NametagAnimator}'s {@code interpolateColor} — the third copy
 * of the RGB lerp, and the only one that was already package-private.
 *
 * <p>It is byte-identical to the two in {@code org.wynnvets.rendering.colors}
 * (see the sibling {@code ColorLerpTest} there) apart from a local variable
 * name, so unifying them is mechanical. The assertions here are the same
 * fixtures deliberately, so that a unification which changes one copy and not
 * the others fails on both sides rather than one.</p>
 *
 * <p>The shared quirk this exists to protect: <b>no clamping of {@code t}</b>.
 * Outside {@code [0, 1]} each channel leaves the {@code 0..255} range and the
 * shift-and-or recombination bleeds it into the neighbouring channel or the
 * sign bit. Adding a clamp during a de-duplication is a silent behaviour
 * change.</p>
 *
 * <p>NOTE: {@link NametagAnimator}'s static state is {@code int} and
 * {@code float} constants only, so nothing loads during class-init even though
 * the class renders.</p>
 */
class ColorLerpTest {

    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    // ----- In-range behaviour -----

    @Test
    void endpointsReturnTheirInputColour() {
        assertEquals(BLACK, NametagAnimator.interpolateColor(BLACK, WHITE, 0f));
        assertEquals(WHITE, NametagAnimator.interpolateColor(BLACK, WHITE, 1f));
    }

    @Test
    void theMidpointRoundsHalfUp() {
        assertEquals(0x808080, NametagAnimator.interpolateColor(BLACK, WHITE, 0.5f));
        assertEquals(0x800000, NametagAnimator.interpolateColor(BLACK, 0xFF0000, 0.5f));
    }

    @Test
    void channelsAreInterpolatedIndependently() {
        assertEquals(0x283848, NametagAnimator.interpolateColor(0x102030, 0x405060, 0.5f));
    }

    @Test
    void anAlphaByteOnTheInputIsDiscarded() {
        assertEquals(0x123456, NametagAnimator.interpolateColor(0xFF123456, 0xFF123456, 0f));
    }

    // ----- No clamping -----

    @Test
    void tAboveOneOverShootsAndBleedsIntoTheNeighbouringChannel() {
        assertEquals(0x017F7F7F, NametagAnimator.interpolateColor(BLACK, WHITE, 1.5f));
        assertEquals(0x01FFFFFE, NametagAnimator.interpolateColor(BLACK, WHITE, 2f));
    }

    @Test
    void tBelowZeroUnderShootsIntoNegativeChannelsAndTheSignBit() {
        assertEquals(0xFFFFFF81, NametagAnimator.interpolateColor(BLACK, WHITE, -0.5f));
    }
}
