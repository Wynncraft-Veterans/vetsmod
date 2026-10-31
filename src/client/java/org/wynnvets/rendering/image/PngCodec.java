package org.wynnvets.rendering.image;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * PNG files to {@link Pixels} and back. {@link #decode} reads through Minecraft's {@link
 * NativeImage}, which takes any PNG the game itself can load; {@link #png} writes in plain Java,
 * because {@code NativeImage} in 1.21.11 can write a PNG only to a file.
 */
public final class PngCodec {

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    private PngCodec() {}

    /**
     * Reads a PNG through Minecraft's own decoder, as 8-bit RGBA whatever the file's colour type.
     *
     * @param png the file's bytes
     * @return the image
     * @throws IOException when the bytes are not an image Minecraft can read
     */
    public static Pixels decode(byte[] png) throws IOException {
        try (NativeImage image =
                NativeImage.read(NativeImage.Format.RGBA, new ByteArrayInputStream(png))) {
            return new Pixels(image.getWidth(), image.getHeight(), image.getPixels());
        }
    }

    /**
     * A PNG's size, from its header, without decoding it.
     *
     * @param png the file's bytes
     * @return {@code {width, height}}
     * @throws IOException when the bytes do not start like a PNG
     */
    public static int[] pngSize(byte[] png) throws IOException {
        // The signature, then IHDR first: its length, type, width and height.
        if (png.length < 24
                || !Arrays.equals(png, 0, 8, PNG_SIGNATURE, 0, 8)
                || !new String(png, 12, 4, StandardCharsets.US_ASCII).equals("IHDR")) {
            throw new IOException("not a PNG");
        }
        ByteBuffer header = ByteBuffer.wrap(png, 16, 8);
        return new int[] {header.getInt(), header.getInt()};
    }

    /**
     * Writes an image as a PNG: 8-bit RGBA, unfiltered, one compressed data chunk.
     *
     * @param image the image
     * @return the file's bytes
     */
    public static byte[] png(Pixels image) {
        int width = image.width();
        byte[] raw = new byte[(width * 4 + 1) * image.height()];
        int at = 0;
        for (int y = 0; y < image.height(); y++) {
            raw[at++] = 0; // filter type: none
            for (int x = 0; x < width; x++) {
                int pixel = image.get(x, y);
                raw[at++] = (byte) (pixel >> 16);
                raw[at++] = (byte) (pixel >> 8);
                raw[at++] = (byte) pixel;
                raw[at++] = (byte) (pixel >>> 24);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PNG_SIGNATURE);
        ByteBuffer header = ByteBuffer.allocate(13);
        // Width, height, bit depth 8, colour type 6 (RGBA), default compression, filter and
        // no interlace.
        header.putInt(width).putInt(image.height()).put((byte) 8).put((byte) 6);
        chunk(out, "IHDR", header.array());
        chunk(out, "IDAT", deflate(raw));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
