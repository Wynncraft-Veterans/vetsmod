package org.wynnvets.retro.pack.generate;

import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.wynnvets.rendering.image.Pixels;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.AssetSections.SourceCopy;

/**
 * {@code source_copies}: each texture from the first of its sources that has it, as a copy is read,
 * with the {@code .mcmeta} beside it, at its {@code vetsmod} id, in the row's colour if it names
 * one, as its recipe takes it. These are for art a stub needs at an id of its own, and for the
 * GUI sprites {@link org.wynnvets.retro.render.RetroHud RetroHud} draws.
 */
final class SourceCopyStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        for (Map.Entry<String, SourceCopy> row : gen.manifest.assets().sourceCopies().entrySet()) {
            String copy = row.getKey();
            String original = row.getValue().source();
            PackOutput.CopyFiles files = out.copyFiles(original, copy);
            if (files == null) continue;
            out.attempt(
                    "could not copy " + original + " to " + copy,
                    () -> {
                        GenerationContext.SourceArt art = gen.sourceArt(original);
                        if (art == null) {
                            out.skip("no source has " + original + " to copy to " + copy);
                            return;
                        }
                        byte[] png = art.png();
                        IntUnaryOperator recolour = row.getValue().recolour();
                        if (recolour != null) {
                            Pixels image = PngCodec.decode(png);
                            image.apply(recolour);
                            png = PngCodec.png(image);
                        }
                        out.writeTexture(files.to(), png, gen.mcmeta(art), Count.SOURCE_COPIES);
                    });
        }
    }
}
