package org.wynnvets.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void minutesSeconds_padsTheSecondsAndNotTheMinutes() {
        assertEquals("0:00", Durations.minutesSeconds(0));
        assertEquals("0:07", Durations.minutesSeconds(7));
        assertEquals("1:00", Durations.minutesSeconds(60));
        assertEquals("3:25", Durations.minutesSeconds(205));
    }

    @Test
    void minutesSeconds_doesNotRollOverIntoHours() {
        assertEquals("72:01", Durations.minutesSeconds(4321));
    }

    @Test
    void hoursMinutes_padsTheMinutesAndDropsTheSeconds() {
        assertEquals("0h 05m", Durations.hoursMinutes(359));
        assertEquals("16h 30m", Durations.hoursMinutes(59_400));
        assertEquals("0h 00m", Durations.hoursMinutes(-5));
    }

    @Test
    void minutesSeconds_readsATimeBelowZeroAsZero() {
        assertEquals("0:00", Durations.minutesSeconds(-5));
    }
}
