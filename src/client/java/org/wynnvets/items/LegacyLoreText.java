package org.wynnvets.items;

import java.util.regex.Pattern;

/**
 * String-level rules the legacy-item code applies to tooltip and lore text, kept apart from
 * {@link LegacyItemHandler} and {@link LegacyEnchantmentRenderer} because both of those load
 * {@code ItemStack} in their static initialisers, which the unit-test runtime cannot do.
 * Nothing here touches Minecraft, so {@code LegacyLoreTextTest} drives it directly.
 */
final class LegacyLoreText {

    /**
     * The beta-era level line. Case-insensitive because the beta items disagree on the
     * capital: most read {@code Lv. min: 26}, Dental Floss reads {@code Lv. Min: 26}.
     * Anchored at {@code Lv}, so the modern {@code Combat Lv. Min: 65} never matches.
     */
    private static final Pattern LV_MIN_PATTERN =
            Pattern.compile("^Lv\\.? min: \\d+$", Pattern.CASE_INSENSITIVE);

    private LegacyLoreText() {}

    /** Returns {@code true} if the formatting-stripped line is a beta-era "Lv. min: N" line. */
    static boolean isBetaLevelLine(String plain) {
        return plain != null && LV_MIN_PATTERN.matcher(plain).matches();
    }
}
