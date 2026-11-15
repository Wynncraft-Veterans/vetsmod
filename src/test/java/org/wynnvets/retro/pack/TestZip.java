package org.wynnvets.retro.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A zip built in memory and read back through {@link HttpRanges}, as a remote jar is. */
final class TestZip {

    /** The whole zip. */
    final byte[] bytes;

    /** Where its central directory starts. */
    final long directoryOffset;

    /** Its central directory's size. */
    final int directorySize;

    /** Every range read so far, as {@code {offset, length}}. */
    final List<long[]> reads = new ArrayList<>();

    private TestZip(byte[] bytes) {
        this.bytes = bytes;
        // No comment: the end-of-directory record is the last 22 bytes.
        ByteBuffer end =
                ByteBuffer.wrap(bytes, bytes.length - 22, 22)
                        .slice()
                        .order(ByteOrder.LITTLE_ENDIAN);
        directorySize = end.getInt(12);
        directoryOffset = Integer.toUnsignedLong(end.getInt(16));
    }

    /** The zip's ranges, each read recorded in {@link #reads}. */
    HttpRanges ranges() {
        return (offset, length) -> {
            reads.add(new long[] {offset, length});
            if (offset < 0 || length < 1 || offset + length > bytes.length) {
                throw new IOException("past the end: " + offset + "+" + length);
            }
            return Arrays.copyOfRange(bytes, (int) offset, (int) offset + length);
        };
    }

    /** The central directory's bytes. */
    byte[] directory() {
        return Arrays.copyOfRange(
                bytes, (int) directoryOffset, (int) directoryOffset + directorySize);
    }

    /** The zip pinned as an old client's jar is. */
    OldClients.Jar jar() {
        return new OldClients.Jar(
                "0000000000000000000000000000000000000000",
                directoryOffset,
                directorySize,
                OldClients.sha1(directory()));
    }

    static Builder builder() {
        return new Builder();
    }

    /** Adds entries in order. */
    static final class Builder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ZipOutputStream zip = new ZipOutputStream(out);

        Builder deflated(String name, byte[] data) throws IOException {
            return add(new ZipEntry(name), data);
        }

        Builder stored(String name, byte[] data) throws IOException {
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
            return add(entry, data);
        }

        /** A deflated entry whose extra field is {@code extraLength} bytes long. */
        Builder withExtra(String name, byte[] data, int extraLength) throws IOException {
            ZipEntry entry = new ZipEntry(name);
            ByteBuffer extra = ByteBuffer.allocate(extraLength).order(ByteOrder.LITTLE_ENDIAN);
            extra.putShort((short) 0x6666).putShort((short) (extraLength - 4));
            entry.setExtra(extra.array());
            return add(entry, data);
        }

        private Builder add(ZipEntry entry, byte[] data) throws IOException {
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
            return this;
        }

        TestZip build() throws IOException {
            zip.close();
            return new TestZip(out.toByteArray());
        }
    }
}
