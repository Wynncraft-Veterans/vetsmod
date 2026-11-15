package org.wynnvets.retro.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.util.Util;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.rendering.image.PixelDrawing;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.manifest.TextureSections;
import org.wynnvets.retro.rules.OldClientRules;
import org.wynnvets.util.AtomicFolders;
import org.wynnvets.util.Digests;

/**
 * Downloads the files the {@code old_clients} rows take from each old client, once, into
 * {@link OldClients}' folders. Only the jar's central directory and the entries the rows name are
 * read, as byte ranges of the jar on Mojang's server, so a client costs its directory and a few
 * dozen small textures rather than the whole jar.
 *
 * <p>It runs on Minecraft's download threads, never in a resource reload, one fetch at a time. It
 * catches everything: a client that fails keeps no folder, so its textures come from the next
 * source, and it is not fetched again until the next start-up. Nothing here latches retro.</p>
 */
public final class OldClientFetch {

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** The clients whose fetch failed this session. */
    private static final Set<RetroSource> FAILED = ConcurrentHashMap.newKeySet();

    private OldClientFetch() {}

    /**
     * How a fetch went.
     *
     * @param wrote whether it wrote any client's folder
     * @param complete whether it fetched every client it set out to
     */
    public record Result(boolean wrote, boolean complete) {}

    /**
     * What one client's fetch writes, and the rows that wrote nothing.
     *
     * @param files each file by its path in the client's folder
     * @param emptyRows the rows, as their patterns, whose old client has none of their files
     */
    record Fetched(Map<String, byte[]> files, List<String> emptyRows) {}

    /**
     * Starts fetching the files of every old client whose folder is missing and whose fetch has
     * not failed this session, unless there is none or a fetch is already running.
     *
     * @param manifest the rule file, parsed
     * @param vanilla Minecraft's vanilla pack: the texture ids there are, and a blank's size
     * @param done runs on the fetch's thread when it ends
     * @return whether a fetch started, so {@code done} will run
     */
    public static boolean start(
            RetroManifest manifest, PackResources vanilla, Consumer<Result> done) {
        boolean taken = false;
        try {
            Path root = OldClients.root();
            if (root == null) return false;
            TextureSections sections = manifest.textures();
            List<RetroSource> missing = OldClients.missing(root, sections);
            missing.removeIf(FAILED::contains);
            if (missing.isEmpty() || !RUNNING.compareAndSet(false, true)) return false;
            taken = true;
            CompletableFuture.supplyAsync(
                            () -> fetchAll(root, sections, missing, vanilla),
                            Util.nonCriticalIoPool())
                    .whenComplete(
                            (result, t) -> {
                                RUNNING.set(false);
                                done.accept(result != null ? result : new Result(false, false));
                            });
            return true;
        } catch (Throwable t) {
            if (taken) RUNNING.set(false);
            VetsLogger.warn("Retro: could not start fetching the old clients' textures", t);
            return false;
        }
    }

    /** Fetches each client in turn; a failure costs only its client. */
    private static Result fetchAll(
            Path root, TextureSections sections, List<RetroSource> clients, PackResources vanilla) {
        long start = System.nanoTime();
        Set<String> textureIds;
        try {
            textureIds = PackFiles.textureIds(vanilla);
        } catch (Throwable t) {
            FAILED.addAll(clients);
            VetsLogger.warn("Retro: could not list vanilla's textures for the old clients", t);
            return new Result(false, false);
        }
        boolean wrote = false;
        for (RetroSource client : clients) {
            try {
                OldClients.Jar jar = OldClients.JARS.get(client);
                Fetched fetched =
                        fetch(
                                jar,
                                sections.oldClients().get(client),
                                sections.oldClientClears(),
                                textureIds,
                                textureId -> vanillaTexture(vanilla, textureId),
                                HttpRanges.of(jar.uri()));
                for (String row : fetched.emptyRows()) {
                    VetsLogger.warn(
                            "Retro: old_clients {} row '{}' names no file of that client",
                            client.yamlName(),
                            row);
                }
                AtomicFolders.install(
                        OldClients.folder(root, client, OldClients.hash(client, sections)),
                        fetched.files());
                wrote = true;
                VetsLogger.info(
                        "Retro: took {} files from Minecraft {}",
                        fetched.files().size(),
                        client.yamlName());
            } catch (Throwable t) {
                FAILED.add(client);
                VetsLogger.warn(
                        "Retro: could not fetch Minecraft {}'s textures; the next source draws"
                                + " them until the next start-up",
                        client.yamlName(),
                        t);
            }
        }
        VetsLogger.debug(
                "Retro: old clients fetched in {} ms", (System.nanoTime() - start) / 1_000_000);
        return new Result(wrote, clients.stream().noneMatch(FAILED::contains));
    }

    /** Vanilla's file of a texture, or {@code null}. */
    private static byte[] vanillaTexture(PackResources vanilla, String textureId) {
        Identifier file = PackFiles.textureFile(textureId);
        if (file == null) return null;
        try {
            return PackFiles.read(vanilla.getResource(PackFiles.TYPE, file));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * One client's files: its central directory, checked against the pin, then each file its rows
     * name with the {@code .mcmeta} beside it, each written at every texture id it stands for, with
     * that id's {@code old_client_clears} rectangles cleared.
     *
     * @param jar the client's jar
     * @param rows the client's {@code old_clients} rows
     * @param clears the {@code old_client_clears} rows
     * @param textureIds the texture ids there are today
     * @param today today's file of a texture, for a blank's size: its PNG, or {@code null} when
     *     there is none
     * @param ranges the jar's bytes
     * @return the files, by their paths in the client's folder
     * @throws IOException when the directory cannot be read or is not the pinned one, or a file
     *     cannot be read whole
     */
    // Package-private for unit tests. See OldClientFetchTest.
    static Fetched fetch(
            OldClients.Jar jar,
            List<TextureSections.OldClientRow> rows,
            List<TextureSections.OldClientClear> clears,
            Set<String> textureIds,
            Function<String, byte[]> today,
            HttpRanges ranges)
            throws IOException {
        byte[] directory = ranges.get(jar.directoryOffset(), jar.directorySize());
        if (!Digests.sha1(directory).equals(jar.directorySha1())) {
            throw new IOException("the central directory is not the pinned one");
        }
        Map<String, ZipDirectory.Entry> entries = ZipDirectory.parse(directory);
        Map<String, byte[]> read = new LinkedHashMap<>();
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<Integer> rowsWithFiles = new TreeSet<>();
        for (OldClientRules.Target target : OldClientRules.targets(rows, textureIds)) {
            String path = texturePath(target.textureId());
            if (path == null) continue;
            byte[] png;
            byte[] mcmeta = null;
            if (target.file() == null) {
                byte[] todays = today.apply(target.textureId());
                if (todays == null) continue;
                int[] size = PngCodec.pngSize(todays);
                png = PngCodec.png(Pixels.blank(size[0], size[1]));
            } else {
                String entry = texturePath(target.file());
                ZipDirectory.Entry found = entry == null ? null : entries.get(entry);
                if (found == null) continue;
                png = readOnce(read, found, ranges, jar);
                ZipDirectory.Entry meta = entries.get(entry + PackResources.METADATA_EXTENSION);
                if (meta != null) mcmeta = readOnce(read, meta, ranges, jar);
                png = cleared(png, OldClientRules.clears(clears, target.textureId()));
            }
            rowsWithFiles.add(target.row());
            files.put(path, png);
            if (mcmeta != null) files.put(path + PackResources.METADATA_EXTENSION, mcmeta);
        }
        List<String> emptyRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!rowsWithFiles.contains(i)) emptyRows.add(rows.get(i).textures().pattern());
        }
        return new Fetched(files, emptyRows);
    }

    /** An entry's bytes, read the first time it is asked for. */
    private static byte[] readOnce(
            Map<String, byte[]> read,
            ZipDirectory.Entry entry,
            HttpRanges ranges,
            OldClients.Jar jar)
            throws IOException {
        byte[] bytes = read.get(entry.name());
        if (bytes == null) {
            bytes = ZipDirectory.read(entry, ranges, jar.directoryOffset());
            read.put(entry.name(), bytes);
        }
        return bytes;
    }

    /** {@code png} with each rectangle made fully transparent; {@code png} itself for none. */
    private static byte[] cleared(byte[] png, List<Rect> rects) throws IOException {
        if (rects.isEmpty()) return png;
        Pixels image = PngCodec.decode(png);
        for (Rect rect : rects) PixelDrawing.paint(image, rect, 0);
        return PngCodec.png(image);
    }

    /** A texture's path in a pack or a jar; {@code null} for no id. */
    private static String texturePath(String textureId) {
        Identifier file = PackFiles.textureFile(textureId);
        return file == null ? null : PackFiles.path(file);
    }
}
