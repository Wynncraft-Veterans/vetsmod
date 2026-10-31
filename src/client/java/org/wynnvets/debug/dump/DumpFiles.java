package org.wynnvets.debug.dump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Where the debug dumps go: {@code vetsmod/dumps/} in the game directory, one folder per kind,
 * such as {@code items} for the item dump, {@code anni} for the anni snapshots and {@code retro}
 * for the retro report.
 */
public final class DumpFiles {

    private static final Path ROOT =
            FabricLoader.getInstance().getGameDir().resolve("vetsmod/dumps");

    private DumpFiles() {}

    /**
     * The folder of one kind of dump. It may not exist yet.
     *
     * @param kind the kind, such as {@code items}
     * @return {@code vetsmod/dumps/<kind>} in the game directory
     */
    public static Path folder(String kind) {
        return ROOT.resolve(kind);
    }

    /**
     * Writes a dump, as UTF-8, creating its folder first.
     *
     * @param kind the kind, such as {@code items}
     * @param fileName the file's name in that folder; an existing file of that name is replaced
     * @param text the contents
     * @return the file written
     * @throws IOException when the folder cannot be made or the file written
     */
    public static Path write(String kind, String fileName, String text) throws IOException {
        Path folder = folder(kind);
        Files.createDirectories(folder);
        return Files.writeString(folder.resolve(fileName), text);
    }

    /**
     * A file's path as the player knows it: from the game directory, with forward slashes.
     *
     * @param file a file under the game directory
     * @return such as {@code vetsmod/dumps/retro/report-20261029-140312.txt}
     */
    public static String shown(Path file) {
        return FabricLoader.getInstance()
                .getGameDir()
                .relativize(file)
                .toString()
                .replace('\\', '/');
    }
}
