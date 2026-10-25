package org.wynnvets.util;

import java.util.Locale;
import java.util.UUID;

/**
 * UUID helpers: text spellings and Minecraft's int array. Each method keeps its own contract, and
 * the contracts differ on purpose:
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
 *   <li>{@link #fromIntArray} and {@link #toIntArray} convert to and from the int array Minecraft
 *       stores a UUID as, most significant int first. Unlike {@code UUIDUtil.uuidFromIntArray},
 *       {@link #fromIntArray} returns {@code null} for {@code null} or any length but four,
 *       rather than throwing or ignoring the extras.</li>
 *   <li>{@link #parseIntArray} reads the two spellings Minecraft prints for that array,
 *       {@code [I;a,b,c,d]} and {@code [I; a, b, c, d]}, and {@link #toIntArrayString} writes
 *       the first.</li>
 *   <li>{@link #parseAny} reads any spelling above.</li>
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

    /**
     * The UUID that Minecraft stores as an int array, most significant int first (the order of
     * {@code UUIDUtil.uuidFromIntArray}). {@code null} for {@code null} or for any length but 4,
     * where {@code UUIDUtil} throws for fewer than four ints and ignores any past the fourth.
     */
    public static UUID fromIntArray(int[] ints) {
        if (ints == null || ints.length != 4) {
            return null;
        }
        long most = ((long) ints[0] << 32) | (ints[1] & 0xFFFFFFFFL);
        long least = ((long) ints[2] << 32) | (ints[3] & 0xFFFFFFFFL);
        return new UUID(most, least);
    }

    /** {@code uuid} as Minecraft's int array, most significant int first; {@code null} for
     *  {@code null}. The inverse of {@link #fromIntArray}. */
    public static int[] toIntArray(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
    }

    /**
     * Parses the two spellings Minecraft prints for a UUID's int array: {@code [I;a,b,c,d]}, as
     * {@code Tag.toString} writes it, and {@code [I; a, b, c, d]}, as the {@code /data} display
     * does. The text is trimmed, and each of the four elements must be an ASCII decimal integer,
     * optionally negative and with any number of leading zeros, that fits an {@code int}.
     * {@code null} for anything else, including {@code null}, a {@code +} sign, a non-ASCII
     * digit, any count but four, and any other spacing, such as a mix of the two spellings.
     */
    public static UUID parseIntArray(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (!s.startsWith("[I;") || !s.endsWith("]")) {
            return null;
        }
        String body = s.substring(3, s.length() - 1);
        boolean spaced = body.startsWith(" ");
        String[] parts = (spaced ? body.substring(1) : body).split(spaced ? ", " : ",", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] ints = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            int start = p.startsWith("-") ? 1 : 0;
            if (p.length() == start) {
                return null;
            }
            for (int k = start; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            try {
                ints[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null; // ASCII digits, so only out of int range
            }
        }
        return fromIntArray(ints);
    }

    /** {@code uuid} in the int-array spelling {@code Tag.toString} writes, {@code [I;a,b,c,d]};
     *  {@code null} for {@code null}. */
    public static String toIntArrayString(UUID uuid) {
        int[] ints = toIntArray(uuid);
        if (ints == null) {
            return null;
        }
        return "[I;" + ints[0] + "," + ints[1] + "," + ints[2] + "," + ints[3] + "]";
    }

    /**
     * A UUID in any spelling this class reads: {@link #parse}'s dashed or undashed text, then
     * {@link #parseIntArray}'s int arrays. {@code null} when neither applies.
     */
    public static UUID parseAny(String raw) {
        UUID uuid = parse(raw);
        return uuid != null ? uuid : parseIntArray(raw);
    }
}
