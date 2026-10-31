package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Tests for {@link PackFiles}: each kind of id's file, the {@code .mcmeta} and reading a file. */
class PackFilesTest {

    @Test
    void eachKindOfIdHasItsFile() {
        assertEquals(
                Identifier.parse("minecraft:textures/block/stone.png"),
                PackFiles.textureFile("minecraft:block/stone"));
        assertEquals(
                Identifier.parse("minecraft:models/item/stick.json"),
                PackFiles.modelFile("minecraft:item/stick"));
        assertEquals(
                Identifier.parse("minecraft:equipment/iron.json"),
                PackFiles.equipmentFile("minecraft:iron"));
    }

    @Test
    void aTextNotAnIdHasNoFile() {
        String notAnId = "Not An Id";

        assertNull(PackFiles.textureFile(notAnId));
        assertNull(PackFiles.modelFile(notAnId));
        assertNull(PackFiles.equipmentFile(notAnId));
    }

    @Test
    void theMcmetaSitsBesideItsFile() {
        assertEquals(
                Identifier.parse("minecraft:textures/block/stone.png.mcmeta"),
                PackFiles.mcmeta(Identifier.parse("minecraft:textures/block/stone.png")));
    }

    @Test
    void onlyAPathEndingInJsonIsJson() {
        assertTrue(PackFiles.isJson(Identifier.parse("minecraft:models/item/stick.json")));
        assertFalse(PackFiles.isJson(Identifier.parse("minecraft:models/item/stick.png")));
    }

    @Test
    void readingGivesTheBytesAndNoSupplierGivesNull() throws IOException {
        byte[] bytes = {1, 2, 3};

        assertArrayEquals(bytes, PackFiles.read(() -> new ByteArrayInputStream(bytes)));
        assertNull(PackFiles.read(null));
    }

    @Test
    void aFoldersJsonFilesAreThoseOfThatNamespaceAndFolderAlone() {
        MemoryPack pack =
                new MemoryPack("pack")
                        .file("minecraft:models/item/a.json", "{}")
                        .file("minecraft:models/item/a.png", "{}")
                        .file("minecraft:models/block/b.json", "{}")
                        .file("other:models/item/c.json", "{}");

        assertEquals(
                List.of(Identifier.parse("minecraft:models/item/a.json")),
                PackFiles.jsonFiles(pack, "minecraft", "models/item"));
    }
}
