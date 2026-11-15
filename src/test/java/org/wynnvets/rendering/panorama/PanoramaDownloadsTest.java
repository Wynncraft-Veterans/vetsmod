package org.wynnvets.rendering.panorama;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wynnvets.util.AtomicFolders;

/**
 * Tests for {@link PanoramaDownloads}' checks, over faces made here and pinned to themselves: a
 * download keeps a face only when it is the pinned file, and reading a kept set checks each face
 * again, deleting a set that changed and marking failed one that cannot be read; and a face is
 * read only from a {@code 200} declaring its pinned length, against a server on this machine.
 * The download's threads need a running client and are not tested.
 */
class PanoramaDownloadsTest {

    @AfterEach
    void forgetTheSession() {
        PanoramaDownloads.clearForTesting();
    }

    private static final IntFunction<String> PATH = face -> "faces/" + face + ".png";

    /** The faces' bytes, each pinned to itself. */
    private static final List<byte[]> BYTES = new ArrayList<>();

    private static final List<HostedPanorama.Face> FACES = new ArrayList<>();

    static {
        for (int face = 0; face < 6; face++) {
            byte[] bytes = ("face " + face).getBytes(StandardCharsets.UTF_8);
            BYTES.add(bytes);
            FACES.add(
                    new HostedPanorama.Face(
                            URI.create("https://i.wynnvets.org/face" + face + ".png"),
                            bytes.length,
                            HostedPanorama.sha256(bytes)));
        }
    }

    /** Serves each face's pinned bytes. */
    private static byte[] serve(HostedPanorama.Face face) {
        return BYTES.get(FACES.indexOf(face)).clone();
    }

    @Test
    void aDownloadKeepsEachFaceAtItsPath() throws IOException {
        Map<String, byte[]> files =
                PanoramaDownloads.fetch(FACES, PATH, PanoramaDownloadsTest::serve);

        assertEquals(
                List.of(
                        PATH.apply(0),
                        PATH.apply(1),
                        PATH.apply(2),
                        PATH.apply(3),
                        PATH.apply(4),
                        PATH.apply(5)),
                List.copyOf(files.keySet()));
        for (int face = 0; face < 6; face++) {
            assertArrayEquals(BYTES.get(face), files.get(PATH.apply(face)));
        }
    }

    @Test
    void aFaceThatIsNotThePinnedFileFailsTheWholeDownload() {
        assertThrows(
                IOException.class,
                () -> PanoramaDownloads.fetch(FACES, PATH, face -> new byte[0]),
                "the wrong size");
        assertThrows(
                IOException.class,
                () ->
                        PanoramaDownloads.fetch(
                                FACES,
                                PATH,
                                face -> {
                                    byte[] bytes = serve(face);
                                    bytes[0] ^= 1;
                                    return bytes;
                                }),
                "the right size, but other bytes");
        assertThrows(
                IOException.class,
                () ->
                        PanoramaDownloads.fetch(
                                FACES,
                                PATH,
                                face -> {
                                    throw new IOException("offline");
                                }),
                "a face that cannot be downloaded");
    }

    @Test
    void aKeptSetIsReadBackOnlyWhileEveryFaceIsStillThePinnedOne(@TempDir Path root)
            throws IOException {
        Path folder = root.resolve("set");
        AtomicFolders.install(
                folder, PanoramaDownloads.fetch(FACES, PATH, PanoramaDownloadsTest::serve));

        List<byte[]> read = PanoramaDownloads.readFolder(folder, FACES, PATH);
        for (int face = 0; face < 6; face++) assertArrayEquals(BYTES.get(face), read.get(face));

        Files.write(folder.resolve(PATH.apply(3)), "changed".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PanoramaDownloads.readFolder(folder, FACES, PATH));
        Files.delete(folder.resolve(PATH.apply(3)));
        assertThrows(IOException.class, () -> PanoramaDownloads.readFolder(folder, FACES, PATH));
    }

    @Test
    void aShippedSetThatIsMissingOrChangedReadsAsNothingAndAChangedOneIsDeleted(@TempDir Path root)
            throws IOException {
        assertNull(PanoramaDownloads.read(root, HostedPanorama.VETLAS), "not downloaded yet");
        assertEquals(
                PanoramaDownloads.State.MISSING,
                PanoramaDownloads.state(root, HostedPanorama.VETLAS));

        Path folder = writeShipped(root, face -> BYTES.get(face));
        assertEquals(
                PanoramaDownloads.State.HERE, PanoramaDownloads.state(root, HostedPanorama.VETLAS));

        assertNull(PanoramaDownloads.read(root, HostedPanorama.VETLAS), "not the pinned files");
        assertFalse(Files.exists(folder), "deleted, so the next request downloads it again");
        assertEquals(
                PanoramaDownloads.State.MISSING,
                PanoramaDownloads.state(root, HostedPanorama.VETLAS),
                "not failed: it can be downloaded again at once");
    }

    @Test
    void aSetThatCannotBeReadIsKeptAndCountsAsFailedSoNothingReadsItAgainAndAgain(
            @TempDir Path root) throws IOException {
        Path folder = writeShipped(root, face -> BYTES.get(face));
        // A face that is a folder cannot be read, which is no sign that the download was bad.
        Path face = folder.resolve(HostedPanorama.VETLAS.path(0));
        Files.delete(face);
        Files.createDirectory(face);

        assertNull(PanoramaDownloads.read(root, HostedPanorama.VETLAS));
        assertTrue(Files.isDirectory(folder), "kept");
        assertEquals(
                PanoramaDownloads.State.FAILED,
                PanoramaDownloads.state(root, HostedPanorama.VETLAS),
                "until the next start-up");
    }

    /** Writes a folder for Vetlas's current pins, each face {@code bytes} of its number. */
    private static Path writeShipped(Path root, IntFunction<byte[]> bytes) throws IOException {
        Path folder = PanoramaDownloads.folder(root, HostedPanorama.VETLAS);
        Files.createDirectories(folder.resolve(HostedPanorama.VETLAS.path(0)).getParent());
        for (int face = 0; face < 6; face++) {
            Files.write(folder.resolve(HostedPanorama.VETLAS.path(face)), bytes.apply(face));
        }
        return folder;
    }

    @Test
    void aFaceIsReadOnlyFromAnOkThatDeclaresItsPinnedLength() throws IOException {
        byte[] png = "a face".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.png", exchange -> answer(exchange, 200, png, png.length));
        server.createContext(
                "/longer.png",
                exchange ->
                        answer(
                                exchange,
                                200,
                                "a longer face".getBytes(StandardCharsets.UTF_8),
                                13));
        server.createContext("/chunked.png", exchange -> answer(exchange, 200, png, 0));
        server.createContext("/missing.png", exchange -> answer(exchange, 404, new byte[0], -1));
        server.createContext(
                "/moved.png",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/ok.png");
                    answer(exchange, 302, new byte[0], -1);
                });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertArrayEquals(png, PanoramaDownloads.get(served(base + "/ok.png", png.length)));
            for (String refused :
                    List.of("/longer.png", "/chunked.png", "/missing.png", "/moved.png")) {
                assertThrows(
                        IOException.class,
                        () -> PanoramaDownloads.get(served(base + refused, png.length)),
                        refused);
            }
        } finally {
            server.stop(0);
        }
    }

    /** A face at {@code uri} pinned to {@code size} bytes; its hash is not what get() checks. */
    private static HostedPanorama.Face served(String uri, long size) {
        return new HostedPanorama.Face(URI.create(uri), size, "0".repeat(64));
    }

    /**
     * Answers with {@code body}: {@code length} bytes declared, 0 for chunked, -1 for no body.
     */
    private static void answer(HttpExchange exchange, int status, byte[] body, long length)
            throws IOException {
        exchange.sendResponseHeaders(status, length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (length != -1) out.write(body);
        }
    }

    @Test
    void aSetsFolderIsNamedByItsSetAndItsPins(@TempDir Path root) {
        assertEquals(
                root.resolve("old_detlas").resolve(HostedPanorama.OLD_DETLAS.hash()),
                PanoramaDownloads.folder(root, HostedPanorama.OLD_DETLAS));
    }
}
