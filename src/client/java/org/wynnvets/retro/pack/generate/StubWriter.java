package org.wynnvets.retro.pack.generate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.pack.PackFiles;
import org.wynnvets.retro.rules.Atlas;
import org.wynnvets.retro.rules.ModelStub;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * Writes the model stubs of one generation, each only once every file it names resolves, in this
 * pack or the reload's others: the row's texture on the tint-proof parent of its kind and atlas, a
 * model that only names a parent, or a potion on the copy of its liquid {@link SourceCopyStep}
 * wrote.
 */
final class StubWriter {

    private final GenerationContext gen;
    private final PackOutput out;

    /** Whether a file resolves in the reload's other packs; memoised. */
    private final Map<Identifier, Boolean> resolved = new HashMap<>();

    StubWriter(GenerationContext gen) {
        this.gen = gen;
        this.out = gen.out;
    }

    /**
     * Writes {@code stub} as {@code model}'s file, once every file it names resolves, and counts it
     * by its rule.
     *
     * @return whether it was written
     */
    boolean write(Identifier file, Identifier model, ModelStub stub) {
        return switch (stub) {
            case ModelStub.Ingredient ingredient ->
                    writeParent(file, model, ingredient.parent(), Count.INGREDIENTS);
            case ModelStub.Potion potion -> writePotion(file, model, potion);
            case ModelStub.TintProof tintProof -> writeTintProof(file, model, tintProof);
            case ModelStub.ItemParent parent ->
                    writeParent(file, model, parent.model(), Count.ITEM_MODELS);
        };
    }

    /**
     * Writes a stub that only names {@code parent} as its parent as {@code model}'s file, once
     * {@code parent} resolves and, for one of vetsmod's own models, every texture it names (the
     * gear boxes' chest draws a {@code source_copies} texture), and counts it.
     *
     * @return whether it was written
     */
    boolean writeParent(Identifier file, Identifier model, String parent, Count count) {
        List<Identifier> needed = new ArrayList<>();
        needed.add(PackFiles.modelFile(parent));
        needed.addAll(ownModelTextures(parent));
        if (!allResolve(model, needed.toArray(Identifier[]::new))) return false;
        out.files.put(file, ModelJson.parentOnly(parent));
        out.count(count);
        return true;
    }

    private boolean writeTintProof(Identifier file, Identifier model, ModelStub.TintProof stub) {
        if (!allResolve(
                model,
                PackFiles.textureFile(stub.art()),
                PackFiles.modelFile(stub.parentModel()),
                PackFiles.textureFile(stub.blank()))) {
            return false;
        }
        out.files.put(file, ModelJson.withTexture(stub.parentModel(), "art", stub.art()));
        out.count(Count.ITEM_MODELS);
        return true;
    }

    private boolean writePotion(Identifier file, Identifier model, ModelStub.Potion stub) {
        if (!allResolve(
                model,
                PackFiles.modelFile(RetroAssetIds.TINTED_POTION),
                PackFiles.textureFile(stub.liquid()),
                PackFiles.textureFile(RetroAssetIds.POTION_BOTTLE),
                PackFiles.textureFile(RetroAssetIds.blank(Atlas.ITEMS)))) {
            return false;
        }
        out.files.put(
                file, ModelJson.withTexture(RetroAssetIds.TINTED_POTION, "liquid", stub.liquid()));
        out.count(Count.POTIONS);
        return true;
    }

    /**
     * The files of the textures one of vetsmod's own models draws ({@link ModelJson#drawn}), read
     * from the reload's packs, top first; none for a model of any other namespace, whose textures
     * are vanilla's or Wynncraft's own, or for one this generation wrote, with its texture. A
     * {@code #} reference to a slot it does not fill is left to the model's parent. A model that
     * cannot be read, or a texture that is not an id, names a file that never resolves, so the stub
     * is skipped.
     */
    private List<Identifier> ownModelTextures(String modelId) {
        Identifier file = PackFiles.modelFile(modelId);
        if (file == null
                || !file.getNamespace().equals(RetroAssetIds.NAMESPACE)
                || out.files.containsKey(file)) {
            return List.of();
        }
        Identifier unreadable = file.withSuffix(".unreadable");
        return out.attempt(
                "could not read the textures " + modelId + " names",
                List.of(unreadable),
                () -> {
                    byte[] json = gen.topFile(file);
                    if (json == null) return List.of(file);
                    List<Identifier> textures = new ArrayList<>();
                    for (String id : ModelJson.drawn(ModelJson.read(json).slots())) {
                        Identifier textureFile = PackFiles.textureFile(id);
                        textures.add(textureFile != null ? textureFile : unreadable);
                    }
                    return textures;
                });
    }

    /** Whether every file the stub for {@code model} needs resolves; reports the first not. */
    private boolean allResolve(Identifier model, Identifier... needed) {
        for (Identifier file : needed) {
            if (!resolves(file)) {
                out.skip("the stub for " + model + " needs " + file + ", which is missing");
                return false;
            }
        }
        return true;
    }

    /** Whether {@code file} is in this pack or any other the reload loads; memoised. */
    private boolean resolves(Identifier file) {
        if (file == null) return false;
        if (out.files.containsKey(file)) return true;
        return resolved.computeIfAbsent(
                file,
                f ->
                        gen.stack.stream()
                                .anyMatch(pack -> pack.getResource(PackFiles.TYPE, f) != null));
    }
}
