package org.wynnvets.util;

import java.util.Locale;

/** Spells a stretch of time for a player to read. */
public final class Durations {

    private Durations() {}

    /**
     * A stopwatch reading: {@code 0:07}, {@code 3:25}, {@code 72:01}. The minutes do not roll over
     * into hours.
     *
     * @param seconds the time in seconds; below zero reads as {@code 0:00}
     * @return the reading
     */
    public static String minutesSeconds(long seconds) {
        long shown = Math.max(0L, seconds);
        return String.format(Locale.ROOT, "%d:%02d", shown / 60L, shown % 60L);
    }

    /**
     * A wait, to the minute: {@code 0h 05m}, {@code 16h 30m}.
     *
     * @param seconds the time in seconds; below zero reads as {@code 0h 00m}
     * @return the reading
     */
    public static String hoursMinutes(long seconds) {
        long minutes = Math.max(0L, seconds) / 60L;
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60L, minutes % 60L);
    }
}
