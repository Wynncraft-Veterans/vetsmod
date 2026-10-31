package org.wynnvets.rendering.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PngCodec}'s plain-Java half. The decoder goes through Minecraft's {@code
 * NativeImage}, so it is not exercised; the writer's output is read back with {@link ImageIO}
 * instead.
 */
class PngCodecTest {

    private static final int PANEL = 0xFFC6C6C6;

    @Test
    void theWrittenPngReadsBackPixelForPixel() throws IOException {
        Pixels image =
                new Pixels(
                        3,
                        2,
                        new int[] {
                            0xFF000000, 0x80FF0000, 0x00000000, 0xFF00FF00, 0x400000FF, PANEL
                        });

        BufferedImage read = ImageIO.read(new ByteArrayInputStream(PngCodec.png(image)));

        assertNotNull(read);
        assertEquals(3, read.getWidth());
        assertEquals(2, read.getHeight());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(image.get(x, y), read.getRGB(x, y), x + "," + y);
            }
        }
    }

    @Test
    void pngSizeReadsTheHeaderAlone() throws IOException {
        assertArrayEquals(new int[] {5, 7}, PngCodec.pngSize(PngCodec.png(Pixels.blank(5, 7))));
        assertThrows(IOException.class, () -> PngCodec.pngSize(new byte[30]));
        assertThrows(IOException.class, () -> PngCodec.pngSize(new byte[] {(byte) 0x89, 'P'}));
    }
}
