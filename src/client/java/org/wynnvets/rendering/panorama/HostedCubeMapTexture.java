package org.wynnvets.rendering.panorama;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.renderer.texture.CubeMapTexture;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * A cube map whose six faces are PNG bytes held in memory rather than files of the resource packs:
 * a downloaded panorama ({@link PanoramaDownloads#read}). A resource reload builds it again from
 * the same bytes, so it never reads the disk mid-reload, where a missing or changed file would
 * fail the reload. Built as vanilla's {@link CubeMapTexture} builds its own.
 */
public final class HostedCubeMapTexture extends CubeMapTexture {

    /** The face each layer of the cube map takes, in the order vanilla's suffixes give them. */
    // Package-private for unit tests. See HostedCubeMapTextureTest.
    static final int[] LAYERS = {1, 3, 5, 4, 0, 2};

    private final List<byte[]> faces;

    /**
     * @param id the id it is registered under
     * @param faces the six faces' PNG bytes, face 0 first, each square and the same size
     */
    public HostedCubeMapTexture(Identifier id, List<byte[]> faces) {
        super(id);
        this.faces = List.copyOf(faces);
    }

    @Override
    public TextureContents loadContents(ResourceManager resources) throws IOException {
        return decode(resourceId(), faces);
    }

    /**
     * Decodes six faces into a cube map's layers. It touches nothing on the GPU, so it can run off
     * the render thread, for {@link #apply} to upload there.
     *
     * @param id the texture's id, for the error
     * @param faces the six faces' PNG bytes, face 0 first
     * @return the layers, for the caller to apply or close
     * @throws IOException when a face does not decode, or the faces are not all one size
     */
    public static TextureContents decode(Identifier id, List<byte[]> faces) throws IOException {
        try (NativeImage first = NativeImage.read(faces.get(LAYERS[0]))) {
            int width = first.getWidth();
            int height = first.getHeight();
            NativeImage layers = new NativeImage(width, height * LAYERS.length, false);
            try {
                first.copyRect(layers, 0, 0, 0, 0, width, height, false, true);
                for (int layer = 1; layer < LAYERS.length; layer++) {
                    try (NativeImage face = NativeImage.read(faces.get(LAYERS[layer]))) {
                        if (face.getWidth() != width || face.getHeight() != height) {
                            throw new IOException("the faces of " + id + " are not all one size");
                        }
                        face.copyRect(layers, 0, 0, 0, layer * height, width, height, false, true);
                    }
                }
            } catch (IOException | RuntimeException e) {
                layers.close();
                throw e;
            }
            return new TextureContents(
                    layers, new TextureMetadataSection(true, false, MipmapStrategy.MEAN, 0.0F));
        }
    }
}
