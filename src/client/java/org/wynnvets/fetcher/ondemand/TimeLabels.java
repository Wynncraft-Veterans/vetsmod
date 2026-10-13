package org.wynnvets.fetcher.ondemand;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Relative date and age labels: "today", "3 days ago", "11 years ago", "5m".
 * {@link #toRelativeDateLabel} and {@link #toCoarseRelativeDateLabel} read the clock: they compare
 * a date with {@link LocalDate#now()}, which is today in the default time zone
 * ({@code wv-check-date-labels-mix-utc-dates-with-local-today}).
 */
final class TimeLabels {

    private TimeLabels() {}

    static String toRelativeDateLabel(String dateText) {
        LocalDate date;

        try {
            date = LocalDate.parse(dateText);
        } catch (RuntimeException ignored) {
            return dateText;
        }

        long days = ChronoUnit.DAYS.between(date, LocalDate.now());
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
     * "4342 days ago" is unreadable but "11 years ago" is at-a-glance useful.
     */
    static String toCoarseRelativeDateLabel(String dateText) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (RuntimeException ignored) {
            return dateText;
        }
        LocalDate now = LocalDate.now();
        long years = ChronoUnit.YEARS.between(date, now);
        if (years >= 1) {
            return years == 1 ? "1 year ago" : years + " years ago";
        }
        long months = ChronoUnit.MONTHS.between(date, now);
        if (months >= 1) {
            return months == 1 ? "1 month ago" : months + " months ago";
        }
        return toRelativeDateLabel(dateText);
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
