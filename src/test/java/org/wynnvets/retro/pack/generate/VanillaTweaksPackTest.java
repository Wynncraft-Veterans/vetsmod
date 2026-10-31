package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.pack.MemoryPack;

/**
 * Tests for {@link VanillaTweaksPack} over the art itself: as a folder, as the art is in a
 * development run, and inside a zip, as it is in the mod's jar.
 */
class VanillaTweaksPackTest {

    private static final String HEART = "minecraft:gui/sprites/hud/heart/withered_full";
    private static final String HEART_FILE =
            "assets/minecraft/textures/gui/sprites/hud/heart/withered_full.png";

    private static Path artFolder() throws URISyntaxException {
        return Path.of(
                VanillaTweaksPackTest.class.getResource("/" + VanillaTweaksPack.ROOT).toURI());
    }

    private static void assertReadsTheHeart(Path root, byte[] png) throws IOException {
        try (ProgrammerArtSources sources =
                ProgrammerArtSources.open(
                        null, VanillaTweaksPack.open(root), new MemoryPack("vanilla"))) {
            assertTrue(sources.has(RetroSource.VANILLA_TWEAKS, HEART));
            assertFalse(sources.has(RetroSource.VANILLA, HEART));
            Identifier file =
                    Identifier.parse("minecraft:textures/gui/sprites/hud/heart/withered_full.png");
            assertArrayEquals(png, sources.read(RetroSource.VANILLA_TWEAKS, file));
            assertNull(
                    sources.read(
                            RetroSource.VANILLA_TWEAKS,
                            Identifier.parse("minecraft:textures/block/stone_nothing.png")));
        }
    }

    @Test
    void readsTheArtFromAFolder() throws Exception {
        Path folder = artFolder();
        assertReadsTheHeart(folder, Files.readAllBytes(folder.resolve(HEART_FILE)));
    }

    @Test
    void readsTheArtFromInsideAZip(@TempDir Path temp) throws Exception {
        byte[] png = Files.readAllBytes(artFolder().resolve(HEART_FILE));
        Path zip = temp.resolve("mod.jar");
        try (FileSystem jar = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path inJar = jar.getPath(VanillaTweaksPack.ROOT).resolve(HEART_FILE);
            Files.createDirectories(inJar.getParent());
            Files.write(inJar, png);
        }
        try (FileSystem jar = FileSystems.newFileSystem(zip)) {
            assertReadsTheHeart(jar.getPath(VanillaTweaksPack.ROOT), png);
        }
    }

    @Test
    void outsideARunningGameThereIsNoArtToFindAndNothingThrows() throws Exception {
        assertNotNull(artFolder());
        assertNull(VanillaTweaksPack.open(), "no vetsmod mod container under JUnit");
    }
}
