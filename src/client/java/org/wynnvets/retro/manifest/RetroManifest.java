package org.wynnvets.retro.manifest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.wynnvets.logging.VetsLogger;

/**
 * The retro rules as {@code retro.yml} spells them out, grouped by what each family of sections
 * decides.
 * {@link org.wynnvets.retro.rules.TextureRules TextureRules} and
 * {@link org.wynnvets.retro.rules.ModelRules ModelRules} make the decisions with them.
 *
 * <p>The file is standard YAML, read with SnakeYAML's safe loader, in a single document: a
 * mapping from section name to the section's entries, in which a repeated section, or a repeated
 * key inside one, is a parse error. As in {@link org.wynnvets.items.ItemDefinitions
 * ItemDefinitions}, a section name this class does not know is ignored (with a debug line), and so
 * is an empty or null pattern. Anything else malformed fails the whole parse: an entry of the
 * wrong shape, a row key its section does not have, a bad regular expression or colour, a
 * replacement without its namespace or with a {@code $n} its pattern has no group for, and what
 * each family's own documentation lists. The real-file test thereby turns a bad edit into a failed
 * build rather than a rule that silently never applies. Every pattern is compiled here, once, and
 * must match the whole id. The file's header and each section's comment describe the format.
 *
 * <p>The record and everything in it are immutable, so one manifest can be shared across threads.
 *
 * @param parts what each part of retro the player can turn off owns
 * @param textures which textures are replaced, and from what
 * @param models which item models and equipment assets are replaced, and with what
 * @param assets the files the pack makes of its own
 * @param stacks how a single stack is drawn
 * @param screens what is drawn over Wynncraft's screens
 * @param wynntilsConfig {@code wynntils_config}: the Wynntils settings changed while retro is on
 *     and not stopped, in file order
 */
public record RetroManifest(
        PartSections parts,
        TextureSections textures,
        ModelSections models,
        AssetSections assets,
        StackSections stacks,
        ScreenSections screens,
        List<WynntilsSetting> wynntilsConfig) {

    /**
     * The {@code ingredients} value that leaves Wynncraft's model in place, the {@code equipment}
     * value that leaves Wynncraft's asset in place, and the {@code container_titles} value that
     * leaves Wynncraft's label in place.
     */
    public static final String KEEP = "keep";

    /** The sections of {@code retro.yml}; any other is ignored. */
    // Package-private for unit tests. See ShippedManifestTest.
    static final Set<String> SECTIONS = sections();

    private static Set<String> sections() {
        Set<String> names = new HashSet<>(PartSections.NAMES);
        names.addAll(TextureSections.NAMES);
        names.addAll(ModelSections.NAMES);
        names.addAll(ItemTables.NAMES);
        names.addAll(AssetSections.NAMES);
        names.addAll(StackSections.NAMES);
        names.addAll(ScreenSections.NAMES);
        names.add(WynntilsSetting.SECTION);
        return Set.copyOf(names);
    }

    /** Copies the list. */
    public RetroManifest {
        wynntilsConfig = List.copyOf(wynntilsConfig);
    }

    /**
     * Reads the file from the classpath, where vetsmod's jar carries it beside
     * {@code definitions.yml}.
     *
     * @return the parsed manifest
     * @throws IOException when the file is missing or cannot be read
     * @throws RuntimeException when the file is malformed: SnakeYAML's {@code YAMLException} for
     *     broken YAML or a repeated key, an {@link IllegalArgumentException} for a malformed
     *     entry
     */
    public static RetroManifest load() throws IOException {
        try (InputStream file = open(ManifestYaml.FILE)) {
            RetroManifest manifest = parse(file);
            Map<String, Integer> counts = new LinkedHashMap<>();
            manifest.parts.addCounts(counts);
            manifest.textures.addCounts(counts);
            manifest.models.addCounts(counts);
            manifest.assets.addCounts(counts);
            manifest.stacks.addCounts(counts);
            manifest.screens.addCounts(counts);
            counts.put(WynntilsSetting.SECTION, manifest.wynntilsConfig.size());
            VetsLogger.debug("Loaded the retro manifest: {}", counts);
            return manifest;
        }
    }

    /**
     * A manifest with no rows, which draws nothing.
     *
     * @return the manifest
     */
    public static RetroManifest empty() {
        return parse(InputStream.nullInputStream());
    }

    private static InputStream open(String file) throws IOException {
        InputStream in = RetroManifest.class.getResourceAsStream("/" + file);
        if (in == null) throw new FileNotFoundException(file + " not found in resources");
        return in;
    }

    /**
     * Parses the file's contents.
     *
     * @param file {@code retro.yml}
     * @return the manifest; an empty input has no rows
     */
    // Package-private for unit tests. See ManifestFixtures.
    static RetroManifest parse(InputStream file) {
        Map<?, ?> rules = ManifestYaml.sections(file, ManifestYaml.FILE, SECTIONS);
        ItemTables tables = ItemTables.parse(rules);
        return new RetroManifest(
                PartSections.parse(rules),
                TextureSections.parse(rules),
                ModelSections.parse(rules, tables),
                AssetSections.parse(rules, tables),
                StackSections.parse(rules),
                ScreenSections.parse(rules),
                WynntilsSetting.parse(rules.get(WynntilsSetting.SECTION)));
    }
}
