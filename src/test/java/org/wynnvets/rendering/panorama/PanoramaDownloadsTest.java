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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wynnvets.util.AtomicFolders;

/**
 * Tests for {@link PanoramaDownloads}' checks, over faces made here and pinned to themselves: a
 * download keeps a face only when it is the pinned file, and reading a kept set checks each face
 * again. Fetching over the network, the session's state and the threads need a running client and
 * are not tested.
 */
class PanoramaDownloadsTest {

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

        Path folder = PanoramaDownloads.folder(root, HostedPanorama.VETLAS);
        Files.createDirectories(folder.resolve(HostedPanorama.VETLAS.path(0)).getParent());
        for (int face = 0; face < 6; face++) {
            Files.write(folder.resolve(HostedPanorama.VETLAS.path(face)), BYTES.get(face));
        }
        assertTrue(Files.isDirectory(folder));

        assertNull(PanoramaDownloads.read(root, HostedPanorama.VETLAS), "not the pinned files");
        assertFalse(Files.exists(folder), "deleted, so the next request downloads it again");
    }

    @Test
    void aSetsFolderIsNamedByItsSetAndItsPins(@TempDir Path root) {
        assertEquals(
                root.resolve("old_detlas").resolve(HostedPanorama.OLD_DETLAS.hash()),
                PanoramaDownloads.folder(root, HostedPanorama.OLD_DETLAS));
    }
}
