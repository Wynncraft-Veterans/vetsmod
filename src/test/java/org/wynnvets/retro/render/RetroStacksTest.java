package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RetroStacks}' glass pane ids. Making a stack needs Minecraft's item registry
 * and is not tested.
 */
class RetroStacksTest {

    @Test
    void clearIsThePlainPaneAndADyeItsStainedPane() {
        assertEquals("glass_pane", RetroStacks.paneId("clear"));
        assertEquals("light_blue_stained_glass_pane", RetroStacks.paneId("light_blue"));
    }
}
