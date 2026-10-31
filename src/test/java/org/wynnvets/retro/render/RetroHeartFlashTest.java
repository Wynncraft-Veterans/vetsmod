package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for when {@link RetroHeartFlash} flashes, and which row a flash shows. */
class RetroHeartFlashTest {

    private static final long TICK = 100;

    private static RetroHeartFlash startedAtFull() {
        RetroHeartFlash flash = new RetroHeartFlash();
        flash.update(1_000, 20, TICK, 0);
        return flash;
    }

    private static void assertFlashesOn(RetroHeartFlash flash, Set<Integer> ticksAfter, int upTo) {
        for (int after = 0; after <= upTo; after++) {
            assertEquals(
                    ticksAfter.contains(after), flash.flashing(TICK + after), "tick +" + after);
        }
    }

    @Test
    void theFirstFrameDoesNotFlash() {
        assertFlashesOn(startedAtFull(), Set.of(), 30);
    }

    @Test
    void aFallFlashesForASecondEveryThreeTicks() {
        RetroHeartFlash flash = startedAtFull();
        flash.update(900, 18, TICK, 50);
        assertFlashesOn(flash, Set.of(3, 4, 5, 9, 10, 11, 15, 16, 17), 30);
    }

    @Test
    void aRiseDoesNotFlash() {
        RetroHeartFlash flash = new RetroHeartFlash();
        flash.update(500, 10, TICK, 0);
        flash.update(600, 12, TICK, 50);
        assertFlashesOn(flash, Set.of(), 30);
    }

    @Test
    void healthThatLeavesTheRowAloneDoesNotFlash() {
        RetroHeartFlash flash = startedAtFull();
        flash.update(1_500, 20, TICK, 50);
        flash.update(1_100, 20, TICK, 100);
        assertFlashesOn(flash, Set.of(), 30);
    }

    @Test
    void aRowMovedByItsMaximumAloneDoesNotFlash() {
        RetroHeartFlash flash = startedAtFull();
        flash.update(1_000, 10, TICK, 50);
        flash.update(1_000, 20, TICK, 100);
        assertFlashesOn(flash, Set.of(), 30);
    }

    @Test
    void theRowFromBeforeAChangeIsKeptForASecondAfterTheLastOne() {
        RetroHeartFlash flash = startedAtFull();
        flash.update(800, 16, TICK, 500);
        assertEquals(20, flash.shownHalves(), "right after the change");
        flash.update(600, 12, TICK, 1_400);
        assertEquals(20, flash.shownHalves(), "a second change holds it longer");
        flash.update(600, 12, TICK, 2_400);
        assertEquals(20, flash.shownHalves(), "at exactly a second");
        flash.update(600, 12, TICK, 2_401);
        assertEquals(12, flash.shownHalves(), "past a second");
    }

    @Test
    void aResetForgetsTheRowAndAnyFlash() {
        RetroHeartFlash flash = startedAtFull();
        flash.update(500, 10, TICK, 50);
        flash.reset();
        assertFalse(flash.flashing(TICK + 3));

        flash.update(100, 2, TICK, 100);
        assertFlashesOn(flash, Set.of(), 30);
        assertEquals(2, flash.shownHalves());
    }
}
