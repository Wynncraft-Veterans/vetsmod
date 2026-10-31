package org.wynnvets.retro.rules;

import java.util.Optional;
import java.util.regex.Matcher;
import org.wynnvets.retro.manifest.ModelSections;
import org.wynnvets.retro.manifest.RetroManifest;

/**
 * The retro pack's decisions about models: which stub replaces an item or potion model, which
 * vanilla model an ingredient's stub parents, and which vanilla equipment asset replaces one of
 * Wynncraft's. Every answer comes from the {@link ModelSections} alone, so the class is pure. Ids
 * are written in full, {@code minecraft:item/stick}, as in the rule files.
 */
public final class ModelRules {

    /** Where Wynncraft's ingredient models are, as the start of their model ids. */
    private static final String INGREDIENT_MODELS = "minecraft:item/wynn/ingredient/";

    private final ModelSections sections;

    /**
     * Decides with these sections' rules.
     *
     * @param sections the parsed sections
     */
    public ModelRules(ModelSections sections) {
        this.sections = sections;
    }

    /**
     * The stub that replaces {@code modelId}: the first {@code item_models} row that matches it,
     * with its texture's or model's {@code $n} filled in.
     *
     * @param modelId the model, which may be {@code null}
     * @return a {@link ModelStub.TintProof} for a row with a texture, a
     *     {@link ModelStub.ItemParent} for a row with a model; empty when no row matches, when the
     *     matching row's texture is in neither {@code block/} nor {@code item/} (no single-atlas
     *     stub can draw it), or for {@code null}
     */
    public Optional<ModelStub> itemStub(String modelId) {
        if (modelId == null) return Optional.empty();
        for (ModelSections.ItemModel row : sections.itemModels()) {
            Matcher matcher = row.models().matcher(modelId);
            if (!matcher.matches()) continue;
            if (row.model() != null) {
                return Optional.of(
                        new ModelStub.ItemParent(RowMatches.substitute(matcher, row.model())));
            }
            String texture = RowMatches.substitute(matcher, row.texture());
            return Atlas.of(texture)
                    .map(atlas -> new ModelStub.TintProof(texture, row.parent(), atlas));
        }
        return Optional.empty();
    }

    /**
     * The stub that replaces the potion model {@code modelId}: the first {@code potions} row that
     * matches it.
     *
     * @param modelId the model, which may be {@code null}
     * @return the stub; empty when no row matches, or for {@code null}
     */
    public Optional<ModelStub.Potion> potionStub(String modelId) {
        if (modelId == null) return Optional.empty();
        for (ModelSections.Potion row : sections.potions()) {
            if (!row.models().matcher(modelId).matches()) continue;
            return Optional.of(new ModelStub.Potion(row.liquid()));
        }
        return Optional.empty();
    }

    /**
     * The vanilla equipment asset whose file replaces one of Wynncraft's: the first
     * {@code equipment} row that matches it, with its {@code $n} filled in.
     *
     * @param assetId Wynncraft's asset, such as {@code minecraft:pale_iron}, which may be
     *     {@code null}
     * @return the vanilla asset, such as {@code minecraft:iron}; empty when the row is
     *     {@code keep}, when no row matches (both leave Wynncraft's asset), or for {@code null}
     */
    public Optional<String> equipmentAsset(String assetId) {
        if (assetId == null) return Optional.empty();
        for (ModelSections.Equipment row : sections.equipment()) {
            Matcher matcher = row.assets().matcher(assetId);
            if (!matcher.matches()) continue;
            if (row.target().equals(RetroManifest.KEEP)) return Optional.empty();
            return Optional.of(RowMatches.substitute(matcher, row.target()));
        }
        return Optional.empty();
    }

    /**
     * The item model the stub for an ingredient parents: vanilla's, or one the pack writes
     * ({@code accessory_tiers}).
     *
     * @param stem the ingredient's model stem under {@code minecraft:item/wynn/ingredient/}, which
     *     may be {@code null}
     * @return the model id; empty when the stem's row is {@code keep}, when it has no row, or for
     *     {@code null}; {@link #modelStub} then decides
     */
    public Optional<String> ingredientParent(String stem) {
        String model = stem == null ? null : sections.ingredients().get(stem);
        if (model == null || model.equals(RetroManifest.KEEP)) return Optional.empty();
        return Optional.of(model);
    }

    /**
     * The stub that replaces an item model, as the pack generator picks it: a model under
     * {@code minecraft:item/wynn/ingredient/} by its stem's row when it has one, and otherwise,
     * like any other, by the first {@code potions} row that matches it, failing that the first
     * {@code item_models} row.
     *
     * @param modelId the model, which may be {@code null}
     * @return the stub and its rule; empty when no rule replaces the model (an ingredient whose
     *     row is {@code keep} among them), or for {@code null}
     */
    public Optional<ModelStub> modelStub(String modelId) {
        String stem = ingredientStem(modelId);
        if (stem != null) {
            Optional<String> parent = ingredientParent(stem);
            if (parent.isPresent()) {
                return Optional.of(new ModelStub.Ingredient(stem, parent.get()));
            }
            if (sections.ingredients().containsKey(stem)) return Optional.empty();
        }
        Optional<ModelStub.Potion> potion = potionStub(modelId);
        if (potion.isPresent()) return Optional.of(potion.get());
        return itemStub(modelId);
    }

    /**
     * An ingredient model's stem, the key of its row in {@code retro_ingredients.yml}.
     *
     * @param modelId a model id, which may be {@code null}
     * @return what follows {@code minecraft:item/wynn/ingredient/}, such as {@code gold_nugget};
     *     {@code null} for any other model, or for {@code null}
     */
    public static String ingredientStem(String modelId) {
        if (modelId == null || !modelId.startsWith(INGREDIENT_MODELS)) return null;
        return modelId.substring(INGREDIENT_MODELS.length());
    }
}
