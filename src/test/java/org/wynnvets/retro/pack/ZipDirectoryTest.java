package org.wynnvets.retro.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link ZipDirectory}, over zips built in memory. */
class ZipDirectoryTest {

    private static final byte[] TEXT =
            "a texture's bytes, repeated: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = {9, 8, 7, 6, 5};

    @Test
    void theDirectoryListsEveryEntryInOrderWithItsSizes() throws IOException {
        TestZip zip =
                TestZip.builder().deflated("a/one.png", TEXT).stored("b/two.png", OTHER).build();

        Map<String, ZipDirectory.Entry> entries = ZipDirectory.parse(zip.directory());

        assertEquals(List.of("a/one.png", "b/two.png"), List.copyOf(entries.keySet()));
        assertEquals(8, entries.get("a/one.png").method());
        assertEquals(TEXT.length, entries.get("a/one.png").size());
        assertEquals(0, entries.get("b/two.png").method());
        assertEquals(OTHER.length, entries.get("b/two.png").compressedSize());
        assertEquals(0, entries.get("a/one.png").offset(), "the first entry starts the zip");
    }

    @Test
    void anEntryIsReadWholeInOneRange() throws IOException {
        TestZip zip =
                TestZip.builder().deflated("a/one.png", TEXT).stored("b/two.png", OTHER).build();
        Map<String, ZipDirectory.Entry> entries = ZipDirectory.parse(zip.directory());
        HttpRanges ranges = zip.ranges();

        assertArrayEquals(
                TEXT, ZipDirectory.read(entries.get("a/one.png"), ranges, zip.directoryOffset));
        assertArrayEquals(
                OTHER, ZipDirectory.read(entries.get("b/two.png"), ranges, zip.directoryOffset));
        assertEquals(2, zip.reads.size());
        long[] last = zip.reads.get(1);
        assertEquals(zip.directoryOffset, last[0] + last[1], "no range reaches past the entries");
    }

    @Test
    void aLocalExtraFieldLongerThanTheSlackTakesASecondRange() throws IOException {
        TestZip zip = TestZip.builder().withExtra("a/one.png", TEXT, 200).build();
        ZipDirectory.Entry entry = ZipDirectory.parse(zip.directory()).get("a/one.png");

        assertArrayEquals(TEXT, ZipDirectory.read(entry, zip.ranges(), zip.directoryOffset));
        assertEquals(2, zip.reads.size());
    }

    @Test
    void anEntryWhoseBytesAreNotTheDirectorysIsRefused() throws IOException {
        TestZip zip = TestZip.builder().stored("b/two.png", OTHER).build();
        ZipDirectory.Entry entry = ZipDirectory.parse(zip.directory()).get("b/two.png");
        // The stored data sits right after the local header and the name.
        zip.bytes[30 + "b/two.png".length()] ^= 1;

        assertThrows(
                IOException.class,
                () -> ZipDirectory.read(entry, zip.ranges(), zip.directoryOffset));
    }

    @Test
    void bytesThatAreNotACentralDirectoryAreRefused() {
        assertThrows(IOException.class, () -> ZipDirectory.parse(new byte[] {1, 2, 3, 4}));
        assertThrows(IOException.class, () -> ZipDirectory.parse(new byte[60]));
    }
}
