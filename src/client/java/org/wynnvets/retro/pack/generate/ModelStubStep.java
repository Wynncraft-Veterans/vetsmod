package org.wynnvets.retro.pack.generate;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.ModelStub;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * Item-model stubs, for every model {@link ModelRules#modelStub} replaces among those Wynncraft
 * ships under {@code item/wynn/} and those of Avomod's in any pack up to Wynncraft's. Avomod points
 * a potion's item model at a model of its own, so its models are stubbed where a {@code potions}
 * row names them. {@link StubWriter} writes each one, but for a model whose part is off.
 */
final class ModelStubStep implements GenerationStep {

    /** Where Wynncraft's own item models live, as a folder of {@code minecraft} assets. */
    private static final String WYNN_MODELS = PackFiles.MODELS_FOLDER + "/item/wynn";

    /**
     * The files of the models the stubs are decided for.
     *
     * @param gen the generation
     * @return the files, each by its full path, in order
     */
    static List<Identifier> models(GenerationContext gen) {
        List<Identifier> models =
                PackFiles.jsonFiles(gen.wynncraft, Identifier.DEFAULT_NAMESPACE, WYNN_MODELS);
        Set<Identifier> avomod = new TreeSet<>();
        for (PackResources pack : gen.lowerPacks) {
            avomod.addAll(
                    PackFiles.jsonFiles(
                            pack, RetroAssetIds.AVOMOD_NAMESPACE, PackFiles.MODELS_FOLDER));
        }
        models.addAll(avomod);
        models.sort(null);
        return models;
    }

    @Override
    public void run(GenerationContext gen) {
        PackOutput out = gen.out;
        Set<String> stems = new HashSet<>();
        for (Identifier file : models(gen)) {
            Identifier model = PackFiles.MODELS.fileToId(file);
            String id = model.toString();
            out.modelIds.add(id);
            String stem = ModelRules.ingredientStem(id);
            if (stem != null) stems.add(stem);
            out.attempt(
                    "could not stub " + model,
                    () -> {
                        if (!gen.parts.modelOn(id)) return;
                        Optional<ModelStub> stub = gen.modelRules.modelStub(id);
                        if (stub.isPresent() && gen.stubs.write(file, model, stub.get())) {
                            out.stubbed.add(id);
                        }
                    });
        }
        for (String stem : gen.manifest.models().ingredients().keySet()) {
            if (!stems.contains(stem)) out.skip("Wynncraft has no ingredient model " + stem);
        }
    }
}
