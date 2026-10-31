package org.wynnvets.retro.manifest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.wynnvets.retro.manifest.ScreenSections.SlotFill;

/**
 * Parses rule-file fixtures for suites in any package, since the parser is package-private, and
 * reads rows back out of a parsed manifest for the suites of this package.
 */
public final class ManifestFixtures {

    /** The recipe a fixture with {@code potions} needs, to put before its rows. */
    public static final String POTION_RECIPE =
            "recipes:\n  potion_overlay: {art: 'minecraft:item/potion_overlay'}\n";

    private ManifestFixtures() {}

    /**
     * Parses both files' fixtures.
     *
     * @param textures the {@code retro_textures.yml} fixture
     * @param ingredients the {@code retro_ingredients.yml} fixture
     * @return the manifest
     */
    public static RetroManifest parse(String textures, String ingredients) {
        return RetroManifest.parse(stream(textures), stream(ingredients));
    }

    /**
     * Parses a {@code retro_textures.yml} fixture, with an empty ingredients file.
     *
     * @param textures the fixture
     * @return the manifest
     */
    public static RetroManifest parseTextures(String textures) {
        return parse(textures, "");
    }

    private static InputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    /** Each {@code source_copies} row's source, in file order. */
    static Map<String, String> copySources(RetroManifest manifest) {
        Map<String, String> rows = new LinkedHashMap<>();
        manifest.assets().sourceCopies().forEach((copy, row) -> rows.put(copy, row.source()));
        return rows;
    }

    /** Each source copy's colour, for the copies that take one. */
    static Map<String, Integer> copyTints(RetroManifest manifest) {
        Map<String, Integer> rows = new LinkedHashMap<>();
        manifest.assets()
                .sourceCopies()
                .forEach(
                        (copy, row) -> {
                            if (row.colour() != null) rows.put(copy, row.colour());
                        });
        return rows;
    }

    /** The colour of each source copy's recipe that comes out as its own, for those with one. */
    static Map<String, Integer> copyFroms(RetroManifest manifest) {
        Map<String, Integer> rows = new LinkedHashMap<>();
        manifest.assets()
                .sourceCopies()
                .forEach(
                        (copy, row) -> {
                            if (row.recipe().kind() != Recipe.Kind.MULTIPLY) {
                                rows.put(copy, row.recipe().from());
                            }
                        });
        return rows;
    }

    /** The source copies whose recipe has a {@code hue_of}. */
    static Set<String> copyHueOnly(RetroManifest manifest) {
        Set<String> rows = new HashSet<>();
        manifest.assets()
                .sourceCopies()
                .forEach(
                        (copy, row) -> {
                            if (row.recipe().kind() == Recipe.Kind.HUE_ONLY) rows.add(copy);
                        });
        return rows;
    }

    /** A container row with its patterns left out, so rows compare by their slots. */
    static SlotFill withoutPatterns(SlotFill fill) {
        return fill instanceof SlotFill.ContainerSlots row
                ? new SlotFill.ContainerSlots(
                        null,
                        null,
                        row.rows(),
                        row.cols(),
                        row.slots(),
                        row.outsideBounds(),
                        row.all(),
                        row.except(),
                        null,
                        row.fallback())
                : fill;
    }
}
