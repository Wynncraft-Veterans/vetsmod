package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.onClasspath;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Tests for the shipped {@code retro_textures.yml} and {@code retro_ingredients.yml} as
 * files: that they load, that they hold exactly the known sections, and that the client jar's
 * assets are on the test classpath. See {@link ShippedFiles ShippedFiles} for what the shipped
 * suites read.
 */
class ShippedManifestTest {

    private static Set<Object> sectionNames(String resource) throws IOException {
        try (InputStream in = ShippedManifestTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Object document =
                    new Yaml(new SafeConstructor(new LoaderOptions()))
                            .load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return Set.copyOf(((Map<?, ?>) document).keySet());
        }
    }

    @Test
    void theRealFilesLoadWithoutError() throws IOException {
        // load() throws on a malformed file, and the generator catches that and serves an empty
        // pack, so a broken file would only show in game as no retro textures. Loading it here,
        // with nothing catching, turns a bad edit (a repeated section included) into a failed
        // build.
        RetroManifest manifest = RetroManifest.load();

        assertTrue(manifest.textures().sameName().size() > 0);
        assertTrue(manifest.models().itemModels().size() > 0);
        assertTrue(manifest.models().ingredients().size() > 0);
    }

    @Test
    void theRealFilesHaveExactlyTheKnownSections() throws IOException {
        // The parser ignores an unknown section, so a misspelt one would silently lose its rows.
        assertEquals(RetroManifest.TEXTURE_SECTIONS, sectionNames("/retro_textures.yml"));
        assertEquals(RetroManifest.INGREDIENT_SECTIONS, sectionNames("/retro_ingredients.yml"));
    }

    @Test
    void theClientJarsAssetsAreOnTheTestClasspath() {
        // The existence checks below would otherwise fail for the wrong reason, or, written
        // as assumptions, pass without checking anything.
        assertTrue(textureInClientJar("minecraft:block/stone"));
        assertTrue(onClasspath("minecraft:item/stick", "models", ".json"));
    }
}
