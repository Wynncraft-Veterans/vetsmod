package org.wynnvets.retro.pack.generate;

import java.util.Map;
import net.minecraft.resources.Identifier;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.pack.PackFiles;

/** {@code block_models}: each row as its block model's file, a stub naming the row's parent. */
final class BlockModelStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        for (Map.Entry<String, String> row : gen.manifest.models().blockModels().entrySet()) {
            Identifier model = Identifier.tryParse(row.getKey());
            if (model == null) {
                gen.out.skip("not a model id: " + row.getKey());
                continue;
            }
            if (!gen.parts.modelOn(row.getKey())) continue;
            gen.out.attempt(
                    "could not stub " + model,
                    () ->
                            gen.stubs.writeParent(
                                    PackFiles.modelFile(row.getKey()),
                                    model,
                                    row.getValue(),
                                    Count.BLOCK_MODELS));
        }
    }
}
