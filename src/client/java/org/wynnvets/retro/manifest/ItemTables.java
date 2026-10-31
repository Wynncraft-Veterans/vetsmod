package org.wynnvets.retro.manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.wynnvets.retro.manifest.AssetSections.SourceCopy;
import org.wynnvets.retro.manifest.ModelSections.ItemModel;
import org.wynnvets.retro.manifest.ModelSections.Potion;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * The tables that spell out whole families of rows: {@code recipes}, {@code materials} and
 * {@code content_book_activities}, which make {@code item_models} and {@code source_copies} rows,
 * and {@code potions}, which makes each family's models and a {@code source_copies} copy of its
 * liquid. Each name in a table becomes its rows here, while parsing, so nothing later knows the
 * tables exist.
 *
 * <p>Beyond the rules every section keeps, these fail the parse: an unknown recipe, a recipe with
 * a name a family's own keys use, a name a family lists twice, a family or table without its
 * {@code models}, a {@code potions} family without a lower-case name or a colour, and potions with
 * no {@code potion_overlay} recipe.
 *
 * @param recipes {@code recipes}: a recipe's name to the recipe, in file order
 * @param itemModels the {@code item_models} rows the tables make, each matching one model id
 * @param potions the {@code potions} rows, in file order
 * @param sourceCopies the {@code source_copies} rows the tables make
 */
record ItemTables(
        Map<String, Recipe> recipes,
        List<ItemModel> itemModels,
        List<Potion> potions,
        Map<String, SourceCopy> sourceCopies) {

    static final String RECIPES = "recipes";
    static final String POTIONS = "potions";
    private static final String MATERIALS = "materials";
    private static final String CONTENT_BOOK_ACTIVITIES = "content_book_activities";

    static final Set<String> NAMES = Set.of(RECIPES, MATERIALS, CONTENT_BOOK_ACTIVITIES, POTIONS);

    /** The state of a content book activity drawn from its art as it is. */
    private static final String BOOK_ACTIVE = "active";

    /** The key of a table's model prefix. */
    private static final String MODELS = "models";

    /** The {@code materials} family key for the names drawn from vanilla's own art. */
    private static final String VANILLA = "vanilla";

    /** The recipe every potion's liquid is a copy of, in its family's colour. */
    private static final String POTION_RECIPE = "potion_overlay";

    /** The pattern of a potion family's Wynncraft models: this, its name, then the fills. */
    private static final String POTION_MODELS = "minecraft:item/wynn/potion/";

    /** Wynncraft's potions all draw one of these three, whatever the bottle holds. */
    private static final String POTION_FILLS = "_(empty|half|full)";

    /** Where Avomod's potion models are. */
    private static final String AVOMOD_MODELS = RetroAssetIds.AVOMOD_NAMESPACE + ":item/";

    /** The rows made so far, and the shapes they come in. */
    private record Rows(List<ItemModel> itemModels, Map<String, SourceCopy> sourceCopies) {

        /** A row for one model id, drawing a texture held flat. */
        void flat(String model, String texture) {
            itemModels.add(
                    new ItemModel(Pattern.compile(model), texture, ParentKind.GENERATED, null));
        }

        /** A row for one model id, drawing a copy of a recipe's art in a colour held flat. */
        void coloured(String model, String copy, Recipe recipe, int colour) {
            sourceCopies.put(copy, new SourceCopy(recipe, colour));
            flat(model, copy);
        }
    }

    static ItemTables parse(Map<?, ?> sections) {
        Map<String, Recipe> recipes = recipes(sections.get(RECIPES));
        Rows rows = new Rows(new ArrayList<>(), new LinkedHashMap<>());
        materials(sections.get(MATERIALS), recipes, rows);
        contentBook(sections.get(CONTENT_BOOK_ACTIVITIES), rows);
        List<Potion> potions = potions(sections.get(POTIONS), recipes, rows);
        return new ItemTables(recipes, rows.itemModels(), potions, rows.sourceCopies());
    }

    /**
     * The recipe {@code name} names.
     *
     * @throws IllegalArgumentException when there is none
     */
    static Recipe recipe(Map<String, Recipe> recipes, Object name, String where) {
        Recipe recipe = recipes.get(name);
        if (recipe == null) throw new IllegalArgumentException(where + ": no recipe " + name);
        return recipe;
    }

    private static Map<String, Recipe> recipes(Object section) {
        return ManifestYaml.textRows(
                section,
                ManifestYaml.inTextures(RECIPES),
                (name, value, rowWhere) -> {
                    ManifestYaml.name(name, rowWhere);
                    if (name.equals(MODELS) || name.equals(VANILLA)) {
                        throw new IllegalArgumentException(rowWhere + ": a family's own key");
                    }
                    Map<?, ?> fields = ManifestYaml.mapping(value, rowWhere);
                    ManifestYaml.onlyKeys(fields, rowWhere, "art", "scale_from", "hue_of");
                    return Recipe.read(
                            ManifestYaml.requiredId(fields.get("art"), rowWhere, "no art"),
                            fields,
                            rowWhere);
                });
    }

    private static void materials(Object section, Map<String, Recipe> recipes, Rows rows) {
        String where = ManifestYaml.inTextures(MATERIALS);
        for (Map.Entry<?, ?> family : ManifestYaml.mapping(section, where).entrySet()) {
            material(
                    ManifestYaml.name(family.getKey(), where),
                    ManifestYaml.mapping(family.getValue(), where + ": " + family.getKey()),
                    where + ": " + family.getKey(),
                    recipes,
                    rows);
        }
    }

    /** One family: the name under {@code vanilla} and under each recipe, each making its rows. */
    private static void material(
            String family, Map<?, ?> fields, String where, Map<String, Recipe> recipes, Rows rows) {
        String models = ManifestYaml.requiredId(fields.get(MODELS), where, "no models");
        Set<String> seen = new HashSet<>();
        for (Map.Entry<?, ?> heading : fields.entrySet()) {
            if (MODELS.equals(heading.getKey())) continue;
            boolean vanilla = VANILLA.equals(heading.getKey());
            Recipe recipe = vanilla ? null : recipe(recipes, heading.getKey(), where);
            String headingWhere = where + ": " + heading.getKey();
            for (Map.Entry<?, ?> row :
                    ManifestYaml.mapping(heading.getValue(), headingWhere).entrySet()) {
                String name = ManifestYaml.name(row.getKey(), headingWhere);
                String rowWhere = headingWhere + ": " + name;
                if (!seen.add(name)) {
                    throw new IllegalArgumentException(rowWhere + ": listed twice");
                }
                if (vanilla) {
                    rows.flat(
                            models + name,
                            ManifestYaml.requiredId(row.getValue(), rowWhere, "no texture"));
                } else {
                    rows.coloured(
                            models + name,
                            RetroAssetIds.materialCopy(family, name),
                            recipe,
                            ManifestYaml.colour(row.getValue(), rowWhere));
                }
            }
        }
    }

    /** One row for each family's Wynncraft models, and one for its Avomod model if it has one. */
    private static List<Potion> potions(Object section, Map<String, Recipe> recipes, Rows rows) {
        String where = ManifestYaml.inTextures(POTIONS);
        List<Potion> potions = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ManifestYaml.mapping(section, where).entrySet()) {
            String family = ManifestYaml.name(entry.getKey(), where);
            String rowWhere = where + ": " + family;
            Map<?, ?> fields = ManifestYaml.mapping(entry.getValue(), rowWhere);
            ManifestYaml.onlyKeys(fields, rowWhere, "colour", "avomod");
            int colour = ManifestYaml.colour(fields.get("colour"), rowWhere + ": colour");
            String liquid = RetroAssetIds.potionLiquid(family);
            rows.sourceCopies()
                    .put(liquid, new SourceCopy(recipe(recipes, POTION_RECIPE, rowWhere), colour));
            potions.add(new Potion(Pattern.compile(POTION_MODELS + family + POTION_FILLS), liquid));
            if (fields.get("avomod") != null) {
                String avomod = ManifestYaml.name(fields.get("avomod"), rowWhere + ": avomod");
                potions.add(new Potion(Pattern.compile(AVOMOD_MODELS + avomod), liquid));
            }
        }
        return potions;
    }

    private static void contentBook(Object section, Rows rows) {
        String where = ManifestYaml.inTextures(CONTENT_BOOK_ACTIVITIES);
        Map<?, ?> fields = ManifestYaml.mapping(section, where);
        if (fields.isEmpty()) return;
        ManifestYaml.onlyKeys(fields, where, MODELS, "states", "activities");
        String models = ManifestYaml.requiredId(fields.get(MODELS), where, "no models");
        Map<?, ?> states = ManifestYaml.mapping(fields.get("states"), where + ": states");
        Map<?, ?> activities =
                ManifestYaml.mapping(fields.get("activities"), where + ": activities");
        for (Map.Entry<?, ?> row : activities.entrySet()) {
            String activity = ManifestYaml.name(row.getKey(), where + ": activities");
            String art =
                    ManifestYaml.requiredId(
                            row.getValue(), where + ": activities: " + activity, "no art");
            rows.flat(models + activity + "_" + BOOK_ACTIVE, art);
            for (Map.Entry<?, ?> entry : states.entrySet()) {
                String state = ManifestYaml.name(entry.getKey(), where + ": states");
                String stateWhere = where + ": states: " + state;
                Map<?, ?> how = ManifestYaml.mapping(entry.getValue(), stateWhere);
                ManifestYaml.onlyKeys(how, stateWhere, "scale_from", "hue_of", "colour");
                rows.coloured(
                        models + activity + "_" + state,
                        RetroAssetIds.bookCopy(activity, state),
                        Recipe.read(art, how, stateWhere),
                        ManifestYaml.colour(how.get("colour"), stateWhere + ": colour"));
            }
        }
    }
}
