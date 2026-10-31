package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.StackSections.AccessoryTiers;

/**
 * Tests for {@link RetroAccessories}' tier lookup against the shipped {@code accessory_tiers}.
 * Reading a stack's level needs Wynntils and a running client, and is not tested.
 */
class RetroAccessoriesTest {

    private static AccessoryTiers rows() throws IOException {
        return RetroManifest.load().stacks().accessoryTiers();
    }

    @Test
    void eachLevelGetsItsTierAndEachTierStartsAtItsBelow() throws IOException {
        Map<Integer, String> tiers =
                Map.ofEntries(
                        Map.entry(1, "leather"),
                        Map.entry(19, "leather"),
                        Map.entry(20, "gold"),
                        Map.entry(39, "gold"),
                        Map.entry(40, "chainmail"),
                        Map.entry(59, "chainmail"),
                        Map.entry(60, "iron"),
                        Map.entry(79, "iron"),
                        Map.entry(80, "diamond"),
                        Map.entry(99, "diamond"),
                        Map.entry(100, "netherite"),
                        Map.entry(120, "netherite"));
        for (Map.Entry<Integer, String> tier : tiers.entrySet()) {
            assertEquals(
                    "vetsmod:retro/accessory/ring_" + tier.getValue(),
                    RetroAccessories.itemFor(rows(), "RING", tier.getKey()),
                    "level " + tier.getKey());
        }
    }

    @Test
    void eachAccessoryTypeHasItsOwnItem() throws IOException {
        assertEquals(
                "vetsmod:retro/accessory/ring_chainmail",
                RetroAccessories.itemFor(rows(), "RING", 50));
        assertEquals(
                "vetsmod:retro/accessory/bracelet_chainmail",
                RetroAccessories.itemFor(rows(), "BRACELET", 50));
        assertEquals(
                "vetsmod:retro/accessory/necklace_chainmail",
                RetroAccessories.itemFor(rows(), "NECKLACE", 50));
    }

    @Test
    void anythingElseIsNoAccessory() throws IOException {
        assertNull(RetroAccessories.itemFor(rows(), "SPEAR", 50));
        assertNull(RetroAccessories.itemFor(rows(), "MASTERY_TOME", 50));
    }
}
