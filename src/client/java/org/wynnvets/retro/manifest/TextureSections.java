package org.wynnvets.retro.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The sections that decide which textures are replaced, and from what.
 *
 * <p>Beyond the rules every section keeps, these fail the parse: an unknown source, a tint of no
 * known form, a strength outside 0 to 1, a contrast below 0, a {@code tints} row with neither a
 * tint nor a contrast, and a {@code like} with a strength or contrast of its own, or naming a
 * texture that not exactly one {@code tints} row matches, or whose row is a {@code like} too.
 *
 * @param sources {@code sources}: which packs a texture is read from; the first matching row
 *     decides
 * @param keepWynncraft {@code keep_wynncraft}: textures whose Wynncraft copy always stays
 * @param tints {@code tints}: textures replaced by a tinted copy of a source's texture; the first
 *     matching row decides
 * @param aliases {@code aliases}: textures replaced by another texture; the first matching row
 *     decides
 * @param sameName {@code same_name}: textures replaced by a source's texture of the same id
 * @param layerGroups {@code layer_groups}: one pattern per group of layered textures
 */
public record TextureSections(
        List<SourceRow> sources,
        List<Pattern> keepWynncraft,
        List<Tint> tints,
        List<Alias> aliases,
        List<Pattern> sameName,
        List<Pattern> layerGroups) {

    private static final String SOURCES = "sources";
    private static final String KEEP_WYNNCRAFT = "keep_wynncraft";
    private static final String TINTS = "tints";
    private static final String ALIASES = "aliases";
    private static final String SAME_NAME = "same_name";
    private static final String LAYER_GROUPS = "layer_groups";

    static final Set<String> NAMES =
            Set.of(SOURCES, KEEP_WYNNCRAFT, TINTS, ALIASES, SAME_NAME, LAYER_GROUPS);

    /** Copies every list. */
    public TextureSections {
        sources = List.copyOf(sources);
        keepWynncraft = List.copyOf(keepWynncraft);
        tints = List.copyOf(tints);
        aliases = List.copyOf(aliases);
        sameName = List.copyOf(sameName);
        layerGroups = List.copyOf(layerGroups);
    }

    /**
     * A {@code sources} row.
     *
     * @param textures the texture ids the row covers
     * @param order the packs such a texture is read from, most preferred first; may be empty
     */
    public record SourceRow(Pattern textures, List<RetroSource> order) {
        /** Copies the list. */
        public SourceRow {
            order = List.copyOf(order);
        }
    }

    /**
     * An {@code aliases} row.
     *
     * @param textures the texture ids the row replaces
     * @param target the texture id copied in their place, in which {@code $n} stands for the
     *     pattern's group {@code n}
     */
    public record Alias(Pattern textures, String target) {}

    /**
     * A {@code tints} row: the textures it matches are a source's texture with each channel
     * multiplied by a factor, then its contrast changed around the result's mean.
     *
     * @param textures the texture ids the row replaces
     * @param source the texture id the tint starts from, in which {@code $n} stands for the
     *     pattern's group {@code n}; {@code null} for the replaced texture's own id
     * @param factor how the factor of each channel is found; {@link TintFactor.Unchanged} for a row
     *     without a {@code tint}
     * @param strength how much of that factor is applied, from 0 (none: the source as it is) to 1
     *     (all of it); {@code factor = 1 + strength * (ratio - 1)}; 1 for a
     *     {@link TintFactor.Like}, which applies the strength of the row it names
     * @param contrast at least 0: below 1 each channel moves toward the mean, above 1 away from it,
     *     {@code mean + contrast * (channel - mean)}; 1, the default, changes nothing; 1 for a
     *     {@link TintFactor.Like}, which applies the contrast of the row it names
     */
    public record Tint(
            Pattern textures, String source, TintFactor factor, double strength, double contrast) {}

    static TextureSections parse(Map<?, ?> sections) {
        return new TextureSections(
                sources(sections.get(SOURCES)),
                ManifestYaml.patterns(
                        sections.get(KEEP_WYNNCRAFT), ManifestYaml.inTextures(KEEP_WYNNCRAFT)),
                tints(sections.get(TINTS)),
                aliases(sections.get(ALIASES)),
                ManifestYaml.patterns(sections.get(SAME_NAME), ManifestYaml.inTextures(SAME_NAME)),
                ManifestYaml.patterns(
                        sections.get(LAYER_GROUPS), ManifestYaml.inTextures(LAYER_GROUPS)));
    }

    void addCounts(Map<String, Integer> counts) {
        counts.put(SOURCES, sources.size());
        counts.put(KEEP_WYNNCRAFT, keepWynncraft.size());
        counts.put(TINTS, tints.size());
        counts.put(ALIASES, aliases.size());
        counts.put(SAME_NAME, sameName.size());
        counts.put(LAYER_GROUPS, layerGroups.size());
    }

    private static List<SourceRow> sources(Object section) {
        return ManifestYaml.patternRows(
                section,
                ManifestYaml.inTextures(SOURCES),
                (pattern, value, rowWhere) -> {
                    List<RetroSource> order = new ArrayList<>();
                    for (Object name : ManifestYaml.list(value, rowWhere)) {
                        RetroSource source =
                                name instanceof String s ? RetroSource.fromYaml(s) : null;
                        if (source == null) {
                            throw new IllegalArgumentException(
                                    rowWhere + ": unknown source " + name);
                        }
                        order.add(source);
                    }
                    return new SourceRow(pattern, order);
                });
    }

    private static List<Alias> aliases(Object section) {
        return ManifestYaml.patternRows(
                section,
                ManifestYaml.inTextures(ALIASES),
                (pattern, value, rowWhere) ->
                        new Alias(pattern, ManifestYaml.replacement(value, pattern, rowWhere)));
    }

    private static List<Tint> tints(Object section) {
        String where = ManifestYaml.inTextures(TINTS);
        List<Tint> rows = ManifestYaml.patternRows(section, where, TextureSections::tint);
        for (Tint row : rows) {
            if (row.factor() instanceof TintFactor.Like like) {
                checkNamedRow(rows, like.texture(), where + ": '" + row.textures().pattern() + "'");
            }
        }
        return rows;
    }

    private static Tint tint(Pattern pattern, Object value, String rowWhere) {
        Map<?, ?> fields = ManifestYaml.mapping(value, rowWhere);
        ManifestYaml.onlyKeys(fields, rowWhere, "source", "tint", "strength", "contrast");
        if (!fields.containsKey("tint") && !fields.containsKey("contrast")) {
            throw new IllegalArgumentException(rowWhere + ": needs a tint, a contrast or both");
        }
        TintFactor factor =
                fields.containsKey("tint")
                        ? factor(fields.get("tint"), pattern, rowWhere + ": tint")
                        : new TintFactor.Unchanged();
        if (factor instanceof TintFactor.Like
                && (fields.containsKey("strength") || fields.containsKey("contrast"))) {
            throw new IllegalArgumentException(
                    rowWhere
                            + ": a like takes the strength and contrast of the row it names,"
                            + " and has none of its own");
        }
        Object source = fields.get("source");
        return new Tint(
                pattern,
                source == null
                        ? null
                        : ManifestYaml.replacement(source, pattern, rowWhere + ": source"),
                factor,
                strength(fields.get("strength"), rowWhere + ": strength"),
                contrast(fields.get("contrast"), rowWhere + ": contrast"));
    }

    /**
     * Checks that exactly one row of {@code rows} matches {@code texture}, which a {@code like}
     * names, and that it is no {@code like} itself.
     *
     * @throws IllegalArgumentException when not exactly one row matches it, or that row is a
     *     {@code like} too: a like names a row that makes its own change
     */
    private static void checkNamedRow(List<Tint> rows, String texture, String where) {
        List<Tint> matching =
                rows.stream().filter(row -> row.textures().matcher(texture).matches()).toList();
        if (matching.size() != 1) {
            throw new IllegalArgumentException(
                    where
                            + ": tint: like: "
                            + matching.size()
                            + " tints rows match "
                            + texture
                            + ", where a like needs exactly one");
        }
        if (matching.get(0).factor() instanceof TintFactor.Like) {
            throw new IllegalArgumentException(
                    where
                            + ": tint: like: the row matching "
                            + texture
                            + " is a like too; a like names a row that makes its own change");
        }
    }

    /**
     * A {@code tints} row's {@code tint}: a colour, {@code auto}, a match, a relative_to or a
     * like.
     */
    private static TintFactor factor(Object value, Pattern pattern, String where) {
        if (TintFactor.AUTO.equals(value)) return new TintFactor.Auto();
        if (value instanceof String)
            return new TintFactor.Colour(ManifestYaml.colour(value, where));
        // An unquoted '#A06540' is a YAML comment, which leaves the key empty.
        Map<?, ?> fields = value instanceof Map<?, ?> map ? map : Map.of();
        if (fields.size() == 1 && fields.containsKey("match")) {
            return new TintFactor.Match(
                    ManifestYaml.replacement(fields.get("match"), pattern, where + ": match"));
        }
        if (fields.size() == 1 && fields.containsKey("relative_to")) {
            return new TintFactor.RelativeTo(
                    ManifestYaml.replacement(
                            fields.get("relative_to"), pattern, where + ": relative_to"));
        }
        if (fields.size() == 1 && fields.containsKey("like")) {
            return new TintFactor.Like(
                    ManifestYaml.requiredId(
                            fields.get("like"), where + ": like", "expected a texture id"));
        }
        throw new IllegalArgumentException(
                where
                        + ": expected a quoted '#RRGGBB', 'auto', {match: <texture>},"
                        + " {relative_to: <texture>} or {like: <texture>}, not "
                        + value);
    }

    /** A {@code tints} row's {@code strength}, from 0 to 1; 1 when absent. */
    private static double strength(Object value, String where) {
        if (value == null) return 1;
        double strength = value instanceof Number n ? n.doubleValue() : Double.NaN;
        if (!(strength >= 0 && strength <= 1)) {
            throw new IllegalArgumentException(
                    where + ": expected a number from 0 to 1, not " + value);
        }
        return strength;
    }

    /** A {@code tints} row's {@code contrast}, at least 0; 1 when absent. */
    private static double contrast(Object value, String where) {
        if (value == null) return 1;
        double contrast = value instanceof Number n ? n.doubleValue() : Double.NaN;
        if (!(contrast >= 0 && Double.isFinite(contrast))) {
            throw new IllegalArgumentException(
                    where + ": expected a number of at least 0, not " + value);
        }
        return contrast;
    }
}
