package org.wynnvets.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link AtomicFolders}, under a temporary folder. */
class AtomicFoldersTest {

    private static final String FILE = "assets/minecraft/textures/block/white_wool.png";
    private static final byte[] BYTES = {1, 2, 3};

    private static long entries(Path folder) throws IOException {
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.count();
        }
    }

    @Test
    void anInstallDeletesOlderFoldersAndAbandonedOnesButNotOneBeingWritten(@TempDir Path parent)
            throws IOException {
        Path old = parent.resolve("old");
        AtomicFolders.install(old, Map.of(FILE, BYTES));
        Path abandoned = Files.createDirectories(parent.resolve("current.tmp-abandoned"));
        Files.setLastModifiedTime(
                abandoned, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path writing = Files.createDirectories(parent.resolve("current.tmp-writing"));

        Path current = parent.resolve("current");
        AtomicFolders.install(current, Map.of(FILE, BYTES));

        assertArrayEquals(BYTES, Files.readAllBytes(current.resolve(FILE)));
        assertFalse(Files.exists(old), "an older folder");
        assertFalse(Files.exists(abandoned), "a temporary folder a day old");
        assertTrue(Files.exists(writing), "one another game may still be writing");
    }

    @Test
    void aFolderThatAlreadyHasItsNameIsKept(@TempDir Path parent) throws IOException {
        Path target = parent.resolve("folder");
        AtomicFolders.install(target, Map.of(FILE, BYTES));

        AtomicFolders.install(target, Map.of(FILE, new byte[] {9}));

        assertArrayEquals(BYTES, Files.readAllBytes(target.resolve(FILE)));
        assertEquals(1, entries(parent), "no temporary folder is left");
    }

    @Test
    void aFileOutsideTheFolderIsRefusedAndNothingIsLeft(@TempDir Path parent) throws IOException {
        assertThrows(
                IOException.class,
                () ->
                        AtomicFolders.install(
                                parent.resolve("folder"), Map.of("../outside.png", BYTES)));
        assertEquals(0, entries(parent), "neither the file nor the temporary folder");
    }
}
