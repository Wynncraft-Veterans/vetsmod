package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RetroStacks}' parsed ids. The stacks need the Minecraft bootstrap and are not
 * tested.
 */
class RetroStacksTest {

    @Test
    void anIdIsParsedOnce() {
        assertEquals("minecraft:paper", RetroStacks.id("minecraft:paper").toString());
        assertSame(RetroStacks.id("minecraft:paper"), RetroStacks.id("minecraft:paper"));
    }
}
