package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.render.RetroHudReadings.Bar;
import org.wynnvets.retro.render.RetroHudReadings.Value;

/**
 * Tests for {@link RetroHudReadings}: what each action bar means for the armour and bubble rows and
 * the numbers, on plain values, with a bar heard at time 0 unless a test says otherwise.
 */
class RetroHudReadingsTest {

    private static final Value SPRINT_FULL = new Value(25, 25);
    private static final Value ULTIMATE_RIM_FULL = new Value(32, 32);
    private static final Value SPRINT_DOWN = new Value(24, 25);
    private static final Value METER_EMPTY = new Value(0, 0);

    private RetroHudReadings readings;

    @BeforeEach
    void setUp() {
        readings = new RetroHudReadings();
        readings.heard(0);
    }

    private static Bar meter(Value meter) {
        return new Bar(meter, false, false, null, false, null, null);
    }

    private static Bar charge(int frame) {
        return new Bar(null, false, false, new Value(frame, 22), false, null, null);
    }

    private static Bar plain() {
        return new Bar(null, false, false, null, false, null, null);
    }

    @Test
    void nothingIsDrawnBeforeABarIsRead() {
        assertEquals(RetroHudReadings.NO_ROW, readings.armourHalves(0));
        assertEquals(RetroHudReadings.NO_ROW, readings.bubbles(0));
        assertNull(readings.healthNumber(0));
        assertNull(readings.manaNumber(0));
    }

    @Test
    void aFullMeterDrawsNoArmourRow() {
        readings.update(meter(SPRINT_FULL));
        assertEquals(RetroHudReadings.NO_ROW, readings.armourHalves(0));

        readings.update(meter(ULTIMATE_RIM_FULL));
        assertEquals(RetroHudReadings.NO_ROW, readings.armourHalves(0), "the ultimate's rim too");
    }

    @Test
    void aMeterBelowFullDrawsItsShareOfTheRow() {
        readings.update(meter(SPRINT_DOWN));
        assertEquals(19, readings.armourHalves(0), "a step lost takes a half");

        readings.update(meter(new Value(1, 25)));
        assertEquals(1, readings.armourHalves(0), "never empty while anything is left");
    }

    @Test
    void anEmptyMeterDrawsAnEmptyRow() {
        readings.update(meter(METER_EMPTY));
        assertEquals(0, readings.armourHalves(0));
    }

    @Test
    void theMeterHoldsThroughItsAnimationButNotOtherwise() {
        readings.update(meter(SPRINT_DOWN));

        readings.update(new Bar(null, true, false, null, false, null, null));
        assertEquals(19, readings.armourHalves(0), "an animation frame holds it");

        readings.update(plain());
        assertEquals(
                RetroHudReadings.NO_ROW, readings.armourHalves(0), "no meter and no animation");
    }

    @Test
    void aRimWithAValueBeatsTheEmptyGlyphBesideIt() {
        assertEquals(
                new Value(20, 32),
                RetroHudReadings.shownMeter(List.of(METER_EMPTY, new Value(20, 32))),
                "the normal rim's empty glyph ahead of the ultimate's rim");
        assertEquals(
                ULTIMATE_RIM_FULL,
                RetroHudReadings.shownMeter(List.of(ULTIMATE_RIM_FULL, METER_EMPTY)),
                "in either order");
        assertEquals(
                new Value(20, 32),
                RetroHudReadings.shownMeter(List.of(SPRINT_FULL, new Value(20, 32))),
                "otherwise the emptiest");
        assertEquals(METER_EMPTY, RetroHudReadings.shownMeter(List.of(METER_EMPTY)), "truly empty");
        assertNull(RetroHudReadings.shownMeter(List.of()), "no rim");
    }

    @Test
    void chargeFillsBubblesRoundedDownButAtLeastOne() {
        readings.update(charge(0));
        assertEquals(0, readings.bubbles(0), "the ultimate shows, uncharged");

        readings.update(charge(1));
        assertEquals(1, readings.bubbles(0), "any charge takes a bubble");

        readings.update(charge(11));
        assertEquals(5, readings.bubbles(0));

        readings.update(charge(21));
        assertEquals(9, readings.bubbles(0), "its last frame is not yet ready");
    }

    @Test
    void onlyAReadyUltimateFillsTheRowEvenOverItsLastFrame() {
        readings.update(new Bar(null, false, true, new Value(21, 22), false, null, null));
        assertEquals(RetroHudReadings.BUBBLES, readings.bubbles(0));
    }

    @Test
    void aChargeWithNoMaximumDrawsNoBubbles() {
        readings.update(new Bar(null, false, false, new Value(0, 0), false, null, null));
        assertEquals(RetroHudReadings.NO_ROW, readings.bubbles(0));
    }

    @Test
    void aChargeThatCannotBeReadEmptiesARowThatWasReady() {
        readings.update(new Bar(null, false, true, null, false, null, null));

        readings.update(new Bar(null, false, false, new Value(0, 0), false, null, null));

        assertEquals(RetroHudReadings.NO_ROW, readings.bubbles(0), "not ten through a recharge");
    }

    @Test
    void theLevelTakesTheBubblesAwayAndOtherFramesHoldThem() {
        readings.update(charge(11));

        readings.update(plain());
        assertEquals(5, readings.bubbles(0), "a frame showing neither holds them");

        readings.update(new Bar(null, false, false, null, true, null, null));
        assertEquals(RetroHudReadings.NO_ROW, readings.bubbles(0));
    }

    @Test
    void aBarWithoutANumberTakesItAway() {
        readings.update(new Bar(null, false, false, null, false, "health", "mana"));
        assertEquals("health", readings.healthNumber(0));
        assertEquals("mana", readings.manaNumber(0));

        readings.update(new Bar(null, false, false, null, false, null, "mana"));
        assertNull(readings.healthNumber(0));
        assertEquals("mana", readings.manaNumber(0));
    }

    @Test
    void clearForgetsEverythingUntilABarIsHeardAgain() {
        readings.update(new Bar(SPRINT_DOWN, false, false, new Value(11, 22), false, "h", "m"));

        readings.clear();

        readings.heard(0);
        assertEquals(RetroHudReadings.NO_ROW, readings.armourHalves(0));
        assertEquals(RetroHudReadings.NO_ROW, readings.bubbles(0));
        assertNull(readings.healthNumber(0));
        assertNull(readings.manaNumber(0));
    }

    @Test
    void readingsDropOutOnceNoBarHasArrivedForABarsLife() {
        readings.update(new Bar(SPRINT_DOWN, false, false, new Value(11, 22), false, "h", "m"));
        long life = RetroHudReadings.BAR_LIFE_MILLIS;

        assertEquals(19, readings.armourHalves(life));
        assertEquals(5, readings.bubbles(life));
        assertEquals("h", readings.healthNumber(life));
        assertEquals("m", readings.manaNumber(life));

        assertEquals(RetroHudReadings.NO_ROW, readings.armourHalves(life + 1));
        assertEquals(RetroHudReadings.NO_ROW, readings.bubbles(life + 1));
        assertNull(readings.healthNumber(life + 1));
        assertNull(readings.manaNumber(life + 1));

        readings.heard(life + 1);
        assertEquals(19, readings.armourHalves(life + 1), "an unchanged bar brings them back");
    }
}
