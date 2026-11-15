package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.wynnvets.retro.pack.PackFiles;

/**
 * Tests for {@link BundledArt} over the art itself: as a folder, as the art is in a development
 * run, and inside a zip, as it is in the mod's jar.
 */
class BundledArtTest {

    /** A texture the art holds. */
    private static final String SAMPLE = "minecraft:gui/sprites/hud/heart/withered_full";

    private static final Identifier SAMPLE_FILE = PackFiles.textureFile(SAMPLE);

    private static Path artFolder() throws URISyntaxException {
        return Path.of(BundledArtTest.class.getResource("/" + BundledArt.ROOT).toURI());
    }

    private static void assertReads(Path root, byte[] png) throws IOException {
        try (RetroSources sources =
                RetroSources.open(
                        null,
                        Map.of(RetroSource.VANILLA_TWEAKS, BundledArt.open(root)),
                        new MemoryPack("vanilla"))) {
            assertTrue(sources.has(RetroSource.VANILLA_TWEAKS, SAMPLE));
            assertFalse(sources.has(RetroSource.VANILLA, SAMPLE));
            assertArrayEquals(png, sources.read(RetroSource.VANILLA_TWEAKS, SAMPLE_FILE));
            assertNull(
                    sources.read(
                            RetroSource.VANILLA_TWEAKS,
                            Identifier.parse("minecraft:textures/block/stone_nothing.png")));
        }
    }

    @Test
    void theArtIsReadFromItsFolder() throws Exception {
        Path folder = artFolder();
        assertReads(folder, Files.readAllBytes(folder.resolve(PackFiles.path(SAMPLE_FILE))));
    }

    @Test
    void theArtIsReadFromInsideAZip(@TempDir Path temp) throws Exception {
        String sample = PackFiles.path(SAMPLE_FILE);
        byte[] png = Files.readAllBytes(artFolder().resolve(sample));
        Path zip = temp.resolve("vetsmod.jar");
        try (FileSystem jar = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path inJar = jar.getPath(BundledArt.ROOT).resolve(sample);
            Files.createDirectories(inJar.getParent());
            Files.write(inJar, png);
        }
        try (FileSystem jar = FileSystems.newFileSystem(zip)) {
            assertReads(jar.getPath(BundledArt.ROOT), png);
        }
    }

    @Test
    void outsideARunningGameThereIsNoArtToFindAndNothingThrows() {
        assertTrue(BundledArt.open().isEmpty(), "no vetsmod mod container under JUnit");
    }
}
