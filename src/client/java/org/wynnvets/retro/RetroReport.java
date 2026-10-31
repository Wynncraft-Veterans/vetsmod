package org.wynnvets.retro;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.RetroSource;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * What one generation of the retro pack did: the Wynncraft pack it was built over, the sources
 * it could read, how many files each rule emitted and how many textures each kind of keep left
 * alone, the {@code wynncraft_copies} it wrote, the items whose vanilla definition it copied for
 * {@code gear_box_icons}, the items it generated and the {@code image_edits} fills it painted,
 * every rule it could not apply, an {@code image_edits} fill its palette guard refused among
 * them, and what it looked at and decided ({@link Seen}). The generator builds one per
 * generation; {@link RetroModeManager#lastReport()} keeps the latest, which
 * {@code /wv debug trigger retroDump} summarises and {@code /wv debug tree retro report} writes out in full.
 *
 * @param wynncraftPack the id of the Wynncraft pack the retro pack sits above
 * @param sources the sources that were present, in {@link RetroSource} order
 * @param counts how many of each {@link Count}; every count is present, zero or more
 * @param wynncraftCopies the {@code wynncraft_copies} textures written, by their {@code vetsmod}
 *     ids
 * @param copiedItems the items, such as {@code minecraft:bow}, whose vanilla item definition was
 *     copied for {@code gear_box_icons}, to
 *     {@link org.wynnvets.retro.rules.RetroAssetIds#itemDefinitionCopy
 *     RetroAssetIds.itemDefinitionCopy}
 * @param generatedItems the item definitions written for {@code accessory_tiers} and
 *     {@code outlined_icons}, by their {@code vetsmod} ids
 * @param imageEdits the rectangles {@code image_edits} painted, in the rule files' order, by the
 *     id of the texture written with them; a fill its palette guard refused is not among them
 * @param skipped one line per rule that could not be applied, such as an alias whose target no
 *     source has, in the order the generator met them
 * @param partsOff the parts it left out, which explains a low count or a row that decided nothing
 * @param millis how long the generation took
 * @param seen what it looked at and decided
 */
public record RetroReport(
        String wynncraftPack,
        List<RetroSource> sources,
        Map<Count, Integer> counts,
        Set<String> wynncraftCopies,
        Set<String> copiedItems,
        Set<String> generatedItems,
        Map<String, List<Rect>> imageEdits,
        List<String> skipped,
        List<RetroPart> partsOff,
        long millis,
        Seen seen) {

    /** Copies the lists, the sets and the map, and the counts with a zero for each one missing. */
    public RetroReport {
        sources = List.copyOf(sources);
        partsOff = List.copyOf(partsOff);
        wynncraftCopies = Set.copyOf(wynncraftCopies);
        copiedItems = Set.copyOf(copiedItems);
        generatedItems = Set.copyOf(generatedItems);
        Map<String, List<Rect>> edits = new HashMap<>();
        imageEdits.forEach((textureId, rects) -> edits.put(textureId, List.copyOf(rects)));
        imageEdits = Map.copyOf(edits);
        Map<Count, Integer> all = new EnumMap<>(Count.class);
        for (Count count : Count.values()) all.put(count, counts.getOrDefault(count, 0));
        counts = Collections.unmodifiableMap(all);
        skipped = List.copyOf(skipped);
    }

    /**
     * What a generation looked at and decided, beyond what it counts. The report command keeps
     * the expensive work for itself: it measures nothing here, and reads the packs again only
     * when it is asked ({@code org.wynnvets.debug.retro.RetroAudit}).
     *
     * @param textures every texture the generation decided, with its decision after
     *     {@code layer_groups}, in id order
     * @param wynncraftTextures the textures the Wynncraft pack ships
     * @param models the item models the stubs were decided for: Wynncraft's under
     *     {@code minecraft:item/wynn/} and Avomod's, in id order
     * @param stubs the models among them that a stub replaced
     * @param equipment the equipment assets the Wynncraft pack ships, in id order
     * @param tintFactors by texture id, the factors each tinted texture's red, green and blue were
     *     multiplied by
     */
    public record Seen(
            Map<String, TextureDecision> textures,
            Set<String> wynncraftTextures,
            List<String> models,
            Set<String> stubs,
            List<String> equipment,
            Map<String, List<Double>> tintFactors) {

        /** Copies every set, list and map, keeping the order of {@code textures}. */
        public Seen {
            textures = Collections.unmodifiableMap(new LinkedHashMap<>(textures));
            wynncraftTextures = Set.copyOf(wynncraftTextures);
            models = List.copyOf(models);
            stubs = Set.copyOf(stubs);
            equipment = List.copyOf(equipment);
            Map<String, List<Double>> factors = new HashMap<>();
            tintFactors.forEach((textureId, rgb) -> factors.put(textureId, List.copyOf(rgb)));
            tintFactors = Map.copyOf(factors);
        }
    }

    /** What a generation counts. The keeps count textures, not files: a keep emits nothing. */
    public enum Count {
        /** Textures {@code same_name} copied in. */
        SAME_NAME,
        /** Textures {@code aliases} copied in. */
        ALIASES,
        /** Textures {@code tints} wrote, each a source's texture tinted. */
        TINTS,
        /** {@code .mcmeta} files copied in beside those textures. */
        MCMETA,
        /**
         * Vanilla textures {@code same_name} would have copied over textures Wynncraft does not
         * ship. They are left out: the lower packs already show vanilla's, or a resource pack the
         * player chose.
         */
        UNCHANGED,
        /** Copies {@code image_edits} painted on, each counted once however many fills it took. */
        IMAGE_EDITS,
        /** Textures {@code wynncraft_copies} copied out of Wynncraft's pack. */
        WYNNCRAFT_COPIES,
        /** Textures {@code source_copies} and the tables copied out of the sources. */
        SOURCE_COPIES,
        /** Textures {@code blank_textures} wrote fully transparent. */
        BLANK_TEXTURES,
        /** Textures {@code wynntils_textures} drew in place of Wynntils' own. */
        WYNNTILS_TEXTURES,
        /** Item-model stubs from {@code item_models}. */
        ITEM_MODELS,
        /** Icons {@code outlined_icons} outlined, each with its model and item definition. */
        OUTLINED_ICONS,
        /** Textures {@code accessory_tiers} tinted, each with its model and item definition. */
        ACCESSORY_TIERS,
        /** Block-model stubs from {@code block_models}. */
        BLOCK_MODELS,
        /** Potion stubs from {@code potions}. */
        POTIONS,
        /** Ingredient stubs from {@code retro_ingredients.yml}. */
        INGREDIENTS,
        /** Wynncraft's equipment assets {@code equipment} replaced with vanilla's. */
        EQUIPMENT,
        /** Vanilla item definitions copied under vetsmod's namespace for {@code gear_box_icons}. */
        ITEM_DEFINITIONS,
        /** Textures {@code keep_wynncraft} kept. */
        KEEP_WYNNCRAFT,
        /** Copies a layer group undid, because the group could not be replaced whole. */
        LAYER_GROUPS,
        /** Copies, tints and blanks left out because their part is off. */
        PARTS_OFF,
        /**
         * Textures Wynncraft ships that no rule replaced, its deliberate blanks among them: the
         * generator decodes no image, so it does not tell a fully transparent one apart.
         */
        NO_RULE
    }

    /**
     * The report on one line, as the log and {@code /wv debug trigger retroDump} show it: each count by
     * name, the number skipped, the time taken, the Wynncraft pack and any parts left out.
     *
     * @return the summary, such as {@code same_name=1180 aliases=41 ... skipped=2, 930 ms over
     *     wynntils_preloaded/<sha1>, parts off: mobs, hud}
     */
    public String summary() {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<Count, Integer> count : counts.entrySet()) {
            line.append(count.getKey().name().toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(count.getValue())
                    .append(' ');
        }
        return line.append("skipped=")
                .append(skipped.size())
                .append(", ")
                .append(millis)
                .append(" ms over ")
                .append(wynncraftPack)
                .append(partsOff.isEmpty() ? "" : ", parts off: " + partsOffNames())
                .toString();
    }

    /**
     * The parts left out, for a line of text.
     *
     * @return their names separated by commas; empty for none
     */
    public String partsOffNames() {
        return RetroPart.names(partsOff, ", ");
    }
}
