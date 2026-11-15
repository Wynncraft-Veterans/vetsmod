package org.wynnvets.retro.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A zip file read a piece at a time: its central directory, then one entry at a time over
 * {@link HttpRanges}. Each entry is checked against the directory: its local header, its size and
 * its CRC-32. Only stored and deflated entries without zip64 fields are read, which is every entry
 * of Mojang's old client jars.
 */
final class ZipDirectory {

    private static final int DIRECTORY_SIGNATURE = 0x02014b50;
    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int DIRECTORY_HEADER = 46;
    private static final int LOCAL_HEADER = 30;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;

    /** Bytes asked for past an entry's name, for a local extra field the directory does not give. */
    private static final int EXTRA_SLACK = 64;

    /** The largest entry read; a texture is far smaller. */
    private static final long MAX_ENTRY = 4L << 20;

    private ZipDirectory() {}

    /**
     * One file of the zip, as its central directory describes it.
     *
     * @param name its path in the zip, such as {@code assets/minecraft/textures/blocks/stone.png}
     * @param method how it is stored: 0 stored, 8 deflated
     * @param crc its CRC-32
     * @param compressedSize its size in the zip
     * @param size its size once read
     * @param offset where its local header starts
     */
    record Entry(String name, int method, long crc, long compressedSize, long size, long offset) {}

    /**
     * The entries of a central directory, by name.
     *
     * @param directory the whole central directory and nothing else
     * @return the entries, in the directory's order
     * @throws IOException when it is not a central directory
     */
    static Map<String, Entry> parse(byte[] directory) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, Entry> entries = new LinkedHashMap<>();
        int at = 0;
        while (at < directory.length) {
            if (directory.length - at < DIRECTORY_HEADER || in.getInt(at) != DIRECTORY_SIGNATURE) {
                throw new IOException("not a central directory header at " + at);
            }
            int nameLength = Short.toUnsignedInt(in.getShort(at + 28));
            int extraLength = Short.toUnsignedInt(in.getShort(at + 30));
            int commentLength = Short.toUnsignedInt(in.getShort(at + 32));
            int next = at + DIRECTORY_HEADER + nameLength + extraLength + commentLength;
            if (next > directory.length) {
                throw new IOException("a central directory header runs past its end at " + at);
            }
            String name =
                    new String(
                            directory, at + DIRECTORY_HEADER, nameLength, StandardCharsets.UTF_8);
            entries.put(
                    name,
                    new Entry(
                            name,
                            Short.toUnsignedInt(in.getShort(at + 10)),
                            Integer.toUnsignedLong(in.getInt(at + 16)),
                            Integer.toUnsignedLong(in.getInt(at + 20)),
                            Integer.toUnsignedLong(in.getInt(at + 24)),
                            Integer.toUnsignedLong(in.getInt(at + 42))));
            at = next;
        }
        return entries;
    }

    /**
     * Reads one entry: its local header and data, usually in one range.
     *
     * @param entry the entry, from {@link #parse}
     * @param ranges the zip's bytes
     * @param end where the entries end (the central directory's offset): no range reaches past it
     * @return the entry's bytes, checked against its size and CRC-32
     * @throws IOException when it cannot be read, or is not what the directory says
     */
    static byte[] read(Entry entry, HttpRanges ranges, long end) throws IOException {
        if (entry.compressedSize() > MAX_ENTRY || entry.size() > MAX_ENTRY) {
            throw new IOException(entry.name() + " is too large");
        }
        int nameLength = entry.name().getBytes(StandardCharsets.UTF_8).length;
        long wanted = LOCAL_HEADER + nameLength + EXTRA_SLACK + entry.compressedSize();
        byte[] head = ranges.get(entry.offset(), (int) Math.min(wanted, end - entry.offset()));
        ByteBuffer in = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (head.length < LOCAL_HEADER || in.getInt(0) != LOCAL_SIGNATURE) {
            throw new IOException(entry.name() + ": no local header");
        }
        int dataStart =
                LOCAL_HEADER
                        + Short.toUnsignedInt(in.getShort(26))
                        + Short.toUnsignedInt(in.getShort(28));
        int compressed = (int) entry.compressedSize();
        byte[] data;
        if (dataStart + compressed <= head.length) {
            data = new byte[compressed];
            System.arraycopy(head, dataStart, data, 0, compressed);
        } else {
            data = ranges.get(entry.offset() + dataStart, compressed);
        }
        byte[] bytes =
                switch (entry.method()) {
                    case STORED -> data;
                    case DEFLATED -> inflate(data, (int) entry.size(), entry.name());
                    default ->
                            throw new IOException(
                                    entry.name() + ": stored by method " + entry.method());
                };
        CRC32 crc = new CRC32();
        crc.update(bytes);
        if (bytes.length != entry.size() || crc.getValue() != entry.crc()) {
            throw new IOException(entry.name() + ": its size or CRC-32 is not the directory's");
        }
        return bytes;
    }

    /** Raw deflate data, inflated to exactly {@code size} bytes. */
    private static byte[] inflate(byte[] data, int size, String name) throws IOException {
        Inflater inflater = new Inflater(true);
        try {
            // With nowrap, zlib may need one byte past the data (Inflater's Javadoc).
            inflater.setInput(Arrays.copyOf(data, data.length + 1));
            byte[] out = new byte[size];
            int read = 0;
            while (read < size && !inflater.finished()) {
                int n = inflater.inflate(out, read, size - read);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                read += n;
            }
            if (read != size) throw new IOException(name + ": inflated to " + read + " bytes");
            return out;
        } catch (DataFormatException e) {
            throw new IOException(name + ": not deflate data", e);
        } finally {
            inflater.end();
        }
    }
}
