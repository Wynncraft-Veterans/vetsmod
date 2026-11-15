package org.wynnvets.rendering.panorama;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.AtomicFolders;
import org.wynnvets.util.Digests;
import org.wynnvets.util.HttpClients;

/**
 * Downloads the {@link HostedPanorama}s, each the first time something asks for it, and keeps them
 * in {@code vetsmod/panoramas/<set>/<hash>} in the game folder.
 *
 * <p>A set is fetched on Minecraft's download threads, its six faces one after another, each
 * checked against its pinned size and SHA-256 before the folder is written, whole or not at all
 * ({@link AtomicFolders}). It never throws: a set that fails is not tried again until the next
 * start-up, and whatever asked for it draws its fallback meanwhile. Reading a set back checks
 * every face again, since a file on disk can change: a folder that lacks a face or no longer
 * matches its pins is deleted, so the next request downloads it again, and a folder that cannot
 * be read, or deleted, counts as failed until the next start-up, so nothing reads it again and
 * again.</p>
 */
public final class PanoramaDownloads {

    /** How long one face may take, from the request to its last byte. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Set<HostedPanorama> RUNNING = ConcurrentHashMap.newKeySet();

    /** The sets whose download failed, or whose kept folder could not be read, this session. */
    private static final Set<HostedPanorama> FAILED = ConcurrentHashMap.newKeySet();

    private PanoramaDownloads() {}

    /** A face that is not its pinned file: the wrong size or the wrong bytes. */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static final class NotPinnedException extends IOException {
        NotPinnedException(String message) {
            super(message);
        }
    }

    /** Where a set stands. */
    public enum State {
        /** Its folder is here: {@link #read} gives its faces unless they have changed. */
        HERE,
        /** It is being downloaded. */
        DOWNLOADING,
        /**
         * Its download failed, or its kept folder could not be read, this session; the next
         * start-up tries again.
         */
        FAILED,
        /** It is not here, and nothing is downloading it. */
        MISSING
    }

    /**
     * Where a set stands. Only {@link State#HERE} and {@link State#MISSING} look at the disk.
     *
     * @param panorama the set
     * @return its state
     */
    public static State state(HostedPanorama panorama) {
        return state(root(), panorama);
    }

    /**
     * {@link #state(HostedPanorama)} under {@code root}.
     *
     * @param root the folder every set's folders are in, or {@code null} when there is none
     * @param panorama the set
     * @return its state
     */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static State state(Path root, HostedPanorama panorama) {
        if (RUNNING.contains(panorama)) return State.DOWNLOADING;
        if (FAILED.contains(panorama)) return State.FAILED;
        return root != null && Files.isDirectory(folder(root, panorama))
                ? State.HERE
                : State.MISSING;
    }

    // Test-only: forgets every download running or failed this session.
    static void clearForTesting() {
        RUNNING.clear();
        FAILED.clear();
    }

    /** Downloads one face. */
    @FunctionalInterface
    interface Fetcher {
        /**
         * @param face the face
         * @return its bytes, exactly its pinned size
         * @throws IOException when it cannot be downloaded whole
         */
        byte[] get(HostedPanorama.Face face) throws IOException;
    }

    /**
     * Starts downloading a set on Minecraft's download threads, unless it is not
     * {@link State#MISSING}: here already, being downloaded, or failed this session.
     *
     * @param panorama the set
     */
    public static void start(HostedPanorama panorama) {
        boolean taken = false;
        try {
            Path root = root();
            if (root == null || state(root, panorama) != State.MISSING || !RUNNING.add(panorama)) {
                return;
            }
            taken = true;
            CompletableFuture.runAsync(() -> download(root, panorama), Util.nonCriticalIoPool())
                    .whenComplete((done, t) -> RUNNING.remove(panorama));
        } catch (Throwable t) {
            if (taken) RUNNING.remove(panorama);
            VetsLogger.warn("Could not start downloading the {} panorama", panorama, t);
        }
    }

    /** Downloads and keeps a set; a failure is logged once and latched until the next start-up. */
    private static void download(Path root, HostedPanorama panorama) {
        long start = System.nanoTime();
        try {
            AtomicFolders.install(
                    folder(root, panorama),
                    fetch(panorama.faces(), panorama::path, PanoramaDownloads::get));
            VetsLogger.info(
                    "Downloaded the {} panorama in {} ms",
                    panorama,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            FAILED.add(panorama);
            VetsLogger.warn(
                    "Could not download the {} panorama; its fallback shows until the next"
                            + " start-up tries again",
                    panorama,
                    t);
        }
    }

    /**
     * Faces, each downloaded and checked against its pin.
     *
     * @param faces the faces, face 0 first
     * @param path where each face is kept in its folder, by its number
     * @param fetcher how a face is downloaded
     * @return each face's bytes by its path in the folder
     * @throws IOException when a face cannot be downloaded or is not the pinned file
     */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static Map<String, byte[]> fetch(
            List<HostedPanorama.Face> faces, IntFunction<String> path, Fetcher fetcher)
            throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            HostedPanorama.Face face = faces.get(i);
            files.put(path.apply(i), checked(face, fetcher.get(face)));
        }
        return files;
    }

    /**
     * Faces as a folder keeps them, each checked against its pin.
     *
     * @param folder the folder
     * @param faces the faces, face 0 first
     * @param path where each face is kept in the folder, by its number
     * @return each face's bytes, face 0 first
     * @throws IOException when a face cannot be read or is not the pinned file
     */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static List<byte[]> readFolder(
            Path folder, List<HostedPanorama.Face> faces, IntFunction<String> path)
            throws IOException {
        List<byte[]> read = new ArrayList<>();
        for (int i = 0; i < faces.size(); i++) {
            read.add(checked(faces.get(i), Files.readAllBytes(folder.resolve(path.apply(i)))));
        }
        return Collections.unmodifiableList(read);
    }

    /**
     * A face's bytes, if they are the pinned file.
     *
     * @throws NotPinnedException when their size or SHA-256 is not the pin's
     */
    private static byte[] checked(HostedPanorama.Face face, byte[] bytes)
            throws NotPinnedException {
        if (bytes == null || bytes.length != face.size()) {
            throw new NotPinnedException(
                    face.uri()
                            + " is "
                            + (bytes == null ? 0 : bytes.length)
                            + " bytes, not "
                            + face.size());
        }
        if (!Digests.sha256(bytes).equals(face.sha256())) {
            throw new NotPinnedException(face.uri() + " is not the pinned file");
        }
        return bytes;
    }

    /**
     * One face over the mod's shared HTTP client, within {@link #TIMEOUT}
     * ({@link HttpClients#bytesWithin}). Only a {@code 200} declaring exactly the pinned length has
     * its body read, so no other answer's body is kept.
     */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static byte[] get(HostedPanorama.Face face) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(face.uri()).timeout(TIMEOUT).GET().build();
        HttpResponse<byte[]> response =
                HttpClients.bytesWithin(
                        request,
                        answer ->
                                answer.statusCode() == 200
                                        && answer.headers()
                                                        .firstValueAsLong("Content-Length")
                                                        .orElse(-1)
                                                == face.size(),
                        TIMEOUT);
        if (response.body() == null) {
            throw new IOException(
                    face.uri()
                            + " answered "
                            + response.statusCode()
                            + " with "
                            + response.headers().firstValue("Content-Length").orElse("no")
                            + " bytes");
        }
        return response.body();
    }

    /**
     * A set's faces as they are kept, each checked against its pin. A folder that lacks a face or
     * whose files are not the pinned ones is deleted, so the next {@link #start} downloads it
     * again; one that cannot be read, or deleted, makes the set {@link State#FAILED} until the next
     * start-up, and a failed set is not read.
     *
     * @param panorama the set
     * @return its six faces, face 0 first; {@code null} when it is not here whole and unchanged
     */
    public static List<byte[]> read(HostedPanorama panorama) {
        try {
            Path root = root();
            return root == null ? null : read(root, panorama);
        } catch (Throwable t) {
            FAILED.add(panorama);
            VetsLogger.warn("Could not read the {} panorama", panorama, t);
            return null;
        }
    }

    /**
     * {@link #read(HostedPanorama)} under {@code root}.
     *
     * @param root the folder every set's folders are in
     * @param panorama the set
     * @return its six faces, or {@code null}
     */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static List<byte[]> read(Path root, HostedPanorama panorama) {
        Path folder = folder(root, panorama);
        if (FAILED.contains(panorama) || !Files.isDirectory(folder)) return null;
        try {
            return readFolder(folder, panorama.faces(), panorama::path);
        } catch (NotPinnedException | NoSuchFileException e) {
            VetsLogger.warn(
                    "The {} panorama's files are not the pinned ones; downloading it again",
                    panorama,
                    e);
            AtomicFolders.delete(folder);
            if (Files.exists(folder)) FAILED.add(panorama);
            return null;
        } catch (IOException | RuntimeException e) {
            FAILED.add(panorama);
            VetsLogger.warn(
                    "Could not read the {} panorama; its fallback shows until the next start-up",
                    panorama,
                    e);
            return null;
        }
    }

    /**
     * A set's folder, if its files are here and are the pinned ones: what retro opens as a pack.
     *
     * @param panorama the set
     * @return the folder, or {@code null}
     */
    public static Path verified(HostedPanorama panorama) {
        try {
            Path root = root();
            return root == null || read(root, panorama) == null ? null : folder(root, panorama);
        } catch (Throwable t) {
            VetsLogger.warn("Could not check the {} panorama", panorama, t);
            return null;
        }
    }

    /**
     * The folder every set's folders are in.
     *
     * @return {@code vetsmod/panoramas} in the game folder, or {@code null} when it cannot be found
     */
    private static Path root() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("vetsmod").resolve("panoramas");
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.debug("No game folder for the downloaded panoramas", e);
            return null;
        }
    }

    /**
     * A set's folder for its current pins.
     *
     * @param root the folder every set's folders are in
     * @param panorama the set
     * @return {@code <root>/<set>/<hash>}
     */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static Path folder(Path root, HostedPanorama panorama) {
        return root.resolve(panorama.folderName()).resolve(panorama.hash());
    }
}
