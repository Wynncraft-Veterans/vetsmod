package org.wynnvets.retro.pack.generate;

import net.minecraft.resources.Identifier;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport.Count;

/** {@code blank_textures}: each texture written fully transparent, at its live size. */
final class BlankTextureStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        for (String textureId : gen.manifest.assets().blankTextures()) {
            Identifier file = out.textureFile(textureId);
            if (file == null) continue;
            if (!gen.parts.textureOn(textureId)) {
                out.count(Count.PARTS_OFF);
                continue;
            }
            out.attempt(
                    "could not blank " + textureId,
                    () -> {
                        byte[] live = gen.liveFile(file);
                        if (live == null) {
                            out.skip("no pack has " + textureId + " to blank");
                            return;
                        }
                        int[] size = PngCodec.pngSize(live);
                        out.writeTexture(
                                file,
                                PngCodec.png(Pixels.blank(size[0], size[1])),
                                null,
                                Count.BLANK_TEXTURES);
                    });
        }
    }
}
