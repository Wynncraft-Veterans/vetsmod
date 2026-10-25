package org.wynnvets.fetcher.ondemand;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Relative date and age labels: "today", "3 days ago", "11 years ago", "5m".
 *
 * <p>The date labels compare a calendar date with a "today" in one time zone. The {@link
 * LocalDate} overloads take both, so a caller holding an {@link Instant} can cut it to the
 * viewer's local date with {@link #toLocalDate} and compare it with the viewer's own today. The
 * {@link String} overloads read {@link LocalDate#now()} (today in the default time zone) and are
 * the fallback for a date the caller could only read as text.</p>
 */
final class TimeLabels {

    private TimeLabels() {}

    /**
     * The calendar date {@code instant} falls on in {@code zone}. The {@code /wv check} readout
     * passes the viewer's zone, so the date and the label agree with the viewer's own calendar.
     */
    static LocalDate toLocalDate(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate();
    }

    /**
     * Relative label for a date given as text, against today in the default time zone. Returns the
     * text unchanged when it does not parse as an ISO date.
     */
    static String toRelativeDateLabel(String dateText) {
        LocalDate date;

        try {
            date = LocalDate.parse(dateText);
        } catch (RuntimeException ignored) {
            return dateText;
        }

        return toRelativeDateLabel(date, LocalDate.now());
    }

    /**
     * Relative label for {@code date} against an explicit {@code today}: "today", "yesterday", "N
     * days ago", and for a date ahead of {@code today} "tomorrow" or "in N days".
     */
    static String toRelativeDateLabel(LocalDate date, LocalDate today) {
        long days = ChronoUnit.DAYS.between(date, today);
        if (days == 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        if (days > 1) {
            return days + " days ago";
        }

        long daysUntil = Math.abs(days);
        if (daysUntil == 1) {
            return "tomorrow";
        }
        return "in " + daysUntil + " days";
    }

    /**
     * Coarse-grained relative date label: years > months > days, picking the
     * coarsest unit that yields a value ≥ 1. Falls back to {@link
     * #toRelativeDateLabel} for sub-month spans. Used for "first seen" where
     * "4342 days ago" is unreadable but "11 years ago" is at-a-glance useful. Takes the date as
     * text and reads today from the default time zone; returns the text unchanged when it does not
     * parse.
     */
    static String toCoarseRelativeDateLabel(String dateText) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (RuntimeException ignored) {
            return dateText;
        }
        return toCoarseRelativeDateLabel(date, LocalDate.now());
    }

    /** The coarse label for {@code date} against an explicit {@code today}. */
    static String toCoarseRelativeDateLabel(LocalDate date, LocalDate today) {
        long years = ChronoUnit.YEARS.between(date, today);
        if (years >= 1) {
            return years == 1 ? "1 year ago" : years + " years ago";
        }
        long months = ChronoUnit.MONTHS.between(date, today);
        if (months >= 1) {
            return months == 1 ? "1 month ago" : months + " months ago";
        }
        return toRelativeDateLabel(date, today);
    }

    /**
     * Coarse "age" formatter for "{@code N ago}" presentations of a duration
     * in seconds. Picks the coarsest unit that yields ≥ 1: years > months >
     * days > hours > minutes > seconds. Used for best-guess timestamps where
     * we have unix seconds (not an ISO date) — e.g. dazebot's
     * {@code last_seen_observed.ts}.
     */
    static String humanizeAge(long secs) {
        if (secs < 0) secs = 0;
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        if (secs < 86400) return (secs / 3600) + "h";
        if (secs < 86400L * 30) return (secs / 86400) + "d";
        if (secs < 86400L * 365) {
            long m = secs / (86400L * 30);
            return m + (m == 1 ? " month" : " months");
        }
        long y = secs / (86400L * 365);
        return y + (y == 1 ? " year" : " years");
    }
}
