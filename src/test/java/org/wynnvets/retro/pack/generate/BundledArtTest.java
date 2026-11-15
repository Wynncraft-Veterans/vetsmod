package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * Tests for {@link BundledArt} over the art itself: as a folder, as the art is in a development
 * run, and inside a zip, as it is in the mod's jar.
 */
class BundledArtTest {

    /** A texture each bundle holds. */
    private static final Map<RetroSource, String> SAMPLES =
            Map.of(RetroSource.VANILLA_TWEAKS, "minecraft:gui/sprites/hud/heart/withered_full");

    /** {@code textureId}'s file under a bundle's folder. */
    private static String file(String textureId) {
        Identifier id = Identifier.parse(textureId);
        return "assets/" + id.getNamespace() + "/textures/" + id.getPath() + ".png";
    }

    private static Path artFolder(RetroSource source) throws URISyntaxException {
        return Path.of(
                BundledArtTest.class
                        .getResource("/" + BundledArt.FOLDERS.get(source).root())
                        .toURI());
    }

    private static void assertReads(RetroSource source, Path root, byte[] png) throws IOException {
        String texture = SAMPLES.get(source);
        try (RetroSources sources =
                RetroSources.open(
                        null,
                        Map.of(source, BundledArt.open(source, root)),
                        Map.of(),
                        new MemoryPack("vanilla"))) {
            assertTrue(sources.has(source, texture), texture);
            assertFalse(sources.has(RetroSource.VANILLA, texture), texture);
            Identifier file =
                    Identifier.parse(texture).withPath(path -> "textures/" + path + ".png");
            assertArrayEquals(png, sources.read(source, file), texture);
            assertNull(
                    sources.read(
                            source, Identifier.parse("minecraft:textures/block/stone_nothing.png")),
                    texture);
        }
    }

    @Test
    void eachBundleIsReadFromItsFolder() throws Exception {
        assertEquals(BundledArt.FOLDERS.keySet(), SAMPLES.keySet(), "a sample per bundle");
        for (RetroSource source : SAMPLES.keySet()) {
            Path folder = artFolder(source);
            assertReads(
                    source, folder, Files.readAllBytes(folder.resolve(file(SAMPLES.get(source)))));
        }
    }

    @Test
    void eachBundleIsReadFromInsideAZip(@TempDir Path temp) throws Exception {
        for (RetroSource source : SAMPLES.keySet()) {
            String root = BundledArt.FOLDERS.get(source).root();
            String sample = file(SAMPLES.get(source));
            byte[] png = Files.readAllBytes(artFolder(source).resolve(sample));
            Path zip = temp.resolve(source.yamlName() + ".jar");
            try (FileSystem jar = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
                Path inJar = jar.getPath(root).resolve(sample);
                Files.createDirectories(inJar.getParent());
                Files.write(inJar, png);
            }
            try (FileSystem jar = FileSystems.newFileSystem(zip)) {
                assertReads(source, jar.getPath(root), png);
            }
        }
    }

    @Test
    void outsideARunningGameThereIsNoArtToFindAndNothingThrows() {
        assertTrue(BundledArt.open().isEmpty(), "no vetsmod mod container under JUnit");
    }
}
