package org.wynnvets.rendering.colors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VanillaGuiColors}: every colour is opaque ARGB, since {@code GuiGraphics} skips
 * a fill or string whose alpha is 0, and each is the value vanilla draws.
 */
class VanillaGuiColorsTest {

    @Test
    void everyColourIsOpaque() {
        assertEquals(0xFF, VanillaGuiColors.SLOT_HIGHLIGHT >>> 24);
    }

    @Test
    void theColoursAreVanillas() {
        // gui/container/inventory.png: a slot's light edge.
        assertEquals(0xFFFFFF, VanillaGuiColors.SLOT_HIGHLIGHT & 0xFFFFFF);
    }
}
