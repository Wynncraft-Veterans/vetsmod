package org.wynnvets.retro.manifest;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The section that names the {@linkplain RetroPart parts} of retro and says what each owns.
 *
 * <p>Beyond the rules every section keeps, these fail the parse: a name that is no
 * {@link RetroPart}, and a row with no {@code about}.
 *
 * @param parts {@code parts}: each part's row, in file order
 */
public record PartSections(Map<RetroPart, Part> parts) {

    private static final String PARTS = "parts";

    static final Set<String> NAMES = Set.of(PARTS);

    /** Copies the rows, keeping their order. */
    public PartSections {
        parts = ManifestYaml.frozen(parts);
    }

    /**
     * A {@code parts} row.
     *
     * @param part the part
     * @param about what {@code /wv retro parts} says of it
     * @param textures the texture ids it owns, each a whole-id pattern
     * @param models the model ids it owns, each a whole-id pattern
     * @param wynntils the names of the Wynntils features whose {@code wynntils_config} settings it
     *     owns
     */
    public record Part(
            RetroPart part,
            String about,
            List<Pattern> textures,
            List<Pattern> models,
            Set<String> wynntils) {

        /** Copies the lists and the set. */
        public Part {
            textures = List.copyOf(textures);
            models = List.copyOf(models);
            wynntils = Set.copyOf(wynntils);
        }
    }

    static PartSections parse(Map<?, ?> sections) {
        Map<RetroPart, Part> parts = new LinkedHashMap<>();
        ManifestYaml.textRows(
                        sections.get(PARTS), ManifestYaml.inTextures(PARTS), PartSections::row)
                .values()
                .forEach(row -> parts.put(row.part(), row));
        return new PartSections(parts);
    }

    void addCounts(Map<String, Integer> counts) {
        counts.put(PARTS, parts.size());
    }

    private static Part row(String name, Object value, String where) {
        RetroPart part = RetroPart.fromYaml(name);
        if (part == null) throw new IllegalArgumentException(where + ": no such part");
        Map<?, ?> fields = ManifestYaml.mapping(value, where);
        ManifestYaml.onlyKeys(fields, where, "about", "textures", "models", "wynntils");
        return new Part(
                part,
                ManifestYaml.requiredText(fields.get("about"), where, "no about"),
                ManifestYaml.patterns(fields.get("textures"), where + ": textures"),
                ManifestYaml.patterns(fields.get("models"), where + ": models"),
                features(fields.get("wynntils"), where + ": wynntils"));
    }

    private static Set<String> features(Object value, String where) {
        Set<String> names = new HashSet<>();
        for (Object entry : ManifestYaml.list(value, where)) {
            names.add(ManifestYaml.requiredText(entry, where, "an empty feature name"));
        }
        return names;
    }
}
