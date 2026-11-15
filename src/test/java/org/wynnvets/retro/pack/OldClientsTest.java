package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.PackResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TextureSections;

/** Tests for {@link OldClients}: the pins, and the folders under a temporary game folder. */
class OldClientsTest {

    private static final String WOOL = "assets/minecraft/textures/block/white_wool.png";
    private static final byte[] PNG = {1, 2, 3};

    private static TextureSections sections(String file) {
        return parse(
                        """
                        old_clients:
                          '1.7.10':
                            'minecraft:block/white_wool': '%s'
                        """
                                .formatted(file))
                .textures();
    }

    @Test
    void everyOldClientIsPinnedToItsJarOnMojangsServer() {
        for (RetroSource source : RetroSource.values()) {
            assertEquals(source.oldClient(), OldClients.JARS.containsKey(source), source.name());
        }
        OldClients.Jar jar = OldClients.JARS.get(RetroSource.MINECRAFT_1_7_10);
        assertEquals("piston-data.mojang.com", jar.uri().getHost());
        assertTrue(jar.uri().getPath().contains(jar.sha1()));
    }

    @Test
    void aClientIsMissingUntilItsFolderIsInstalledThenItOpens(@TempDir Path root)
            throws IOException {
        TextureSections sections = sections("minecraft:blocks/wool_colored_white");
        assertEquals(List.of(RetroSource.MINECRAFT_1_7_10), OldClients.missing(root, sections));
        assertTrue(OldClients.open(root, sections).isEmpty());

        String hash = OldClients.hash(RetroSource.MINECRAFT_1_7_10, sections);
        OldClients.install(root, RetroSource.MINECRAFT_1_7_10, hash, Map.of(WOOL, PNG));

        assertEquals(List.of(), OldClients.missing(root, sections));
        Map<RetroSource, PackResources> packs = OldClients.open(root, sections);
        assertTrue(
                PackFiles.textureIds(packs.get(RetroSource.MINECRAFT_1_7_10))
                        .contains("minecraft:block/white_wool"));
    }

    @Test
    void newRowsMakeANewFolderAndTheInstallDeletesTheStaleOnes(@TempDir Path root)
            throws IOException {
        TextureSections before = sections("minecraft:blocks/wool_colored_white");
        TextureSections after = sections("minecraft:blocks/cloth_0");
        String old = OldClients.hash(RetroSource.MINECRAFT_1_7_10, before);
        String current = OldClients.hash(RetroSource.MINECRAFT_1_7_10, after);
        assertNotEquals(old, current);
        OldClients.install(root, RetroSource.MINECRAFT_1_7_10, old, Map.of(WOOL, PNG));
        Path client = root.resolve("1.7.10");
        Path abandoned = Files.createDirectories(client.resolve(current + ".tmp-abandoned"));
        Files.setLastModifiedTime(
                abandoned, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path writing = Files.createDirectories(client.resolve(current + ".tmp-writing"));
        assertEquals(List.of(RetroSource.MINECRAFT_1_7_10), OldClients.missing(root, after));

        OldClients.install(root, RetroSource.MINECRAFT_1_7_10, current, Map.of(WOOL, PNG));

        assertTrue(Files.isDirectory(client.resolve(current)));
        assertFalse(Files.exists(client.resolve(old)), "the folder of the older rows");
        assertFalse(Files.exists(abandoned), "a temporary folder a day old");
        assertTrue(Files.exists(writing), "one another game may still be writing");
    }

    @Test
    void aFolderThatAlreadyHasItsNameIsKept(@TempDir Path root) throws IOException {
        TextureSections sections = sections("minecraft:blocks/wool_colored_white");
        String hash = OldClients.hash(RetroSource.MINECRAFT_1_7_10, sections);
        OldClients.install(root, RetroSource.MINECRAFT_1_7_10, hash, Map.of(WOOL, PNG));

        OldClients.install(root, RetroSource.MINECRAFT_1_7_10, hash, Map.of(WOOL, new byte[] {9}));

        Path folder = OldClients.folder(root, RetroSource.MINECRAFT_1_7_10, hash);
        assertTrue(Arrays.equals(PNG, Files.readAllBytes(folder.resolve(WOOL))));
        try (var siblings = Files.list(folder.getParent())) {
            assertEquals(1, siblings.count(), "no temporary folder is left");
        }
    }

    @Test
    void aFileOutsideTheFolderIsRefused(@TempDir Path root) {
        assertThrows(
                IOException.class,
                () ->
                        OldClients.install(
                                root,
                                RetroSource.MINECRAFT_1_7_10,
                                "hash",
                                Map.of("../outside.png", PNG)));
    }
}
