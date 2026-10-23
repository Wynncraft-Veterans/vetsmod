package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TimeLabels}: {@code humanizeAge} at every unit boundary, and
 * {@code toRelativeDateLabel} and {@code toCoarseRelativeDateLabel} on inputs that cannot straddle
 * a unit boundary on any calendar day.
 *
 * <p>The String overloads of the date labels read {@link LocalDate#now()} themselves, so each
 * assertion on them reads it before and after the call, and the test is skipped from that
 * assertion on if midnight passed in between. Their inputs are date strings, never instants, built
 * from the same default-zone date the labels read, so the zone cancels out.</p>
 *
 * <p>The overloads that take a {@link LocalDate} and an explicit {@code today}, and {@code
 * toLocalDate}, read no clock. They are tested with a fixed zone and a fixed today, including the
 * evening on which the UTC date is a day ahead of the local one.</p>
 */
class TimeLabelsTest {

    private static final long DAY = 86400L;

    /** UTC-3 all year, so an instant's local date is fixed whatever the machine's zone. */
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");

    private static final ZoneId UTC = ZoneId.of("UTC");

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

    // ----- toLocalDate -----

    @Test
    void toLocalDate_cutsTheInstantAtTheGivenZone() {
        Instant evening = Instant.parse("2026-10-22T01:07:00Z");
        assertEquals(LocalDate.of(2026, 10, 22), TimeLabels.toLocalDate(evening, UTC));
        assertEquals(LocalDate.of(2026, 10, 21), TimeLabels.toLocalDate(evening, BUENOS_AIRES));
        // 03:00Z is exactly local midnight in UTC-3.
        assertEquals(
                LocalDate.of(2026, 10, 21),
                TimeLabels.toLocalDate(Instant.parse("2026-10-22T02:59:59Z"), BUENOS_AIRES));
        assertEquals(
                LocalDate.of(2026, 10, 22),
                TimeLabels.toLocalDate(Instant.parse("2026-10-22T03:00:00Z"), BUENOS_AIRES));
    }

    // ----- both date labels against an explicit today -----

    @Test
    void toRelativeDateLabel_explicitToday() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        assertEquals("today", TimeLabels.toRelativeDateLabel(today, today));
        assertEquals("yesterday", TimeLabels.toRelativeDateLabel(today.minusDays(1), today));
        assertEquals("3 days ago", TimeLabels.toRelativeDateLabel(today.minusDays(3), today));
        assertEquals("tomorrow", TimeLabels.toRelativeDateLabel(today.plusDays(1), today));
        assertEquals("in 3 days", TimeLabels.toRelativeDateLabel(today.plusDays(3), today));
    }

    @Test
    void toRelativeDateLabel_lateEveningJoinReadsToday() {
        // Seen in game: a join at 01:07 UTC on 22 October, checked at 22:07 on 21 October in
        // UTC-3, read "tomorrow" because the UTC date was compared with the local today.
        Instant join = Instant.parse("2026-10-22T01:07:00Z");
        LocalDate localToday = LocalDate.of(2026, 10, 21);
        LocalDate localDate = TimeLabels.toLocalDate(join, BUENOS_AIRES);
        assertEquals("today", TimeLabels.toRelativeDateLabel(localDate, localToday));
        // The next local day it reads "yesterday", where the UTC date said "today".
        assertEquals(
                "yesterday", TimeLabels.toRelativeDateLabel(localDate, localToday.plusDays(1)));
    }

    @Test
    void toCoarseRelativeDateLabel_explicitTodayYearsMonthsThenDays() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        assertEquals(
                "1 year ago", TimeLabels.toCoarseRelativeDateLabel(today.minusYears(1), today));
        assertEquals(
                "11 years ago", TimeLabels.toCoarseRelativeDateLabel(today.minusYears(11), today));
        assertEquals(
                "1 month ago", TimeLabels.toCoarseRelativeDateLabel(today.minusMonths(1), today));
        assertEquals(
                "5 months ago", TimeLabels.toCoarseRelativeDateLabel(today.minusMonths(5), today));
        assertEquals(
                "27 days ago", TimeLabels.toCoarseRelativeDateLabel(today.minusDays(27), today));
        assertEquals("today", TimeLabels.toCoarseRelativeDateLabel(today, today));
    }

    @Test
    void toCoarseRelativeDateLabel_localAnniversaryReadsTheFullYear() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        // 22:00 on 13 October 2015 in UTC-3 is 01:00 UTC on 14 October: cut at UTC, the date is a
        // day short of the anniversary and reads 10 years.
        Instant firstJoin = Instant.parse("2015-10-14T01:00:00Z");
        assertEquals(
                "11 years ago",
                TimeLabels.toCoarseRelativeDateLabel(
                        TimeLabels.toLocalDate(firstJoin, BUENOS_AIRES), today));
        assertEquals(
                "10 years ago",
                TimeLabels.toCoarseRelativeDateLabel(
                        TimeLabels.toLocalDate(firstJoin, UTC), today));
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
