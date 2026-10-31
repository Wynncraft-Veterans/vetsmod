package org.wynnvets.retro.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.wynnvets.retro.manifest.ModelSections;
import org.wynnvets.retro.manifest.TextureSections;

/**
 * What one generation's ids made of the rule files' rows, for the retro report. It sits beside the
 * rules because its ranking of the sections must mirror {@link TextureRules#decide} and
 * {@link ModelRules#modelStub}.
 *
 * @param rows every pattern row of the sections {@link #of} checks, in the order it lists them,
 *     each section's rows in file order
 * @param equipmentWithoutRow the equipment assets no {@code equipment} row matches, in the order
 *     given
 * @param stemsWithoutRow the ingredient stems with no row in {@code retro_ingredients.yml}, in the
 *     order given
 */
public record RuleUsage(
        List<RowUse> rows, List<String> equipmentWithoutRow, List<String> stemsWithoutRow) {

    /** Copies the lists. */
    public RuleUsage {
        rows = List.copyOf(rows);
        equipmentWithoutRow = List.copyOf(equipmentWithoutRow);
        stemsWithoutRow = List.copyOf(stemsWithoutRow);
    }

    /**
     * How many ids one row of the rule files decided.
     *
     * @param section the row's section, as the rule files name it
     * @param pattern the row's pattern
     * @param ids how many of the ids the row decided; 0 for a row that decided nothing
     */
    public record RowUse(String section, String pattern, int ids) {}

    /**
     * How many of one generation's ids each pattern row decided, and the ids no row covers. A row
     * decides an id when it is the first row of its section to match it, or, in a list section
     * ({@code keep_wynncraft}, {@code same_name}, {@code layer_groups}), whenever it matches it; an
     * id a higher section takes is left out of the sections below it. The sections, in the order
     * the rows come back:
     *
     * <ul>
     *   <li>over the textures: {@code sources}, {@code keep_wynncraft}, then {@code tints} over
     *       the textures no {@code keep_wynncraft} row matches, {@code aliases} over those no tint
     *       row matches either, {@code same_name} over those no alias matches either, and
     *       {@code layer_groups} over them all;</li>
     *   <li>over the models other than ingredients': {@code potions}, then {@code item_models}
     *       over the models no {@code potions} row matches;</li>
     *   <li>over the equipment assets: {@code equipment}.</li>
     * </ul>
     *
     * <p>Whether a source had what a row reads, layer groups and transparency are not counted: a
     * row that matched an id counts even when the generation then wrote nothing for it, which its
     * report's skipped lines say.</p>
     *
     * @param textureRows the rule files' texture sections
     * @param modelRows the rule files' model sections
     * @param textures the texture ids decided, such as a generation's
     * @param models the item model ids the stubs were decided for, ingredients' among them
     * @param equipment the equipment asset ids
     * @return the count of every row, and the equipment assets and ingredient stems no row covers
     */
    public static RuleUsage of(
            TextureSections textureRows,
            ModelSections modelRows,
            Collection<String> textures,
            Collection<String> models,
            Collection<String> equipment) {
        List<RowUse> rows = new ArrayList<>();
        countDecided(
                "sources",
                patterns(textureRows.sources(), TextureSections.SourceRow::textures),
                true,
                textures,
                rows);
        List<String> unkept =
                countDecided("keep_wynncraft", textureRows.keepWynncraft(), false, textures, rows);
        List<String> untinted =
                countDecided(
                        "tints",
                        patterns(textureRows.tints(), TextureSections.Tint::textures),
                        true,
                        unkept,
                        rows);
        List<String> unaliased =
                countDecided(
                        "aliases",
                        patterns(textureRows.aliases(), TextureSections.Alias::textures),
                        true,
                        untinted,
                        rows);
        countDecided("same_name", textureRows.sameName(), false, unaliased, rows);
        countDecided("layer_groups", textureRows.layerGroups(), false, textures, rows);

        List<String> items = new ArrayList<>();
        List<String> stemsWithoutRow = new ArrayList<>();
        for (String model : models) {
            String stem = ModelRules.ingredientStem(model);
            if (stem == null) {
                if (model != null) items.add(model);
            } else if (!modelRows.ingredients().containsKey(stem)) {
                stemsWithoutRow.add(stem);
            }
        }
        List<String> unpotioned =
                countDecided(
                        "potions",
                        patterns(modelRows.potions(), ModelSections.Potion::models),
                        true,
                        items,
                        rows);
        countDecided(
                "item_models",
                patterns(modelRows.itemModels(), ModelSections.ItemModel::models),
                true,
                unpotioned,
                rows);

        List<String> equipmentWithoutRow =
                countDecided(
                        "equipment",
                        patterns(modelRows.equipment(), ModelSections.Equipment::assets),
                        true,
                        equipment,
                        rows);
        return new RuleUsage(rows, equipmentWithoutRow, stemsWithoutRow);
    }

    private static <R> List<Pattern> patterns(List<R> rows, Function<R, Pattern> pattern) {
        return rows.stream().map(pattern).toList();
    }

    /**
     * Counts how many of {@code ids} each of {@code section}'s rows decides, adding a
     * {@link RowUse} per row to {@code into}: the first row that matches an id, or with
     * {@code firstOnly} false every row that does.
     *
     * @return the ids no row matches, in their order; a {@code null} id is skipped
     */
    private static List<String> countDecided(
            String section,
            List<Pattern> rows,
            boolean firstOnly,
            Collection<String> ids,
            List<RowUse> into) {
        int[] decided = new int[rows.size()];
        List<String> unmatched = new ArrayList<>();
        for (String id : ids) {
            if (id == null) continue;
            boolean matched = false;
            for (int i = 0; i < rows.size() && !(matched && firstOnly); i++) {
                if (rows.get(i).matcher(id).matches()) {
                    decided[i]++;
                    matched = true;
                }
            }
            if (!matched) unmatched.add(id);
        }
        for (int i = 0; i < rows.size(); i++) {
            into.add(new RowUse(section, rows.get(i).pattern(), decided[i]));
        }
        return unmatched;
    }
}
