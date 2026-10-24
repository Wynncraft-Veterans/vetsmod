package org.wynnvets.items;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LegacyLoreText}, the string-level rules split out of
 * {@link LegacyItemHandler} and {@link LegacyEnchantmentRenderer} so that they can run without
 * the Minecraft bootstrap those two classes need at class-init.
 */
class LegacyLoreTextTest {

    @Test
    void betaLevelLine_acceptsBothCapitalisationsOfMin() {
        assertTrue(LegacyLoreText.isBetaLevelLine("Lv. min: 26"));
        assertTrue(LegacyLoreText.isBetaLevelLine("Lv. Min: 26"), "Dental Floss's spelling");
        assertTrue(LegacyLoreText.isBetaLevelLine("Lv min: 5"), "the dot is optional");
    }

    @Test
    void betaLevelLine_stillRequiresTheAnchorAndANumber() {
        assertFalse(LegacyLoreText.isBetaLevelLine("Combat Lv. Min: 65"), "modern line");
        assertFalse(LegacyLoreText.isBetaLevelLine("Lv. Min: high"));
        assertFalse(LegacyLoreText.isBetaLevelLine("Lv. Min: 26 extra"));
        assertFalse(LegacyLoreText.isBetaLevelLine(null));
    }
}
