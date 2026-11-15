package org.wynnvets.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LegacyTooltipRenderer}'s percentage suffix, which it strips from an item's name
 * before matching the name against {@code definitions.yml}. Rewriting a tooltip needs the hovered
 * stack and the config, and is not tested.
 */
class LegacyTooltipRendererTest {

    @Test
    void eachSuffixWynntilsOrWynnmodWritesIsStripped() {
        assertEquals("Sword", LegacyTooltipRenderer.stripPercentSuffix("Sword [61.7%]"));
        assertEquals("Sword", LegacyTooltipRenderer.stripPercentSuffix("Sword [16,6%]"));
        assertEquals("Sword", LegacyTooltipRenderer.stripPercentSuffix("Sword [~61.7%]"));
        assertEquals("Sword", LegacyTooltipRenderer.stripPercentSuffix("Sword [100%]"));
        // wynnmod in an Arabic locale: Arabic-Indic digits around the Arabic decimal separator.
        String arabic =
                new StringBuilder()
                        .appendCodePoint(0x0661)
                        .appendCodePoint(0x0666)
                        .appendCodePoint(0x066B)
                        .appendCodePoint(0x0666)
                        .toString();
        assertEquals("Sword", LegacyTooltipRenderer.stripPercentSuffix("Sword[" + arabic + "%]"));
    }
}
