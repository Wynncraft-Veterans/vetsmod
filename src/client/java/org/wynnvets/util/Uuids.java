package org.wynnvets.util;

import java.util.UUID;

/**
 * Small UUID helpers shared by providers that receive undashed IDs.
 *
 * <p>Formerly {@code fetcher.lookup.Uuids}.</p>
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
}
