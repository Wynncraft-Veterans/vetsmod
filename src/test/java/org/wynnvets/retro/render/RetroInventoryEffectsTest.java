package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RetroInventoryEffects#isHidden} with the shipped
 * {@code hidden_inventory_effects}: Wynncraft's endless attack-speed effects are left out of the
 * inventory's effect list, and nothing else is. Filtering a live effect list needs a running client
 * and is not tested.
 */
class RetroInventoryEffectsTest {

    @Test
    void theEndlessAttackSpeedEffectsAreHidden() {
        assertTrue(RetroInventoryEffects.isHidden("minecraft:haste", true));
        assertTrue(RetroInventoryEffects.isHidden("minecraft:mining_fatigue", true));
    }

    @Test
    void anEffectThatRunsOutStillShows() {
        assertFalse(RetroInventoryEffects.isHidden("minecraft:haste", false));
        assertFalse(RetroInventoryEffects.isHidden("minecraft:mining_fatigue", false));
    }

    @Test
    void otherEffectsStillShowEvenWhenEndless() {
        assertFalse(RetroInventoryEffects.isHidden("minecraft:speed", true));
        assertFalse(RetroInventoryEffects.isHidden("minecraft:night_vision", true));
        assertFalse(
                RetroInventoryEffects.isHidden("minecraft:haste_extra", true), "whole ids only");
    }
}
