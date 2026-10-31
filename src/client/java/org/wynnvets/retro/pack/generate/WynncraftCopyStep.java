package org.wynnvets.retro.pack.generate;

import java.util.Map;
import java.util.Optional;
import net.minecraft.server.packs.resources.Resource;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.pack.PackFiles;

/**
 * {@code wynncraft_copies}: Wynncraft's live texture, with its {@code .mcmeta}, copied to a
 * {@code vetsmod} id. It reads the packs below retro's, so the texture rules replacing the original
 * make no difference to it.
 */
final class WynncraftCopyStep implements GenerationStep {

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        for (Map.Entry<String, String> row : gen.manifest.assets().wynncraftCopies().entrySet()) {
            String copy = row.getKey();
            String original = row.getValue();
            PackOutput.CopyFiles files = out.copyFiles(original, copy);
            if (files == null) continue;
            out.attempt(
                    "could not copy " + original + " to " + copy,
                    () -> {
                        Optional<Resource> live = gen.lower.getResource(files.from());
                        if (live.isEmpty()) {
                            out.skip("Wynncraft has no " + original + " to copy to " + copy);
                            return;
                        }
                        // The .mcmeta comes from the pack the PNG does, as Minecraft would take it.
                        byte[] mcmeta =
                                PackFiles.read(
                                        live.get()
                                                .source()
                                                .getResource(
                                                        PackFiles.TYPE,
                                                        PackFiles.mcmeta(files.from())));
                        out.writeTexture(
                                files.to(),
                                PackFiles.read(live.get()::open),
                                mcmeta,
                                Count.WYNNCRAFT_COPIES);
                        out.wynncraftCopies.add(copy);
                    });
        }
    }
}
