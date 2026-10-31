package org.wynnvets.retro.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The sections that decide how a single stack is drawn.
 *
 * <p>Beyond the rules every section keeps, these fail the parse: a gear or tome type that is not
 * one upper-case word, an outline or scale out of range, a tier without a lower-case name or a ramp
 * of two colours, a tier's {@code below} that does not rise, any tier but the last without one, and
 * accessory art without an {@code empty_outline}, a {@code stack_models} row's {@code part} that is
 * no {@link RetroPart}.
 *
 * @param stackModels {@code stack_models}: the single stacks drawn as another item
 * @param gearBoxIcons {@code gear_box_icons}: a Wynntils gear or tome type's name, such as
 *     {@code SPEAR}, to the vanilla item drawn over a gear box of that type, in file order
 * @param outlinedIcons {@code outlined_icons}: a gear or tome type to its outlined icon
 * @param accessoryTiers {@code accessory_tiers}: the accessories' art and tiers by level
 */
public record StackSections(
        List<StackModel> stackModels,
        Map<String, String> gearBoxIcons,
        Map<String, OutlinedIcon> outlinedIcons,
        AccessoryTiers accessoryTiers) {

    private static final String STACK_MODELS = "stack_models";
    private static final String GEAR_BOX_ICONS = "gear_box_icons";
    private static final String OUTLINED_ICONS = "outlined_icons";
    private static final String ACCESSORY_TIERS = "accessory_tiers";

    static final Set<String> NAMES =
            Set.of(STACK_MODELS, GEAR_BOX_ICONS, OUTLINED_ICONS, ACCESSORY_TIERS);

    /** What an {@code accessory_tiers} tier's name looks like: it goes into resource paths. */
    private static final Pattern TIER_NAME = Pattern.compile("[a-z_]+");

    /** Copies every list and map, keeping their order. */
    public StackSections {
        stackModels = List.copyOf(stackModels);
        gearBoxIcons = ManifestYaml.frozen(gearBoxIcons);
        outlinedIcons = ManifestYaml.frozen(outlinedIcons);
    }

    /**
     * A {@code stack_models} row.
     *
     * @param item the item model of the stacks it covers, such as {@code minecraft:splash_potion}
     * @param name the pattern on such a stack's whole name, without formatting codes
     * @param model the item model drawn instead
     * @param part the part it belongs to, {@link RetroPart#ITEMS} when the row names none
     */
    public record StackModel(String item, Pattern name, String model, RetroPart part) {}

    /**
     * An {@code outlined_icons} icon.
     *
     * @param art the texture it is drawn from
     * @param outlinePixels how many of the art's pixels thick its outline is
     * @param outlineColour the outline's colour, {@code 0xRRGGBB}
     * @param scale the art's size, as a share of the slot
     */
    public record OutlinedIcon(String art, int outlinePixels, int outlineColour, double scale) {}

    /**
     * The {@code accessory_tiers} section.
     *
     * @param outlineBelowLuma the brightness, 0 to 255, below which a pixel of the art turns the
     *     grey of its brightness instead of taking the ramp
     * @param emptyOutline the colour, {@code 0xRRGGBB}, of the outline of the art an empty slot
     *     shows
     * @param emptyLinesBelowLuma the brightness, 0 to 255, below which a pixel inside the art is
     *     drawn in that outline's colour too
     * @param art a name of Wynntils' gear type to the texture its accessories are drawn from
     * @param tiers the tiers, lowest level first
     */
    public record AccessoryTiers(
            int outlineBelowLuma,
            int emptyOutline,
            int emptyLinesBelowLuma,
            Map<String, String> art,
            List<AccessoryTier> tiers) {

        /** Freezes the rows. */
        public AccessoryTiers {
            art = ManifestYaml.frozen(art);
            tiers = List.copyOf(tiers);
        }
    }

    /**
     * An {@code accessory_tiers} tier.
     *
     * @param name its name, such as {@code gold}
     * @param below the level it stops at, not including it; {@link Integer#MAX_VALUE} on the last
     * @param ramp the colours, {@code 0xRRGGBB}, the art's brightness runs through
     */
    public record AccessoryTier(String name, int below, List<Integer> ramp) {

        /** Freezes the ramp. */
        public AccessoryTier {
            ramp = List.copyOf(ramp);
        }
    }

    static StackSections parse(Map<?, ?> sections) {
        return new StackSections(
                stackModels(sections.get(STACK_MODELS)),
                gearBoxIcons(sections.get(GEAR_BOX_ICONS)),
                outlinedIcons(sections.get(OUTLINED_ICONS)),
                accessoryTiers(sections.get(ACCESSORY_TIERS)));
    }

    void addCounts(Map<String, Integer> counts) {
        counts.put(STACK_MODELS, stackModels.size());
        counts.put(GEAR_BOX_ICONS, gearBoxIcons.size());
        counts.put(OUTLINED_ICONS, outlinedIcons.size());
        counts.put(ACCESSORY_TIERS, accessoryTiers.tiers().size());
    }

    private static List<StackModel> stackModels(Object section) {
        return ManifestYaml.listRows(
                section,
                ManifestYaml.inTextures(STACK_MODELS),
                List.of("item", "name", "model", "part"),
                (fields, rowWhere) -> {
                    String item =
                            ManifestYaml.literalId(fields.get("item"), null, rowWhere + ": item");
                    String name = ManifestYaml.text(fields.get("name"), rowWhere + ": name");
                    String model =
                            ManifestYaml.literalId(fields.get("model"), null, rowWhere + ": model");
                    if (item == null || name == null || model == null) {
                        throw new IllegalArgumentException(
                                rowWhere + ": a row needs item, name and model");
                    }
                    return new StackModel(
                            item,
                            Pattern.compile(name),
                            model,
                            stackPart(fields.get("part"), rowWhere + ": part"));
                });
    }

    private static RetroPart stackPart(Object value, String where) {
        String name = ManifestYaml.text(value, where);
        if (name == null) return RetroPart.ITEMS;
        RetroPart part = RetroPart.fromYaml(name);
        if (part == null) throw new IllegalArgumentException(where + ": no such part: " + name);
        return part;
    }

    private static Map<String, String> gearBoxIcons(Object section) {
        return ManifestYaml.typeRows(
                section,
                ManifestYaml.inTextures(GEAR_BOX_ICONS),
                "SPEAR",
                (gearType, value, rowWhere) -> ManifestYaml.requiredId(value, rowWhere, "no item"));
    }

    private static Map<String, OutlinedIcon> outlinedIcons(Object section) {
        Map<String, OutlinedIcon> rows = new LinkedHashMap<>();
        ManifestYaml.listRows(
                        section,
                        ManifestYaml.inTextures(OUTLINED_ICONS),
                        List.of("outline", "scale", "art"),
                        StackSections::outlinedGroup)
                .forEach(rows::putAll);
        return rows;
    }

    /** A group of {@code outlined_icons}: the types that share its outline and scale. */
    private static Map<String, OutlinedIcon> outlinedGroup(Map<?, ?> group, String where) {
        String outlineWhere = where + ": outline";
        Map<?, ?> outline = ManifestYaml.mapping(group.get("outline"), outlineWhere);
        ManifestYaml.onlyKeys(outline, outlineWhere, "pixels", "colour");
        int pixels = ManifestYaml.boundedInt(outline.get("pixels"), 0, 8, outlineWhere);
        int colour = ManifestYaml.colour(outline.get("colour"), outlineWhere);
        if (!(group.get("scale") instanceof Number scale)
                || scale.doubleValue() <= 0
                || scale.doubleValue() > 1) {
            throw new IllegalArgumentException(where + ": scale is above 0, to 1");
        }
        return ManifestYaml.typeRows(
                group.get("art"),
                where + ": art",
                "WEAPON_TOME",
                (type, value, rowWhere) ->
                        new OutlinedIcon(
                                ManifestYaml.requiredId(value, rowWhere, "no texture"),
                                pixels,
                                colour,
                                scale.doubleValue()));
    }

    private static AccessoryTiers accessoryTiers(Object section) {
        String where = ManifestYaml.inTextures(ACCESSORY_TIERS);
        Map<?, ?> fields = ManifestYaml.mapping(section, where);
        ManifestYaml.onlyKeys(
                fields,
                where,
                "outline_below_luma",
                "empty_outline",
                "empty_lines_below_luma",
                "art",
                "tiers");
        Map<String, String> art =
                ManifestYaml.typeRows(
                        fields.get("art"),
                        where + ": art",
                        "RING",
                        (gearType, value, rowWhere) ->
                                ManifestYaml.requiredId(value, rowWhere, "no texture"));
        int emptyOutline =
                art.isEmpty()
                        ? 0
                        : ManifestYaml.colour(
                                fields.get("empty_outline"), where + ": empty_outline");
        return new AccessoryTiers(
                optionalLuma(fields, "outline_below_luma", where),
                emptyOutline,
                optionalLuma(fields, "empty_lines_below_luma", where),
                art,
                tiers(fields.get("tiers"), where + ": tiers"));
    }

    /** A brightness from 0 to 255 under {@code key}; 0 without one. */
    private static int optionalLuma(Map<?, ?> fields, String key, String where) {
        return fields.get(key) == null
                ? 0
                : ManifestYaml.boundedInt(fields.get(key), 0, 255, where + ": " + key);
    }

    private static List<AccessoryTier> tiers(Object section, String where) {
        List<AccessoryTier> tiers = new ArrayList<>();
        int previous = 0;
        List<?> rows = ManifestYaml.list(section, where);
        for (int i = 0; i < rows.size(); i++) {
            String rowWhere = where + ": " + rows.get(i);
            Map<?, ?> row = ManifestYaml.mapping(rows.get(i), rowWhere);
            ManifestYaml.onlyKeys(row, rowWhere, "name", "below", "ramp");
            String name = ManifestYaml.text(row.get("name"), rowWhere + ": name");
            if (name == null || !TIER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(rowWhere + ": a tier needs a lower-case name");
            }
            boolean last = i == rows.size() - 1;
            if (last != (row.get("below") == null)) {
                throw new IllegalArgumentException(rowWhere + ": only the last tier has no below");
            }
            int below = Integer.MAX_VALUE;
            if (!last) {
                if (!(row.get("below") instanceof Integer value) || value <= previous) {
                    throw new IllegalArgumentException(rowWhere + ": below must rise");
                }
                below = value;
                previous = value;
            }
            List<Integer> ramp = ManifestYaml.colours(row.get("ramp"), rowWhere + ": ramp");
            if (ramp.size() < 2) {
                throw new IllegalArgumentException(rowWhere + ": a ramp needs two colours");
            }
            tiers.add(new AccessoryTier(name, below, ramp));
        }
        return tiers;
    }
}
