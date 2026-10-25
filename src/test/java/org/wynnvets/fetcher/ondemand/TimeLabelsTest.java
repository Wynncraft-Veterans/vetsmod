package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TimeLabels}: {@code humanizeAge} at every unit boundary, and
 * {@code toRelativeDateLabel} and {@code toCoarseRelativeDateLabel} on inputs that cannot straddle
 * a unit boundary on any calendar day.
 *
 * <p>The date labels read {@link LocalDate#now()} themselves, so each date assertion reads it
 * before and after the call, and the test is skipped from that assertion on if midnight passed in
 * between. The inputs are date strings, never instants, built from the same default-zone date the
 * labels read, so the zone cancels out and no instant is ever cut to a date.</p>
 */
class TimeLabelsTest {

    private static final long DAY = 86400L;

    // ----- humanizeAge -----

    @Test
    void humanizeAge_unitBoundaries() {
        assertEquals("0s", TimeLabels.humanizeAge(0));
        assertEquals("59s", TimeLabels.humanizeAge(59));
        assertEquals("1m", TimeLabels.humanizeAge(60));
        assertEquals("59m", TimeLabels.humanizeAge(3599));
        assertEquals("1h", TimeLabels.humanizeAge(3600));
        assertEquals("23h", TimeLabels.humanizeAge(DAY - 1));
        assertEquals("1d", TimeLabels.humanizeAge(DAY));
        assertEquals("29d", TimeLabels.humanizeAge(DAY * 30 - 1));
        assertEquals("1 month", TimeLabels.humanizeAge(DAY * 30));
        assertEquals("2 months", TimeLabels.humanizeAge(DAY * 60));
        assertEquals("12 months", TimeLabels.humanizeAge(DAY * 365 - 1));
        assertEquals("1 year", TimeLabels.humanizeAge(DAY * 365));
        assertEquals("2 years", TimeLabels.humanizeAge(DAY * 730));
    }

    @Test
    void humanizeAge_negativeReadsAsZero() {
        assertEquals("0s", TimeLabels.humanizeAge(-5));
    }

    // ----- toRelativeDateLabel -----

    @Test
    void toRelativeDateLabel_todayPastAndFutureDays() {
        assertLabel("today", d -> d, TimeLabels::toRelativeDateLabel);
        assertLabel("yesterday", d -> d.minusDays(1), TimeLabels::toRelativeDateLabel);
        assertLabel("3 days ago", d -> d.minusDays(3), TimeLabels::toRelativeDateLabel);
        assertLabel("tomorrow", d -> d.plusDays(1), TimeLabels::toRelativeDateLabel);
        assertLabel("in 3 days", d -> d.plusDays(3), TimeLabels::toRelativeDateLabel);
    }

    // ----- toCoarseRelativeDateLabel -----

    @Test
    void toCoarseRelativeDateLabel_yearsMonthsThenDays() {
        assertLabel("1 year ago", d -> d.minusYears(1), TimeLabels::toCoarseRelativeDateLabel);
        assertLabel("11 years ago", d -> d.minusYears(11), TimeLabels::toCoarseRelativeDateLabel);
        assertLabel("1 month ago", d -> d.minusMonths(1), TimeLabels::toCoarseRelativeDateLabel);
        assertLabel("5 months ago", d -> d.minusMonths(5), TimeLabels::toCoarseRelativeDateLabel);
        // At most 27 days is under a month on every calendar day, so it falls through to days.
        assertLabel("27 days ago", d -> d.minusDays(27), TimeLabels::toCoarseRelativeDateLabel);
        assertLabel("yesterday", d -> d.minusDays(1), TimeLabels::toCoarseRelativeDateLabel);
    }

    // ----- both date labels -----

    @Test
    void dateLabels_returnUnparseableTextAsIs() {
        assertEquals("not-a-date", TimeLabels.toRelativeDateLabel("not-a-date"));
        assertEquals("not-a-date", TimeLabels.toCoarseRelativeDateLabel("not-a-date"));
    }

    private static void assertLabel(
            String want, Function<LocalDate, LocalDate> from, Function<String, String> label) {
        LocalDate before = LocalDate.now();
        String got = label.apply(from.apply(before).toString());
        assumeTrue(before.equals(LocalDate.now()), "midnight passed during the call");
        assertEquals(want, got);
    }
}
