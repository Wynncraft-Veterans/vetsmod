package org.wynnvets.retro.pack.generate;

import java.util.Map;
import net.minecraft.resources.Identifier;
import org.wynnvets.rendering.image.PixelDrawing;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.StackSections;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * {@code outlined_icons}: each icon's art, read from the sources, with its outline, as a
 * {@link GeneratedItemWriter generated item} at {@link RetroAssetIds#outlinedIconPath}. Art the
 * sources lack leaves that type with no icon.
 */
final class OutlinedIconStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        for (Map.Entry<String, StackSections.OutlinedIcon> row :
                gen.manifest.stacks().outlinedIcons().entrySet()) {
            String artId = row.getValue().art();
            Identifier from = out.textureFile(artId);
            if (from == null) continue;
            out.attempt(
                    "could not outline " + artId,
                    () -> {
                        GenerationContext.SourceArt art = gen.sourceArt(artId);
                        if (art == null) {
                            out.skip("nothing has " + artId + " for an outlined icon");
                            return;
                        }
                        GeneratedItemWriter.write(
                                out,
                                RetroAssetIds.outlinedIconPath(row.getKey()),
                                PixelDrawing.outlined(
                                        PngCodec.decode(art.png()),
                                        row.getValue().outlinePixels(),
                                        0xFF000000 | row.getValue().outlineColour()),
                                Count.OUTLINED_ICONS);
                    });
        }
    }
}
