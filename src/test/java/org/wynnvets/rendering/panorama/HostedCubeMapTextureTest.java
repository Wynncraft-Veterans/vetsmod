package org.wynnvets.rendering.panorama;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.client.renderer.texture.CubeMapTexture;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HostedCubeMapTexture}'s face order. Building the texture needs a running
 * client and is not tested.
 */
class HostedCubeMapTextureTest {

    @Test
    void eachLayerTakesTheFaceVanillasSuffixesGiveIt() throws ReflectiveOperationException {
        // Vanilla reads layer k from the file named by SUFFIXES[k], such as "_1.png".
        Field field = CubeMapTexture.class.getDeclaredField("SUFFIXES");
        field.setAccessible(true);
        int[] vanilla =
                Arrays.stream((String[]) field.get(null))
                        .mapToInt(suffix -> Integer.parseInt(suffix.substring(1, 2)))
                        .toArray();

        assertArrayEquals(vanilla, HostedCubeMapTexture.LAYERS);
    }
}
