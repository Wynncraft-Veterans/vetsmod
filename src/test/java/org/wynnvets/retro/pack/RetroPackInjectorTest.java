package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.server.packs.PackResources;
import org.junit.jupiter.api.Test;

/**
 * Tests for how {@link RetroPackInjector} tells Wynncraft's pack: by its id's prefix and a file
 * only it has. Adding the pack to a reload needs a running client and is not tested.
 */
class RetroPackInjectorTest {

    private static final String PROBE = "minecraft:font/banner/box.json";

    private static PackResources wynncraft(String id) {
        return new MemoryPack(id).file(PROBE, "{}");
    }

    @Test
    void wynncraftsPackIsAServerOrPreloadedPackWithTheProbe() {
        assertTrue(RetroPackInjector.isWynncraftPack(wynncraft("server/abc")));
        assertTrue(RetroPackInjector.isWynncraftPack(wynncraft("wynntils_preloaded/abc")));
        assertFalse(
                RetroPackInjector.isWynncraftPack(new MemoryPack("server/abc")),
                "another server's pack");
        assertFalse(
                RetroPackInjector.isWynncraftPack(wynncraft("file/mine.zip")),
                "the player's own pack, whatever it holds");
    }

    @Test
    void aPackThatThrowsIsNotWynncrafts() {
        assertFalse(
                RetroPackInjector.isWynncraftPack(
                        new MemoryPack("server/abc").file(PROBE, "{}").throwingOnLookup()));
    }

    @Test
    void theHighestWynncraftPackIsChosen() {
        PackResources vanilla = new MemoryPack("vanilla");
        PackResources mine = new MemoryPack("file/mine.zip");

        assertEquals(
                3,
                RetroPackInjector.highestWynncraftPack(
                        List.of(
                                vanilla,
                                wynncraft("wynntils_preloaded/abc"),
                                mine,
                                wynncraft("server/abc"),
                                mine)));
        assertEquals(-1, RetroPackInjector.highestWynncraftPack(List.of(vanilla, mine)));
        assertEquals(-1, RetroPackInjector.highestWynncraftPack(List.of()));
    }

    @Test
    void onlyTheInjectorsOwnPackIsARetroPack() {
        assertFalse(RetroPackInjector.isRetroPack(new MemoryPack("retro")));
    }
}
