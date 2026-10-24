package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the line a staff commit gets when its outcome is unknown (its socket was lost, or no reply
 * came in time): it says to check before retrying, and for an eject to act by hand.
 */
class CautionCommandsTest {

    @Test
    void aCautionOrWarningWithAnUnknownOutcomeSaysToCheckBeforeRetrying() {
        assertEquals(
                "[warning] Outcome unknown (connection lost) - run /wv check Steve before retrying",
                CautionCommands.unknownOutcomeLine("warning", "Steve", "connection lost"));
        assertEquals(
                "[caution] Outcome unknown (no reply) - run /wv check Steve before retrying",
                CautionCommands.unknownOutcomeLine("caution", "Steve", "no reply"));
    }

    @Test
    void anEjectWithAnUnknownOutcomeSaysToActByHand() {
        assertEquals(
                "[eject] Outcome unknown (no reply) - run /wv check Steve; if it went through, kick"
                        + " or demote them yourself",
                CautionCommands.unknownOutcomeLine("eject", "Steve", "no reply"));
    }
}
