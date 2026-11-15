package org.wynnvets.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.wynnvets.logging.VetsLogger;

/**
 * Folders that are written whole or not at all: under a temporary name, then renamed, so a reader
 * that opens only the folder of the final name never meets one half written. What retro's old
 * clients' files and the downloaded panoramas are kept in.
 */
public final class AtomicFolders {

    /** A temporary folder this old is a write that never finished, and is deleted. */
    private static final Duration ABANDONED = Duration.ofDays(1);

    private static final String TEMPORARY = ".tmp-";

    /**
     * How often a rename refused for a moment is tried, waiting 50 ms before the first retry and
     * twice as long before each next (0.75 s in all).
     */
    private static final int RENAME_ATTEMPTS = 5;

    private AtomicFolders() {}

    /**
     * Writes {@code files} under a temporary name beside {@code target}, then gives the folder
     * {@code target}'s name, then deletes the other folders beside it: older ones, and temporary
     * ones a day old. A folder that already has the name, which another game sharing the folder
     * can have written, is kept.
     *
     * @param target the folder to write
     * @param files each file by its path in the folder, such as
     *     {@code assets/minecraft/textures/block/white_wool.png}
     * @throws IOException when the folder cannot be written
     */
    public static void install(Path target, Map<String, byte[]> files) throws IOException {
        String name = target.getFileName().toString();
        Path temporary = target.resolveSibling(name + TEMPORARY + UUID.randomUUID());
        try {
            Files.createDirectories(temporary);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Path path = temporary.resolve(file.getKey()).normalize();
                if (!path.startsWith(temporary)) throw new IOException("outside: " + file.getKey());
                Files.createDirectories(path.getParent());
                Files.write(path, file.getValue());
            }
        } catch (IOException | RuntimeException e) {
            delete(temporary);
            throw e;
        }
        rename(temporary, target);
        deleteStale(target.getParent(), name);
    }

    /**
     * Gives the written folder its name. A folder that already has the name is kept and the written
     * one deleted. On Windows a scanner can hold a just-written file open and refuse the rename for
     * a moment, so it is tried again a few times first.
     */
    private static void rename(Path temporary, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                if (Files.isDirectory(target)) {
                    delete(temporary);
                    return;
                }
                if (e instanceof FileAlreadyExistsException || attempt == RENAME_ATTEMPTS - 1) {
                    delete(temporary);
                    throw e;
                }
                try {
                    Thread.sleep(50L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    delete(temporary);
                    throw new IOException("interrupted renaming " + temporary, interrupted);
                }
            }
        }
    }

    /** Deletes each folder beside the current one but those still being written. */
    private static void deleteStale(Path parent, String current) {
        Instant abandoned = Instant.now().minus(ABANDONED);
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(parent)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                if (name.equals(current)) continue;
                if (name.contains(TEMPORARY)) {
                    FileTime modified = Files.getLastModifiedTime(folder);
                    if (modified.toInstant().isAfter(abandoned)) continue;
                }
                delete(folder);
            }
        } catch (IOException | RuntimeException e) {
            VetsLogger.debug("Left some stale folders in {}", parent, e);
        }
    }

    /**
     * Deletes a folder and everything in it; what cannot be deleted stays.
     *
     * @param folder the folder
     */
    public static void delete(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    VetsLogger.debug("Could not delete {}", path, e);
                }
            }
        } catch (IOException | RuntimeException e) {
            VetsLogger.debug("Could not delete {}", folder, e);
        }
    }
}
