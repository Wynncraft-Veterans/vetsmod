package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.PackResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TextureSections;
import org.wynnvets.util.AtomicFolders;

/**
 * Tests for {@link OldClients}: the pins, and the folders under a temporary game folder. How a
 * folder is written is {@code AtomicFoldersTest}'s.
 */
class OldClientsTest {

    private static final String WOOL = "assets/minecraft/textures/block/white_wool.png";
    private static final byte[] PNG = {1, 2, 3};

    private static TextureSections sections(String file, String clears) {
        return parse(
                        """
                        old_clients:
                          '1.7.10':
                            'minecraft:block/white_wool': '%s'
                        %s
                        """
                                .formatted(file, clears))
                .textures();
    }

    private static TextureSections sections(String file) {
        return sections(file, "");
    }

    private static void install(Path root, TextureSections sections) throws IOException {
        RetroSource client = RetroSource.MINECRAFT_1_7_10;
        AtomicFolders.install(
                OldClients.folder(root, client, OldClients.hash(client, sections)),
                Map.of(WOOL, PNG));
    }

    @Test
    void everyOldClientAndNoOtherSourceHasAPinnedJar() {
        for (RetroSource source : RetroSource.values()) {
            assertEquals(source.oldClient(), OldClients.JARS.containsKey(source), source.name());
        }
    }

    @Test
    void aClientIsMissingUntilItsFolderIsInstalledThenItOpens(@TempDir Path root)
            throws IOException {
        TextureSections sections = sections("minecraft:blocks/wool_colored_white");
        assertEquals(List.of(RetroSource.MINECRAFT_1_7_10), OldClients.missing(root, sections));
        assertTrue(OldClients.open(root, sections).isEmpty());

        install(root, sections);

        assertEquals(List.of(), OldClients.missing(root, sections));
        Map<RetroSource, PackResources> packs = OldClients.open(root, sections);
        assertTrue(
                PackFiles.textureIds(packs.get(RetroSource.MINECRAFT_1_7_10))
                        .contains("minecraft:block/white_wool"));
    }

    @Test
    void newRowsOrClearsMakeANewFolderSoTheClientIsMissingAgain(@TempDir Path root)
            throws IOException {
        TextureSections before = sections("minecraft:blocks/wool_colored_white");
        TextureSections rows = sections("minecraft:blocks/cloth_0");
        TextureSections clears =
                sections(
                        "minecraft:blocks/wool_colored_white",
                        "old_client_clears:\n  'minecraft:block/white_wool': [[0, 0, 1, 1]]");
        RetroSource client = RetroSource.MINECRAFT_1_7_10;
        assertNotEquals(OldClients.hash(client, before), OldClients.hash(client, rows));
        assertNotEquals(OldClients.hash(client, before), OldClients.hash(client, clears));

        install(root, before);

        assertEquals(List.of(client), OldClients.missing(root, rows));
        assertEquals(List.of(client), OldClients.missing(root, clears));
    }
}
