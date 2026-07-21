package org.wynnvets.chat;

import java.util.Locale;
import java.util.Map;

/**
 * Central mapping from raw Wynn guild rank names to the client-facing
 * display labels introduced in the 2026-07 permission restructure.
 *
 * <p>The wire keeps raw Wynn names ({@code strategist}/{@code chief}/
 * {@code owner}/etc.) for backwards compatibility with vetsmod 0.14.x
 * clients that whitelist those values. This class does the display-side
 * remap: {@code Strategist} / {@code Chief} / {@code Owner} all collapse
 * to {@code "Steward"} for pill display; {@code Captain} / {@code Recruiter}
 * collapse to {@code "Returner"} (Captain was retired in the restructure;
 * stray captains render as non-staff Returners).</p>
 *
 * <p>Kept as a single lookup so if we later decide to distinguish
 * Chief/Owner as {@code "Chief Steward"} rather than {@code "Steward"},
 * it's a one-line edit here.</p>
 */
public final class RankDisplayMap {

    private static final Map<String, String> DISPLAY = Map.of(
            "recruit",    "Recruit",
            "recruiter",  "Returner",
            "captain",    "Returner",
            "strategist", "Steward",
            "chief",      "Steward",
            "owner",      "Steward"
    );

    private RankDisplayMap() {
    }

    /**
     * Return the display label for {@code rawRank}. Case-insensitive.
     * If the rank isn't in the table the raw value is returned unchanged
     * (nulls pass through as null).
     */
    public static String displayFor(String rawRank) {
        if (rawRank == null) {
            return null;
        }
        String key = rawRank.trim().toLowerCase(Locale.ROOT);
        return DISPLAY.getOrDefault(key, rawRank);
    }

    /**
     * Does {@code rawRank} identify a staff-tier Wynn rank
     * ({@code strategist} / {@code chief} / {@code owner})? Captains are
     * NOT staff post-restructure.
     */
    public static boolean isStaffDisplay(String rawRank) {
        if (rawRank == null) {
            return false;
        }
        String key = rawRank.trim().toLowerCase(Locale.ROOT);
        return "strategist".equals(key) || "chief".equals(key) || "owner".equals(key);
    }

    /**
     * The tag string rendered next to a staff member's name in the
     * {@code /v} menu. Strategists get {@code "staff"}; chiefs and owners
     * get {@code "owner"}; anything else returns {@code null}.
     */
    public static String vTagFor(String rawRank) {
        if (rawRank == null) {
            return null;
        }
        String key = rawRank.trim().toLowerCase(Locale.ROOT);
        if ("strategist".equals(key)) {
            return "staff";
        }
        if ("chief".equals(key) || "owner".equals(key)) {
            return "owner";
        }
        return null;
    }
}
