package org.wynnvets.retro.pack;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackResources;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TextureSections;

/**
 * The old Minecraft clients retro takes art from ({@link RetroSource#oldClient()}): each jar as
 * Mojang serves it, and the files {@link OldClientFetch} took from it, kept in the game folder.
 *
 * <p>A client's files live in {@code vetsmod/retro/<minecraft version>/<client version>/<hash>},
 * at today's texture ids, as a
 * pack's would. The hash covers the jar, this format and the client's rows, so a change to the
 * rows makes a new folder and leaves the old one stale. A folder is complete once it has its name:
 * it is written under a temporary name and renamed, and a generation opens only the folder of the
 * current hash. A client with no such folder has no textures, so the next source supplies them.
 */
public final class OldClients {

    /** Changes the hash when what a folder holds for the same rows changes. */
    private static final int FORMAT = 1;

    /** A temporary folder this old is a fetch that never finished, and is deleted. */
    private static final Duration ABANDONED = Duration.ofDays(1);

    private static final String TEMPORARY = ".tmp-";

    /** How often a rename refused for a moment is tried: 50 ms, then doubling, up to 0.8 s. */
    private static final int RENAME_ATTEMPTS = 5;

    /**
     * One client's jar, as Mojang serves it. The jars never change, so each is pinned.
     *
     * @param sha1 the jar's SHA-1, which is also its name on Mojang's server
     * @param directoryOffset where its central directory starts, which is where its entries end
     * @param directorySize the central directory's size
     * @param directorySha1 the central directory's SHA-1, which pins every entry's name, size and
     *     CRC-32
     */
    record Jar(String sha1, long directoryOffset, int directorySize, String directorySha1) {
        /** The jar on Mojang's server. */
        URI uri() {
            return URI.create("https://piston-data.mojang.com/v1/objects/" + sha1 + "/client.jar");
        }
    }

    /** Each old client's jar. */
    static final Map<RetroSource, Jar> JARS =
            Map.of(
                    RetroSource.MINECRAFT_1_7_10,
                    new Jar(
                            "e80d9b3bf5085002218d4be59e668bac718abbc6",
                            5056710,
                            199513,
                            "7ee19573f2246a2ff8874ec8ef6fd6e8755396cd"),
                    RetroSource.MINECRAFT_1_8_9,
                    new Jar(
                            "3870888a6c3d349d3771a3e9d16c9bf5e076b908",
                            8027128,
                            434334,
                            "f004c1db6dcfb7f982c0da7d8d9c1eb3df1126e4"),
                    RetroSource.MINECRAFT_1_9_4,
                    new Jar(
                            "4a61c873be90bb1196d68dac7b29870408c56969",
                            8258006,
                            478055,
                            "50763efeb62f8e17e480f2eb27ac950e5a090efb"));

    private OldClients() {}

    /**
     * The folder every client's folders are in, one per Minecraft version: today's texture ids and
     * sizes decide what a folder holds, and two versions sharing a game folder keep their own.
     *
     * @return {@code vetsmod/retro/<minecraft version>} in the game folder, or {@code null} when
     *     it cannot be found
     */
    static Path root() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            String minecraft =
                    loader.getModContainer("minecraft")
                            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                            .orElse("unknown");
            return loader.getGameDir().resolve("vetsmod").resolve("retro").resolve(minecraft);
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.debug("Retro: no game folder for the old clients' files", e);
            return null;
        }
    }

    /**
     * The packs of the old clients whose files are here, for a generation to read.
     *
     * @param manifest the rule files the generation reads
     * @return each client with a complete folder for its current rows; none when anything fails
     */
    public static Map<RetroSource, PackResources> open(RetroManifest manifest) {
        try {
            Path root = root();
            return root == null ? Map.of() : open(root, manifest.textures());
        } catch (Throwable t) {
            VetsLogger.warn("Retro pack: could not open the old clients' files", t);
            return Map.of();
        }
    }

    /**
     * {@link #open(RetroManifest)} under {@code root}.
     *
     * @param root the folder every client's folders are in
     * @param sections the texture sections
     * @return each client with a complete folder for its current rows
     */
    // Package-private for unit tests. See OldClientsTest.
    static Map<RetroSource, PackResources> open(Path root, TextureSections sections) {
        Map<RetroSource, PackResources> packs = new EnumMap<>(RetroSource.class);
        for (RetroSource client : sections.oldClients().keySet()) {
            Path folder = folder(root, client, hash(client, sections));
            if (Files.isDirectory(folder)) {
                packs.put(
                        client,
                        PackFiles.folder(
                                folder,
                                "vetsmod_retro_" + client.yamlName(),
                                "Minecraft " + client.yamlName()));
            }
        }
        return packs;
    }

    /**
     * The clients that have rows but no folder for them yet.
     *
     * @param root the folder every client's folders are in
     * @param sections the texture sections
     * @return those clients, in {@link RetroSource} order
     */
    static List<RetroSource> missing(Path root, TextureSections sections) {
        List<RetroSource> missing = new ArrayList<>();
        for (RetroSource client : sections.oldClients().keySet()) {
            if (!sections.oldClients().get(client).isEmpty()
                    && !Files.isDirectory(folder(root, client, hash(client, sections)))) {
                missing.add(client);
            }
        }
        return missing;
    }

    /**
     * The folder of a client's files for its current rows.
     *
     * @param root the folder every client's folders are in
     * @param client the client
     * @param hash its {@link #hash}
     * @return {@code <root>/<version>/<hash>}
     */
    static Path folder(Path root, RetroSource client, String hash) {
        return root.resolve(client.yamlName()).resolve(hash);
    }

    /**
     * What a client's folder holds, as a name: the SHA-1 of the jar, this format, the client's
     * {@code old_clients} rows and every {@code old_client_clears} row.
     *
     * @param client the client
     * @param sections the texture sections
     * @return 40 lower-case hex digits
     */
    static String hash(RetroSource client, TextureSections sections) {
        StringBuilder text = new StringBuilder();
        text.append(FORMAT).append('\n').append(JARS.get(client).sha1()).append('\n');
        for (TextureSections.OldClientRow row :
                sections.oldClients().getOrDefault(client, List.of())) {
            text.append(row.textures().pattern()).append('\t').append(row.file()).append('\n');
        }
        for (TextureSections.OldClientClear clear : sections.oldClientClears()) {
            text.append(clear.textures().pattern());
            clear.rects().forEach(rect -> text.append('\t').append(rect.toYaml()));
            text.append('\n');
        }
        return sha1(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The SHA-1 of some bytes.
     *
     * @param bytes the bytes
     * @return 40 lower-case hex digits
     */
    static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-1", e);
        }
    }

    /**
     * Writes a client's files under a temporary name, then gives the folder its name, then deletes
     * the client's other folders: those of older rows, and temporary ones a day old. A folder that
     * already has the name, which another game sharing the folder can have written, is kept.
     *
     * @param root the folder every client's folders are in
     * @param client the client
     * @param hash its {@link #hash}
     * @param files each file by its path in the folder, such as
     *     {@code assets/minecraft/textures/block/white_wool.png}
     * @throws IOException when the folder cannot be written
     */
    static void install(Path root, RetroSource client, String hash, Map<String, byte[]> files)
            throws IOException {
        Path target = folder(root, client, hash);
        Path temporary = target.resolveSibling(hash + TEMPORARY + UUID.randomUUID());
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
        deleteStale(target.getParent(), hash);
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
    private static void deleteStale(Path clientFolder, String hash) {
        Instant abandoned = Instant.now().minus(ABANDONED);
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(clientFolder)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                if (name.equals(hash)) continue;
                if (name.contains(TEMPORARY)) {
                    FileTime modified = Files.getLastModifiedTime(folder);
                    if (modified.toInstant().isAfter(abandoned)) continue;
                }
                delete(folder);
            }
        } catch (IOException | RuntimeException e) {
            VetsLogger.debug("Retro: left some stale old-client folders", e);
        }
    }

    /** Deletes a folder and everything in it; what cannot be deleted stays. */
    private static void delete(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    VetsLogger.debug("Retro: could not delete {}", path, e);
                }
            }
        } catch (IOException | RuntimeException e) {
            VetsLogger.debug("Retro: could not delete {}", folder, e);
        }
    }
}
