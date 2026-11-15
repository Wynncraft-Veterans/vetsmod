package org.wynnvets.rendering.panorama;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wynnvets.util.AtomicFolders;
import org.wynnvets.util.Digests;
import org.wynnvets.util.LocalHttpServer;

/**
 * Tests for {@link PanoramaDownloads}' checks, over faces made here and pinned to themselves: a
 * download keeps a face only when it is the pinned file; reading a kept set checks each face
 * again, deleting a set that changed or lacks a face, and marking failed, and not reading again,
 * one that cannot be read; and a face is read only from a {@code 200} declaring its pinned
 * length, against a server on this machine. The download's threads need a running client and
 * are not tested.
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
                            Digests.sha256(bytes)));
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
        // NotPinnedException is what read() deletes a set on, rather than marking it failed.
        assertThrows(
                PanoramaDownloads.NotPinnedException.class,
                () -> PanoramaDownloads.fetch(FACES, PATH, face -> new byte[0]),
                "the wrong size");
        assertThrows(
                PanoramaDownloads.NotPinnedException.class,
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
        assertThrows(
                PanoramaDownloads.NotPinnedException.class,
                () -> PanoramaDownloads.readFolder(folder, FACES, PATH));
        Files.delete(folder.resolve(PATH.apply(3)));
        assertThrows(
                NoSuchFileException.class, () -> PanoramaDownloads.readFolder(folder, FACES, PATH));
    }

    @Test
    void aSetThatIsMissingOrChangedReadsAsNothingAndAChangedOneIsDeleted(@TempDir Path root)
            throws IOException {
        assertNull(PanoramaDownloads.read(root, HostedPanorama.VETLAS), "not downloaded yet");
        assertEquals(
                PanoramaDownloads.State.MISSING,
                PanoramaDownloads.state(root, HostedPanorama.VETLAS));

        Path folder = writeVetlasFolder(root);
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
    void aSetThatLacksAFaceIsDeletedSoItIsDownloadedAgain(@TempDir Path root) throws IOException {
        Path folder = writeVetlasFolder(root);
        // Face 0 is read first, so its absence is what the read meets.
        Files.delete(folder.resolve(HostedPanorama.VETLAS.path(0)));

        assertNull(PanoramaDownloads.read(root, HostedPanorama.VETLAS));
        assertFalse(Files.exists(folder), "an incomplete set is deleted");
        assertEquals(
                PanoramaDownloads.State.MISSING,
                PanoramaDownloads.state(root, HostedPanorama.VETLAS),
                "not failed: it can be downloaded again at once");
    }

    @Test
    void aSetThatCannotBeReadIsKeptAndCountsAsFailedSoNothingReadsItAgainAndAgain(
            @TempDir Path root) throws IOException {
        Path folder = writeVetlasFolder(root);
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

        // Readable again, but a failed set is not read, so it is not found changed and deleted.
        Files.delete(face);
        Files.write(face, BYTES.get(0));
        assertNull(PanoramaDownloads.read(root, HostedPanorama.VETLAS));
        assertTrue(Files.isDirectory(folder), "not read again");
    }

    /**
     * Writes a folder at Vetlas's place for its current pins, holding {@link #BYTES}, which are
     * not Vetlas's pinned faces.
     */
    private static Path writeVetlasFolder(Path root) throws IOException {
        Path folder = PanoramaDownloads.folder(root, HostedPanorama.VETLAS);
        Files.createDirectories(folder.resolve(HostedPanorama.VETLAS.path(0)).getParent());
        for (int face = 0; face < 6; face++) {
            Files.write(folder.resolve(HostedPanorama.VETLAS.path(face)), BYTES.get(face));
        }
        return folder;
    }

    @Test
    void aFaceIsReadOnlyFromAnOkThatDeclaresItsPinnedLength() throws IOException {
        byte[] png = "a face".getBytes(StandardCharsets.UTF_8);
        byte[] longer = "a longer face".getBytes(StandardCharsets.UTF_8);
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.answer(
                            "/ok.png",
                            exchange -> LocalHttpServer.send(exchange, 200, png, png.length))
                    .answer(
                            "/longer.png",
                            exchange -> LocalHttpServer.send(exchange, 200, longer, longer.length))
                    .answer("/chunked.png", exchange -> LocalHttpServer.send(exchange, 200, png, 0))
                    .answer(
                            "/missing.png",
                            exchange -> LocalHttpServer.send(exchange, 404, new byte[0], -1))
                    .answer(
                            "/moved.png",
                            exchange -> {
                                exchange.getResponseHeaders().add("Location", "/ok.png");
                                LocalHttpServer.send(exchange, 302, new byte[0], -1);
                            })
                    .start();

            assertArrayEquals(
                    png, PanoramaDownloads.get(served(server.uri("/ok.png"), png.length)));
            for (String refused :
                    List.of("/longer.png", "/chunked.png", "/missing.png", "/moved.png")) {
                assertThrows(
                        IOException.class,
                        () -> PanoramaDownloads.get(served(server.uri(refused), png.length)),
                        refused);
            }
        }
    }

    /** A face at {@code uri} pinned to {@code size} bytes; its hash is not what get() checks. */
    private static HostedPanorama.Face served(URI uri, long size) {
        return new HostedPanorama.Face(uri, size, "0".repeat(64));
    }
}
