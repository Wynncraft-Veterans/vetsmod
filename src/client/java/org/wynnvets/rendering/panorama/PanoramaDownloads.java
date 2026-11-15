package org.wynnvets.rendering.panorama;

import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.AtomicFolders;
import org.wynnvets.util.HttpClients;

/**
 * Downloads the {@link HostedPanorama}s, each the first time something asks for it, and keeps them
 * in {@code vetsmod/panoramas/<set>/<hash>} in the game folder.
 *
 * <p>A set is fetched on a thread of its own, its six faces one after another, each checked
 * against its pinned size and SHA-256 before the folder is written, whole or not at all
 * ({@link AtomicFolders}). It never throws: a set that fails is not tried again until the next
 * start-up, and whatever asked for it draws its fallback meanwhile. Reading a set back checks
 * every face again, since a file on disk can change: a folder that no longer matches its pins is
 * deleted, so the next request downloads it again, and a folder that cannot be read, or deleted,
 * counts as failed until the next start-up, so nothing reads it again and again.</p>
 */
public final class PanoramaDownloads {

    /** How long one face may take, from the request to its last byte. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Set<HostedPanorama> RUNNING = ConcurrentHashMap.newKeySet();

    /** The sets whose download failed this session. */
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
        /** Its download failed this session; the next start-up tries again. */
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

    /** Forgets every download running or failed this session. Tests only. */
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
     * Starts downloading a set, unless it is here already, is being downloaded, or failed this
     * session.
     *
     * @param panorama the set
     * @param done runs on the download's thread when it ends, with whether it wrote the set
     * @return whether a download started, so {@code done} will run
     */
    public static boolean start(HostedPanorama panorama, Consumer<Boolean> done) {
        try {
            Path root = root();
            if (root == null
                    || FAILED.contains(panorama)
                    || Files.isDirectory(folder(root, panorama))
                    || !RUNNING.add(panorama)) {
                return false;
            }
            Thread thread =
                    new Thread(
                            () -> {
                                boolean wrote = false;
                                try {
                                    wrote = download(root, panorama);
                                } finally {
                                    RUNNING.remove(panorama);
                                    done.accept(wrote);
                                }
                            },
                            "vetsmod-panorama-" + panorama.folderName());
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (Throwable t) {
            RUNNING.remove(panorama);
            VetsLogger.warn("Could not start downloading the {} panorama", panorama, t);
            return false;
        }
    }

    /** Downloads and keeps a set; a failure is logged once and latched until the next start-up. */
    private static boolean download(Path root, HostedPanorama panorama) {
        long start = System.nanoTime();
        try {
            AtomicFolders.install(
                    folder(root, panorama),
                    fetch(panorama.faces(), panorama::path, PanoramaDownloads::get));
            VetsLogger.info(
                    "Downloaded the {} panorama in {} ms",
                    panorama,
                    (System.nanoTime() - start) / 1_000_000);
            return true;
        } catch (Throwable t) {
            FAILED.add(panorama);
            VetsLogger.warn(
                    "Could not download the {} panorama; its fallback shows until the next"
                            + " start-up tries again",
                    panorama,
                    t);
            return false;
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
        if (!HostedPanorama.sha256(bytes).equals(face.sha256())) {
            throw new NotPinnedException(face.uri() + " is not the pinned file");
        }
        return bytes;
    }

    /**
     * One face over the mod's shared HTTP client. Only a {@code 200} declaring exactly the pinned
     * length is read, so no other answer's body is kept. The request's own timeout ends when the
     * headers arrive, so the whole exchange waits at most {@link #TIMEOUT}, after which it is
     * cancelled.
     */
    // Package-private for unit tests. See PanoramaDownloadsTest.
    static byte[] get(HostedPanorama.Face face) throws IOException {
        URI uri = face.uri();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        CompletableFuture<HttpResponse<byte[]>> exchange =
                HttpClients.standard()
                        .sendAsync(
                                request,
                                answer ->
                                        answer.statusCode() == 200
                                                        && answer.headers()
                                                                        .firstValueAsLong(
                                                                                "Content-Length")
                                                                        .orElse(-1)
                                                                == face.size()
                                                ? HttpResponse.BodySubscribers.ofByteArray()
                                                : HttpResponse.BodySubscribers.replacing(null));
        HttpResponse<byte[]> response;
        try {
            response = exchange.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted reading " + uri, e);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new IOException(uri + " took over " + TIMEOUT, e);
        } catch (ExecutionException e) {
            throw new IOException(uri + " failed", e.getCause());
        }
        if (response.statusCode() != 200 || response.body() == null) {
            throw new IOException(
                    uri
                            + " answered "
                            + response.statusCode()
                            + " with "
                            + response.headers().firstValue("Content-Length").orElse("no")
                            + " bytes");
        }
        return response.body();
    }

    /**
     * A set's faces as they are kept, each checked against its pin. A folder whose files are not
     * the pinned ones is deleted, so the next {@link #start} downloads it again; one that cannot be
     * read, or deleted, makes the set {@link State#FAILED} until the next start-up.
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
        if (!Files.isDirectory(folder)) return null;
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
    static Path root() {
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
    static Path folder(Path root, HostedPanorama panorama) {
        return root.resolve(panorama.folderName()).resolve(panorama.hash());
    }
}
