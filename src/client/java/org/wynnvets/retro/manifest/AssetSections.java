package org.wynnvets.retro.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * The sections that make files of the pack's own, or paint over the ones it copies.
 *
 * <p>Beyond the rules every section keeps, these fail the parse: a generated file outside the
 * namespace its section writes, a fill without a palette, a {@code wynntils_textures} row with
 * neither {@code blank} nor frames, a language code that is not one word, a {@code source_copies}
 * row with an unknown recipe, and a copy {@link ItemTables} already makes.
 *
 * @param recipes {@code recipes}: a recipe's name to the recipe, in file order
 * @param imageEdits {@code image_edits}: texture id to the fills painted on its copy, in order
 * @param wynncraftCopies {@code wynncraft_copies}: a {@code vetsmod} texture id to the Wynncraft
 *     texture copied there
 * @param sourceCopies a {@code vetsmod} texture id to the texture copied there from its sources,
 *     and how that copy is recoloured: the rows {@link ItemTables} makes, then
 *     {@code source_copies}' own
 * @param blankTextures {@code blank_textures}: the textures written fully transparent
 * @param wynntilsTextures {@code wynntils_textures}: a {@code wynntils} texture id to the frames
 *     drawn in its place, top first; no frames for a fully transparent texture
 * @param lang {@code lang}: the strings the pack's language files hold
 */
public record AssetSections(
        Map<String, Recipe> recipes,
        Map<String, List<Fill>> imageEdits,
        Map<String, String> wynncraftCopies,
        Map<String, SourceCopy> sourceCopies,
        List<String> blankTextures,
        Map<String, List<Frame>> wynntilsTextures,
        Lang lang) {

    private static final String IMAGE_EDITS = "image_edits";
    private static final String WYNNCRAFT_COPIES = "wynncraft_copies";
    private static final String SOURCE_COPIES = "source_copies";
    private static final String BLANK_TEXTURES = "blank_textures";
    private static final String WYNNTILS_TEXTURES = "wynntils_textures";
    private static final String LANG = "lang";

    static final Set<String> NAMES =
            Set.of(
                    IMAGE_EDITS,
                    WYNNCRAFT_COPIES,
                    SOURCE_COPIES,
                    BLANK_TEXTURES,
                    WYNNTILS_TEXTURES,
                    LANG);

    /** The namespace {@code wynntils_textures} writes into. */
    private static final String WYNNTILS_NAMESPACE = "wynntils";

    /** The {@code wynntils_textures} value for a fully transparent texture. */
    private static final String BLANK = "blank";

    /** A language code a file name can carry, such as {@code en_us}. */
    private static final Pattern LOCALE = Pattern.compile("[a-z0-9_.-]+");

    /** Copies every list and map, keeping their order. */
    public AssetSections {
        recipes = ManifestYaml.frozen(recipes);
        imageEdits = ManifestYaml.frozen(imageEdits);
        wynncraftCopies = ManifestYaml.frozen(wynncraftCopies);
        sourceCopies = ManifestYaml.frozen(sourceCopies);
        blankTextures = List.copyOf(blankTextures);
        wynntilsTextures = ManifestYaml.frozen(wynntilsTextures);
    }

    /**
     * An {@code image_edits} fill: a rectangle painted the colour of one of the texture's own
     * pixels, provided it holds only the palette's colours.
     *
     * @param rect the rectangle painted
     * @param sampleX the column of the pixel whose colour it is painted
     * @param sampleY the row of that pixel
     * @param palette the RGB values ({@code 0xRRGGBB}) the rectangle may hold
     */
    public record Fill(Rect rect, int sampleX, int sampleY, Set<Integer> palette) {
        /** Copies the palette. */
        public Fill {
            palette = Set.copyOf(palette);
        }
    }

    /**
     * A copy of a texture from its sources: a {@code source_copies} row, or a row of a table.
     *
     * @param recipe the art copied, and how it takes the colour
     * @param colour the colour it takes, {@code 0xRRGGBB}, or {@code null} for a copy of the file
     *     as it is
     */
    public record SourceCopy(Recipe recipe, Integer colour) {

        /** A copy of the file as it is. */
        static SourceCopy of(String art) {
            return new SourceCopy(Recipe.multiply(art), null);
        }

        /**
         * The art copied.
         *
         * @return the texture id, read from its sources
         */
        public String source() {
            return recipe.art();
        }

        /**
         * The recolour this row asks for.
         *
         * @return the operator on one pixel, or {@code null} for a copy of the file as it is
         */
        public IntUnaryOperator recolour() {
            return colour == null ? null : recipe.recolour(colour);
        }
    }

    /**
     * A {@code wynntils_textures} frame: a region of a texture, stretched to the frame's size the
     * way Minecraft draws a nine-slice GUI sprite.
     *
     * @param source the texture id the region is taken from
     * @param region the region, or {@code null} for the whole texture
     * @param border the width of the region's corners and edges, which are not tiled
     */
    public record Frame(String source, Rect region, int border) {}

    /**
     * The {@code lang} section.
     *
     * @param locales the language codes a file is written for, such as {@code en_us}
     * @param strings translation key to text, in file order
     */
    public record Lang(List<String> locales, Map<String, String> strings) {
        /** Copies the list and the map, keeping their order. */
        public Lang {
            locales = List.copyOf(locales);
            strings = ManifestYaml.frozen(strings);
        }
    }

    static AssetSections parse(Map<?, ?> sections, ItemTables tables) {
        return new AssetSections(
                tables.recipes(),
                imageEdits(sections.get(IMAGE_EDITS)),
                wynncraftCopies(sections.get(WYNNCRAFT_COPIES)),
                sourceCopies(sections.get(SOURCE_COPIES), tables),
                blankTextures(sections.get(BLANK_TEXTURES)),
                wynntilsTextures(sections.get(WYNNTILS_TEXTURES)),
                lang(sections.get(LANG)));
    }

    void addCounts(Map<String, Integer> counts) {
        counts.put(ItemTables.RECIPES, recipes.size());
        counts.put(IMAGE_EDITS, imageEdits.size());
        counts.put(WYNNCRAFT_COPIES, wynncraftCopies.size());
        counts.put(SOURCE_COPIES, sourceCopies.size());
        counts.put(BLANK_TEXTURES, blankTextures.size());
        counts.put(WYNNTILS_TEXTURES, wynntilsTextures.size());
        counts.put(LANG, lang.strings().size());
    }

    private static Map<String, List<Fill>> imageEdits(Object section) {
        return ManifestYaml.idRows(
                section,
                ManifestYaml.inTextures(IMAGE_EDITS),
                null,
                (texture, value, rowWhere) ->
                        List.copyOf(
                                ManifestYaml.listRows(
                                        value,
                                        rowWhere,
                                        List.of("fill", "sample", "palette"),
                                        AssetSections::fill)));
    }

    private static Fill fill(Map<?, ?> fields, String rowWhere) {
        Rect rect = ManifestYaml.rect(fields.get("fill"), rowWhere + ": fill");
        int[] sample = ManifestYaml.ints(fields.get("sample"), 2, rowWhere + ": sample");
        Set<Integer> palette =
                new LinkedHashSet<>(
                        ManifestYaml.colours(fields.get("palette"), rowWhere + ": palette"));
        if (palette.isEmpty()) {
            throw new IllegalArgumentException(rowWhere + ": a fill needs a palette");
        }
        return new Fill(rect, sample[0], sample[1], palette);
    }

    private static Map<String, String> wynncraftCopies(Object section) {
        return ManifestYaml.idRows(
                section,
                ManifestYaml.inTextures(WYNNCRAFT_COPIES),
                RetroAssetIds.NAMESPACE,
                (copy, value, rowWhere) ->
                        ManifestYaml.requiredId(value, rowWhere, "nothing to copy"));
    }

    private static Map<String, SourceCopy> sourceCopies(Object section, ItemTables tables) {
        Map<String, SourceCopy> rows = new LinkedHashMap<>(tables.sourceCopies());
        ManifestYaml.idRows(
                        section,
                        ManifestYaml.inTextures(SOURCE_COPIES),
                        RetroAssetIds.NAMESPACE,
                        (copy, value, rowWhere) -> sourceCopy(value, tables.recipes(), rowWhere))
                .forEach(
                        (copy, row) -> {
                            if (rows.putIfAbsent(copy, row) != null) {
                                throw new IllegalArgumentException(
                                        ManifestYaml.inTextures(SOURCE_COPIES)
                                                + ": a table already makes "
                                                + copy);
                            }
                        });
        return rows;
    }

    /** A {@code source_copies} row: the texture's id alone, or one recipe and its colour. */
    private static SourceCopy sourceCopy(
            Object value, Map<String, Recipe> recipes, String rowWhere) {
        if (!(value instanceof Map<?, ?> fields)) {
            return SourceCopy.of(ManifestYaml.requiredId(value, rowWhere, "nothing to copy"));
        }
        if (fields.size() != 1) {
            throw new IllegalArgumentException(rowWhere + ": a row names one recipe and a colour");
        }
        Map.Entry<?, ?> row = fields.entrySet().iterator().next();
        return new SourceCopy(
                ItemTables.recipe(recipes, row.getKey(), rowWhere),
                ManifestYaml.colour(row.getValue(), rowWhere));
    }

    private static List<String> blankTextures(Object section) {
        String where = ManifestYaml.inTextures(BLANK_TEXTURES);
        List<String> rows = new ArrayList<>();
        for (Object entry : ManifestYaml.list(section, where)) {
            rows.add(ManifestYaml.requiredId(entry, where, "an empty row"));
        }
        return rows;
    }

    private static Map<String, List<Frame>> wynntilsTextures(Object section) {
        return ManifestYaml.idRows(
                section,
                ManifestYaml.inTextures(WYNNTILS_TEXTURES),
                WYNNTILS_NAMESPACE,
                (texture, value, rowWhere) -> {
                    if (BLANK.equals(value)) return List.of();
                    List<Frame> frames =
                            ManifestYaml.listRows(
                                    value,
                                    rowWhere,
                                    List.of("source", "region", "border"),
                                    AssetSections::frame);
                    if (frames.isEmpty()) {
                        throw new IllegalArgumentException(
                                rowWhere + ": expected 'blank' or frames");
                    }
                    return List.copyOf(frames);
                });
    }

    private static Frame frame(Map<?, ?> fields, String rowWhere) {
        String source =
                ManifestYaml.requiredId(fields.get("source"), rowWhere + ": source", "no source");
        Object region = fields.get("region");
        return new Frame(
                source,
                region == null ? null : ManifestYaml.rect(region, rowWhere + ": region"),
                ManifestYaml.ints(fields.get("border"), 1, rowWhere + ": border")[0]);
    }

    private static Lang lang(Object section) {
        String where = ManifestYaml.inTextures(LANG);
        Map<?, ?> fields = ManifestYaml.mapping(section, where);
        ManifestYaml.onlyKeys(fields, where, "locales", "strings");
        List<String> locales = new ArrayList<>();
        for (Object locale : ManifestYaml.list(fields.get("locales"), where + ": locales")) {
            String code = ManifestYaml.text(locale, where + ": locales");
            if (code == null) continue;
            if (!LOCALE.matcher(code).matches()) {
                throw new IllegalArgumentException(where + ": not a language code: " + code);
            }
            locales.add(code);
        }
        Map<String, String> strings =
                ManifestYaml.textRows(
                        fields.get("strings"),
                        where + ": strings",
                        (key, value, rowWhere) ->
                                ManifestYaml.requiredText(value, rowWhere, "is empty"));
        return new Lang(locales, strings);
    }
}
