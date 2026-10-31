package org.wynnvets.retro.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The sections the game reads while it draws Wynncraft's screens.
 *
 * <p>Beyond the rules every section keeps, these fail the parse: a slot, row or column a chest does
 * not have, a {@code slot_fills} row that names both a container and a title, neither, or no slots,
 * a {@code slot_values} lore pattern without a group, and a glyph outside the private use area.
 *
 * @param containerTitles {@code container_titles}: the titles drawn on Wynncraft's containers; the
 *     first matching row decides
 * @param glyphTitles {@code glyph_titles}: a glyph's code point to the title drawn on a screen
 *     whose title of glyphs has it
 * @param slotFills {@code slot_fills}: the slots drawn as white glass
 * @param slotValues {@code slot_values}: values drawn in a button's slot, in file order
 * @param hiddenInventoryEffects {@code hidden_inventory_effects}: effect ids left out of the
 *     inventory's effect list while they last forever
 */
public record ScreenSections(
        List<ContainerTitle> containerTitles,
        Map<Integer, String> glyphTitles,
        List<SlotFill> slotFills,
        List<SlotValue> slotValues,
        List<Pattern> hiddenInventoryEffects) {

    /** The highest column of a chest; a chest is nine columns wide. */
    public static final int LAST_COLUMN = 8;

    private static final String CONTAINER_TITLES = "container_titles";
    private static final String GLYPH_TITLES = "glyph_titles";
    private static final String SLOT_FILLS = "slot_fills";
    private static final String SLOT_VALUES = "slot_values";
    private static final String HIDDEN_INVENTORY_EFFECTS = "hidden_inventory_effects";

    static final Set<String> NAMES =
            Set.of(
                    CONTAINER_TITLES,
                    GLYPH_TITLES,
                    SLOT_FILLS,
                    SLOT_VALUES,
                    HIDDEN_INVENTORY_EFFECTS);

    /** The {@code slot_fills} {@code player} value: the player's slots Wynntils names. */
    private static final String WYNNTILS_PLAYER_SLOTS = "wynntils";

    /** The {@code slot_fills} {@code slots} value for every slot outside Wynntils' bounds. */
    private static final String OUTSIDE_BOUNDS = "outside_bounds";

    /** The {@code slot_fills} {@code slots} value for every slot. */
    private static final String ALL_SLOTS = "all";

    /** The highest row of a chest; a chest is at most six rows. */
    private static final int LAST_ROW = 5;

    /** The highest slot of a chest, counted across then down from 0. */
    private static final int LAST_SLOT = 53;

    /** What a {@code glyph_titles} key looks like: a character in the private use area. */
    private static final Pattern GLYPH = Pattern.compile("[EF][0-9A-F]{3}");

    /** Copies every list and map, keeping their order. */
    public ScreenSections {
        containerTitles = List.copyOf(containerTitles);
        glyphTitles = ManifestYaml.frozen(glyphTitles);
        slotFills = List.copyOf(slotFills);
        slotValues = List.copyOf(slotValues);
        hiddenInventoryEffects = List.copyOf(hiddenInventoryEffects);
    }

    /**
     * A {@code container_titles} row.
     *
     * @param containers the simple names of the Wynntils container classes it covers, such as
     *     {@code AccountBankContainer}
     * @param title the title drawn on such a container, or {@link RetroManifest#KEEP} for
     *     Wynncraft's label
     */
    public record ContainerTitle(Pattern containers, String title) {}

    /**
     * A {@code slot_values} row.
     *
     * @param containers the simple names of the Wynntils container classes it covers
     * @param name the exact name of the item whose slot shows the value
     * @param lore the pattern found in a lore line; its first group is the value
     */
    public record SlotValue(Pattern containers, String name, Pattern lore) {}

    /** A {@code slot_fills} row: slots that show white glass, under their item if they have one. */
    public sealed interface SlotFill {

        /**
         * {@code {player: wynntils}}: the player's own slots that Wynntils names (the compass, the
         * content book, the accessories and the ingredient pouch), wherever slot fills apply.
         */
        record PlayerSlots() implements SlotFill {}

        /**
         * A row of a container's slots, counted in a chest's grid: nine columns, up to six rows,
         * slot {@code n} at row {@code n / 9} and column {@code n % 9}. Exactly one of
         * {@code containers} and {@code titles} is set. The slots painted are the union of
         * {@code rows}, {@code cols}, {@code slots} and, with {@code outsideBounds}, every slot
         * outside Wynntils' bounds; with {@code all}, every slot. At least one of them picks some.
         * A slot in {@code except} is never painted by this row, and a container {@code unless}
         * matches is not one it covers. A {@code fallback} row covers a container only when no row
         * before it did.
         *
         * @param containers the simple names of the Wynntils container classes it covers, or
         *     {@code null}
         * @param titles the titles, as retro draws them, of the containers it covers, or
         *     {@code null}
         * @param rows whole rows, 0 the top
         * @param cols whole columns, 0 the left
         * @param slots single slots
         * @param outsideBounds every slot outside the area Wynntils says holds the container's
         *     items
         * @param all every slot
         * @param except slots this row never paints, whatever else it picks: the ones the player
         *     fills or sets
         * @param unless the containers it skips, by the simple name of their Wynntils class or by
         *     their title as retro draws it, or {@code null}
         * @param fallback whether it applies only where no earlier row applied
         */
        record ContainerSlots(
                Pattern containers,
                Pattern titles,
                Set<Integer> rows,
                Set<Integer> cols,
                Set<Integer> slots,
                boolean outsideBounds,
                boolean all,
                Set<Integer> except,
                Pattern unless,
                boolean fallback)
                implements SlotFill {
            /** Copies the sets. */
            public ContainerSlots {
                rows = Set.copyOf(rows);
                cols = Set.copyOf(cols);
                slots = Set.copyOf(slots);
                except = Set.copyOf(except);
            }
        }
    }

    static ScreenSections parse(Map<?, ?> sections) {
        return new ScreenSections(
                containerTitles(sections.get(CONTAINER_TITLES)),
                glyphTitles(sections.get(GLYPH_TITLES)),
                slotFills(sections.get(SLOT_FILLS)),
                slotValues(sections.get(SLOT_VALUES)),
                ManifestYaml.patterns(
                        sections.get(HIDDEN_INVENTORY_EFFECTS),
                        ManifestYaml.inTextures(HIDDEN_INVENTORY_EFFECTS)));
    }

    void addCounts(Map<String, Integer> counts) {
        counts.put(CONTAINER_TITLES, containerTitles.size());
        counts.put(GLYPH_TITLES, glyphTitles.size());
        counts.put(SLOT_FILLS, slotFills.size());
        counts.put(SLOT_VALUES, slotValues.size());
        counts.put(HIDDEN_INVENTORY_EFFECTS, hiddenInventoryEffects.size());
    }

    private static List<ContainerTitle> containerTitles(Object section) {
        return ManifestYaml.patternRows(
                section,
                ManifestYaml.inTextures(CONTAINER_TITLES),
                (pattern, value, rowWhere) ->
                        new ContainerTitle(
                                pattern,
                                ManifestYaml.requiredText(
                                        value, rowWhere, "expected a title or 'keep'")));
    }

    private static Map<Integer, String> glyphTitles(Object section) {
        String where = ManifestYaml.inTextures(GLYPH_TITLES);
        Map<Integer, String> rows = new LinkedHashMap<>();
        ManifestYaml.textRows(
                        section,
                        where,
                        (glyph, value, rowWhere) -> {
                            if (!GLYPH.matcher(glyph).matches()) {
                                throw new IllegalArgumentException(
                                        where + ": expected a glyph such as 'E006', not " + glyph);
                            }
                            return ManifestYaml.requiredText(value, rowWhere, "no title");
                        })
                .forEach((glyph, title) -> rows.put(Integer.parseInt(glyph, 16), title));
        return rows;
    }

    private static List<SlotFill> slotFills(Object section) {
        return ManifestYaml.listRows(
                section,
                ManifestYaml.inTextures(SLOT_FILLS),
                List.of(
                        "player",
                        "container",
                        "title",
                        "rows",
                        "cols",
                        "slots",
                        "except",
                        "unless",
                        "fallback"),
                ScreenSections::slotFill);
    }

    private static SlotFill slotFill(Map<?, ?> fields, String rowWhere) {
        if (fields.containsKey("player")) {
            if (!WYNNTILS_PLAYER_SLOTS.equals(fields.get("player")) || fields.size() != 1) {
                throw new IllegalArgumentException(
                        rowWhere + ": a player row is exactly {player: wynntils}");
            }
            return new SlotFill.PlayerSlots();
        }
        Pattern container = optionalPattern(fields.get("container"), rowWhere + ": container");
        Pattern title = optionalPattern(fields.get("title"), rowWhere + ": title");
        if ((container == null) == (title == null)) {
            throw new IllegalArgumentException(
                    rowWhere + ": a row names a container or a title, not both or neither");
        }
        Object slots = fields.get("slots");
        boolean outsideBounds = OUTSIDE_BOUNDS.equals(slots);
        boolean all = ALL_SLOTS.equals(slots);
        SlotFill.ContainerSlots row =
                new SlotFill.ContainerSlots(
                        container,
                        title,
                        ManifestYaml.numbers(fields.get("rows"), LAST_ROW, rowWhere + ": rows"),
                        ManifestYaml.numbers(fields.get("cols"), LAST_COLUMN, rowWhere + ": cols"),
                        outsideBounds || all
                                ? Set.of()
                                : ManifestYaml.numbers(slots, LAST_SLOT, rowWhere + ": slots"),
                        outsideBounds,
                        all,
                        ManifestYaml.numbers(
                                fields.get("except"), LAST_SLOT, rowWhere + ": except"),
                        optionalPattern(fields.get("unless"), rowWhere + ": unless"),
                        ManifestYaml.flag(fields.get("fallback"), rowWhere + ": fallback"));
        if (row.rows().isEmpty()
                && row.cols().isEmpty()
                && row.slots().isEmpty()
                && !outsideBounds
                && !all) {
            throw new IllegalArgumentException(rowWhere + ": picks no slots");
        }
        return row;
    }

    private static Pattern optionalPattern(Object value, String where) {
        String regex = ManifestYaml.text(value, where);
        return regex == null ? null : Pattern.compile(regex);
    }

    private static List<SlotValue> slotValues(Object section) {
        return ManifestYaml.listRows(
                section,
                ManifestYaml.inTextures(SLOT_VALUES),
                List.of("container", "name", "lore"),
                (fields, rowWhere) -> {
                    String container =
                            ManifestYaml.text(fields.get("container"), rowWhere + ": container");
                    String name = ManifestYaml.text(fields.get("name"), rowWhere + ": name");
                    String lore = ManifestYaml.text(fields.get("lore"), rowWhere + ": lore");
                    if (container == null || name == null || lore == null) {
                        throw new IllegalArgumentException(
                                rowWhere
                                        + ": a row is exactly a container, a name and a lore"
                                        + " pattern");
                    }
                    Pattern lorePattern = Pattern.compile(lore);
                    if (lorePattern.matcher("").groupCount() < 1) {
                        throw new IllegalArgumentException(
                                rowWhere + ": the lore pattern needs a group");
                    }
                    return new SlotValue(Pattern.compile(container), name, lorePattern);
                });
    }
}
