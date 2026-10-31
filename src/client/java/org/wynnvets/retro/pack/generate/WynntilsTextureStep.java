package org.wynnvets.retro.pack.generate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.wynnvets.rendering.image.PixelDrawing;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.AssetSections.Frame;

/**
 * {@code wynntils_textures}: a texture of Wynntils' own drawn again at its live size, fully
 * transparent or as vanilla GUI parts stretched to fit.
 */
final class WynntilsTextureStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        for (Map.Entry<String, List<Frame>> row :
                gen.manifest.assets().wynntilsTextures().entrySet()) {
            String textureId = row.getKey();
            Identifier file = out.textureFile(textureId);
            if (file == null) continue;
            if (!gen.parts.textureOn(textureId)) {
                out.count(Count.PARTS_OFF);
                continue;
            }
            out.attempt(
                    "could not draw " + textureId,
                    () -> {
                        byte[] live = gen.liveFile(file);
                        if (live == null) {
                            out.skip("no pack has " + textureId + " to draw again");
                            return;
                        }
                        int[] size = PngCodec.pngSize(live);
                        Pixels drawn =
                                row.getValue().isEmpty()
                                        ? Pixels.blank(size[0], size[1])
                                        : frames(gen, textureId, row.getValue(), size[0], size[1]);
                        if (drawn == null) return;
                        out.writeTexture(file, PngCodec.png(drawn), null, Count.WYNNTILS_TEXTURES);
                    });
        }
    }

    /**
     * The frames stacked top to bottom, each the texture's width and an equal share of its height;
     * {@code null}, reported, when they cannot be drawn.
     */
    private static Pixels frames(
            GenerationContext gen, String textureId, List<Frame> frames, int width, int height)
            throws IOException {
        if (height % frames.size() != 0) {
            gen.out.skip(
                    textureId
                            + " is "
                            + height
                            + " pixels high, which "
                            + frames.size()
                            + " frames cannot share");
            return null;
        }
        List<Pixels> drawn = new ArrayList<>();
        for (Frame frame : frames) {
            Pixels source = gen.sourceImage(frame.source());
            if (source == null) {
                gen.out.skip("no source has " + frame.source() + " to draw " + textureId);
                return null;
            }
            Rect region =
                    frame.region() != null
                            ? frame.region()
                            : new Rect(0, 0, source.width(), source.height());
            drawn.add(
                    PixelDrawing.nineSlice(
                            source, region, frame.border(), width, height / frames.size()));
        }
        return PixelDrawing.stack(drawn);
    }
}
