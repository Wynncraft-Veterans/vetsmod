package org.wynnvets.retro.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The sections that decide which item models and equipment assets are replaced, and with what.
 *
 * <p>Beyond the rules every section keeps, these fail the parse: an {@code item_models} row that
 * names both a model and a texture, or an unknown parent, and an {@code ingredients} value that is
 * neither a full model id nor {@link RetroManifest#KEEP}.
 *
 * @param itemModels models replaced by a stub, the rows {@link ItemTables} makes before
 *     {@code item_models}' own; the first matching row decides
 * @param potions potion models replaced by the vanilla potion, the rows {@link ItemTables} makes:
 *     each family's Wynncraft models then its Avomod model; the first matching row decides
 * @param equipment {@code equipment}: Wynncraft's equipment assets replaced by vanilla's; the
 *     first matching row decides
 * @param blockModels {@code block_models}: a block model to the model it is drawn from
 * @param ingredients {@code ingredients}: model stem under {@code minecraft:item/wynn/ingredient/}
 *     to the item model its stub parents, vanilla's or one the pack writes, or
 *     {@link RetroManifest#KEEP}
 */
public record ModelSections(
        List<ItemModel> itemModels,
        List<Potion> potions,
        List<Equipment> equipment,
        Map<String, String> blockModels,
        Map<String, String> ingredients) {

    private static final String ITEM_MODELS = "item_models";
    private static final String EQUIPMENT = "equipment";
    private static final String BLOCK_MODELS = "block_models";
    private static final String INGREDIENTS = "ingredients";

    static final Set<String> NAMES = Set.of(ITEM_MODELS, EQUIPMENT, BLOCK_MODELS);

    /** The sections of the ingredients file. */
    static final Set<String> INGREDIENT_NAMES = Set.of(INGREDIENTS);

    /** Copies every list and map, keeping the order of {@code blockModels}. */
    public ModelSections {
        itemModels = List.copyOf(itemModels);
        potions = List.copyOf(potions);
        equipment = List.copyOf(equipment);
        blockModels = ManifestYaml.frozen(blockModels);
        ingredients = Map.copyOf(ingredients);
    }

    /**
     * An {@code item_models} row. Exactly one of {@code texture} (with {@code parent}) and
     * {@code model} is set.
     *
     * @param models the model ids the row replaces
     * @param texture the texture id the stub draws on a tint-proof parent, in which {@code $n}
     *     stands for the pattern's group {@code n}, or {@code null}
     * @param parent how that stub is held, or {@code null} with {@code texture}
     * @param model the model id the stub names as its only content, its parent, with {@code $n} as
     *     in {@code texture}, or {@code null}
     */
    public record ItemModel(Pattern models, String texture, ParentKind parent, String model) {}

    /**
     * A {@code potions} row.
     *
     * @param models the model ids the row replaces
     * @param liquid the texture id of the liquid, a {@code source_copies} copy in the family's
     *     colour
     */
    public record Potion(Pattern models, String liquid) {}

    /**
     * An {@code equipment} row.
     *
     * @param assets the equipment asset ids the row covers, such as {@code minecraft:pale_iron}
     * @param target the vanilla asset whose file replaces theirs, in which {@code $n} stands for
     *     the pattern's group {@code n}, or {@link RetroManifest#KEEP} for Wynncraft's
     */
    public record Equipment(Pattern assets, String target) {}

    static ModelSections parse(Map<?, ?> textures, Map<?, ?> ingredients, ItemTables tables) {
        List<ItemModel> itemModels = new ArrayList<>(tables.itemModels());
        itemModels.addAll(itemModels(textures.get(ITEM_MODELS)));
        return new ModelSections(
                itemModels,
                tables.potions(),
                equipment(textures.get(EQUIPMENT)),
                blockModels(textures.get(BLOCK_MODELS)),
                ingredients(ingredients.get(INGREDIENTS)));
    }

    void addCounts(Map<String, Integer> counts) {
        counts.put(ITEM_MODELS, itemModels.size());
        counts.put(ItemTables.POTIONS, potions.size());
        counts.put(EQUIPMENT, equipment.size());
        counts.put(BLOCK_MODELS, blockModels.size());
        counts.put(INGREDIENTS, ingredients.size());
    }

    private static List<ItemModel> itemModels(Object section) {
        return ManifestYaml.patternRows(
                section, ManifestYaml.inTextures(ITEM_MODELS), ModelSections::itemModel);
    }

    /** An {@code item_models} row: a texture id alone, held flat, or a mapping. */
    private static ItemModel itemModel(Pattern pattern, Object value, String rowWhere) {
        if (value instanceof String) {
            String texture = ManifestYaml.replacement(value, pattern, rowWhere);
            return new ItemModel(pattern, texture, ParentKind.GENERATED, null);
        }
        Map<?, ?> fields = ManifestYaml.mapping(value, rowWhere);
        ManifestYaml.onlyKeys(fields, rowWhere, "texture", "parent", "model");
        if (fields.containsKey("model")) {
            if (fields.containsKey("texture") || fields.containsKey("parent")) {
                throw new IllegalArgumentException(
                        rowWhere + ": a row names a model, or a texture and parent, not both");
            }
            String model =
                    ManifestYaml.replacement(fields.get("model"), pattern, rowWhere + ": model");
            return new ItemModel(pattern, null, null, model);
        }
        String texture =
                ManifestYaml.replacement(fields.get("texture"), pattern, rowWhere + ": texture");
        ParentKind parent =
                fields.get("parent") instanceof String name ? ParentKind.fromYaml(name) : null;
        if (parent == null) {
            throw new IllegalArgumentException(
                    rowWhere
                            + ": parent must be handheld, handheld_rod, bow or generated, not "
                            + fields.get("parent"));
        }
        return new ItemModel(pattern, texture, parent, null);
    }

    private static List<Equipment> equipment(Object section) {
        return ManifestYaml.patternRows(
                section,
                ManifestYaml.inTextures(EQUIPMENT),
                (pattern, value, rowWhere) ->
                        new Equipment(
                                pattern,
                                RetroManifest.KEEP.equals(value)
                                        ? RetroManifest.KEEP
                                        : ManifestYaml.replacement(value, pattern, rowWhere)));
    }

    private static Map<String, String> blockModels(Object section) {
        return ManifestYaml.idRows(
                section,
                ManifestYaml.inTextures(BLOCK_MODELS),
                null,
                (model, value, rowWhere) -> ManifestYaml.requiredId(value, rowWhere, "no model"));
    }

    private static Map<String, String> ingredients(Object section) {
        return ManifestYaml.textRows(
                section,
                ManifestYaml.INGREDIENTS_FILE + ": " + INGREDIENTS,
                (stem, value, rowWhere) -> {
                    String model = ManifestYaml.text(value, rowWhere);
                    if (model == null
                            || !(model.equals(RetroManifest.KEEP)
                                    || ManifestYaml.hasNamespace(model))) {
                        throw new IllegalArgumentException(
                                rowWhere + ": expected a full model id or 'keep', not " + model);
                    }
                    return model;
                });
    }
}
