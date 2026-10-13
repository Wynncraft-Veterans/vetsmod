package org.wynnvets.util;

import java.util.Locale;
import java.util.UUID;

/**
 * UUID text helpers. Each method keeps its own contract, and the contracts differ on purpose:
 *
 * <ul>
 *   <li>{@link #parse} is lenient: it trims, re-dashes a 32-character input, and returns what
 *       {@link UUID#fromString} accepts, or {@code null} for {@code null}, blank or rejected
 *       input.</li>
 *   <li>{@link #normalizeUndashed} does not validate. It strips every dash and lowercases, so the
 *       dashed and undashed spellings of one UUID compare equal as text; any other text is changed
 *       the same way, and {@code null} becomes {@code ""}.</li>
 *   <li>{@link #isUndashedLowercase} says whether a string is exactly the form
 *       {@link #normalizeUndashed} gives a UUID.</li>
 *   <li>{@link #toUndashed} spells a {@link UUID} without its dashes.</li>
 * </ul>
 *
 * <p>The forms are opposite: {@link UUID#toString()} is dashed lowercase, so
 * {@code parse(s).toString()} and {@code normalizeUndashed(s)} never spell one UUID the same
 * way.</p>
 *
 * <p>Formerly {@code fetcher.lookup.Uuids}. {@link #normalizeUndashed} replaces
 * {@code NameResolver.normalizeUuid} and {@code UserInfoFetcher.normalizeUuidText}, and
 * {@link #isUndashedLowercase} replaces {@code UserInfoFetcher.isUuidLike}.</p>
 */
public final class Uuids {

    private Uuids() {}

    /**
     * Parses either a dashed or undashed UUID string. Returns null if the
     * input is null, blank, or rejected by {@link UUID#fromString} (a
     * 32-character input is re-dashed first).
     */
    public static UUID parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.length() == 32) {
                s =
                        s.substring(0, 8)
                                + "-"
                                + s.substring(8, 12)
                                + "-"
                                + s.substring(12, 16)
                                + "-"
                                + s.substring(16, 20)
                                + "-"
                                + s.substring(20);
            }
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Strips every dash and lowercases with {@link Locale#ROOT}, so the dashed and undashed
     * spellings of one UUID compare equal as text. It does not validate: any other text is changed
     * the same way, and {@code null} becomes {@code ""}, never {@code null}.
     */
    public static String normalizeUndashed(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Whether {@code value} is exactly 32 characters of {@code [0-9a-f]}, the form
     * {@link #normalizeUndashed} gives a UUID. {@code false} for {@code null}.
     */
    public static boolean isUndashedLowercase(String value) {
        return value != null && value.length() == 32 && value.matches("[0-9a-f]{32}");
    }

    /** {@code uuid}'s lowercase spelling without dashes. Throws {@link NullPointerException} for
     *  {@code null}. */
    public static String toUndashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
