package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyFroms;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyHueOnly;
import static org.wynnvets.retro.manifest.ManifestFixtures.copySources;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyTints;
import static org.wynnvets.retro.manifest.ManifestFixtures.parseTextures;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.colors.Recolours;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.manifest.AssetSections.Fill;
import org.wynnvets.retro.manifest.AssetSections.Frame;
import org.wynnvets.retro.manifest.AssetSections.SourceCopy;

/**
 * Tests for the parser's reading of {@link AssetSections}: the image edits, copies, recipes,
 * blank textures, Wynntils textures and language, on small fixtures.
 */
class AssetSectionsTest {

    @Test
    void imageEditsReadTheirFillsInOrder() {
        RetroManifest manifest =
                parseTextures(
                        """
                        image_edits:
                          'minecraft:gui/container/inventory':
                            - fill: [97, 17, 74, 36]
                              sample: [172, 17]
                              palette: ['#C6C6C6', '#8B8B8B']
                            - {fill: [76, 61, 18, 18], sample: [0, 0], palette: ['#373737']}
                        """);

        List<Fill> fills = manifest.assets().imageEdits().get("minecraft:gui/container/inventory");
        assertEquals(
                List.of(
                        new Fill(new Rect(97, 17, 74, 36), 172, 17, Set.of(0xC6C6C6, 0x8B8B8B)),
                        new Fill(new Rect(76, 61, 18, 18), 0, 0, Set.of(0x373737))),
                fills);
    }

    @Test
    void aMalformedImageEditFailsTheParse() {
        String good = "{fill: [1, 1, 2, 2], sample: [0, 0], palette: ['#C6C6C6']}";
        for (String edit :
                List.of(
                        good.replace("[1, 1, 2, 2]", "[1, 1, 2]"),
                        good.replace("[1, 1, 2, 2]", "[1, -1, 2, 2]"),
                        good.replace("[1, 1, 2, 2]", "[1, 1, 0, 2]"),
                        good.replace("[1, 1, 2, 2]", "[1, 1, 2, 2.5]"),
                        good.replace("[0, 0]", "[0]"),
                        good.replace("['#C6C6C6']", "[]"),
                        good.replace(", palette: ['#C6C6C6']", ""))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("image_edits:\n  'minecraft:gui/x':\n    - " + edit + "\n"),
                    edit);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("image_edits:\n  'minecraft:gui/.*':\n    - " + good + "\n"),
                "a pattern, not an id");
        assertEquals(
                1,
                parseTextures("image_edits:\n  'minecraft:gui/x':\n    - " + good + "\n")
                        .assets()
                        .imageEdits()
                        .size());
    }

    @Test
    void wynncraftCopiesWriteOnlyIntoVetsmod() {
        RetroManifest manifest =
                parseTextures(
                        """
                        wynncraft_copies:
                          'vetsmod:gui/sprites/tooltip/legacy_frame': 'minecraft:gui/sprites/tooltip/unique_frame'
                        """);
        assertEquals(
                Map.of(
                        "vetsmod:gui/sprites/tooltip/legacy_frame",
                        "minecraft:gui/sprites/tooltip/unique_frame"),
                manifest.assets().wynncraftCopies());

        // Anywhere else, a copy would hide a texture of Minecraft's or Wynncraft's own.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                wynncraft_copies:
                                  'minecraft:gui/sprites/tooltip/frame': 'minecraft:gui/sprites/tooltip/unique_frame'
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                wynncraft_copies:
                                  'vetsmod:gui/x': 'minecraft:gui/(a|b)'
                                """),
                "the original is an id, not a pattern");
    }

    @Test
    void aRecipeHasAHueOfOrAScaleFromNotBoth() {
        RetroManifest manifest =
                parseTextures(
                        """
                        recipes:
                          a: {art: 'minecraft:item/a', hue_of: '#102030'}
                          b: {art: 'minecraft:item/b', scale_from: '#102030'}
                        source_copies:
                          'vetsmod:item/retro/a': {a: '#48B518'}
                          'vetsmod:item/retro/b': {b: '#48B518'}
                        """);

        assertEquals(Set.of("vetsmod:item/retro/a"), copyHueOnly(manifest));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                recipes:
                                  a: {art: 'minecraft:item/a', hue_of: '#102030', scale_from: '#102030'}
                                """));
    }

    @Test
    void hueOfRefusesAColourWithoutOneStrongestChannel() {
        for (String tied : List.of("#808080", "#808000", "#008080", "#800080", "#000000")) {
            IllegalArgumentException refused =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    parseTextures(
                                            "recipes:\n  a: {art: 'minecraft:item/a', hue_of: '"
                                                    + tied
                                                    + "'}\n"),
                            tied);
            assertTrue(refused.getMessage().contains("recipes: 'a'"), refused.getMessage());
            assertTrue(refused.getMessage().contains("strongest"), refused.getMessage());
        }
    }

    @Test
    void aSourceCopyPicksItsRecolourByItsRecipe() {
        int pixel = 0xFF402010;
        String art = "minecraft:item/a";

        Recipe scale = new Recipe(art, Recipe.Kind.SCALE, 0x102030);
        Recipe hue = new Recipe(art, Recipe.Kind.HUE_ONLY, 0x102030);
        assertNull(SourceCopy.of(art).recolour(), "a copy of the file");
        assertEquals(art, SourceCopy.of(art).source());
        assertEquals(
                Recolours.tint(0x48B518).applyAsInt(pixel),
                new SourceCopy(Recipe.multiply(art), 0x48B518).recolour().applyAsInt(pixel));
        assertEquals(
                Recolours.scale(0x102030, 0x48B518).applyAsInt(pixel),
                new SourceCopy(scale, 0x48B518).recolour().applyAsInt(pixel));
        assertEquals(
                Recolours.hueOnly(0x102030, 0x48B518).applyAsInt(0xFF102030),
                new SourceCopy(hue, 0x48B518).recolour().applyAsInt(0xFF102030));
    }

    @Test
    void aScaleOrMultiplyRecipeLeavesTheArtsOutlineAndAHueRecipePassesOverGrey() {
        String art = "minecraft:item/a";
        int outline = 0xFF353535;
        int lighterGrey = 0xFF808080;
        int colour = 0x6CB0F0;
        int main = 0xD8D8D8;

        for (Recipe recipe :
                List.of(
                        Recipe.multiply(art),
                        new Recipe(art, Recipe.Kind.SCALE, main),
                        new Recipe(art, Recipe.Kind.HUE_ONLY, 0x345FDA))) {
            assertEquals(outline, recipe.recolour(colour).applyAsInt(outline), recipe.toString());
        }
        assertEquals(
                Recolours.tint(colour).applyAsInt(lighterGrey),
                Recipe.multiply(art).recolour(colour).applyAsInt(lighterGrey));
        assertEquals(
                Recolours.scale(main, colour).applyAsInt(lighterGrey),
                new Recipe(art, Recipe.Kind.SCALE, main).recolour(colour).applyAsInt(lighterGrey));
    }

    @Test
    void blankTexturesAreIdsAndAPatternFailsTheParse() {
        assertEquals(
                List.of("minecraft:font/x"),
                parseTextures("blank_textures:\n  - 'minecraft:font/x'\n")
                        .assets()
                        .blankTextures());
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("blank_textures:\n  - 'minecraft:font/.*'\n"));
    }

    @Test
    void aSourceCopyCanCarryATint() {
        RetroManifest manifest =
                parseTextures(
                        """
                        recipes:
                          g: {art: 'minecraft:block/g'}
                        source_copies:
                          'vetsmod:block/retro/g': {g: '#48B518'}
                          'vetsmod:block/retro/p': 'minecraft:block/p'
                        """);
        assertEquals(
                Map.of(
                        "vetsmod:block/retro/g", "minecraft:block/g",
                        "vetsmod:block/retro/p", "minecraft:block/p"),
                copySources(manifest));
        assertEquals(Map.of("vetsmod:block/retro/g", 0x48B518), copyTints(manifest));
        assertTrue(copyFroms(manifest).isEmpty());
        assertEquals(
                Map.of("vetsmod:block/retro/g", 0x102030),
                copyFroms(
                        parseTextures(
                                """
                                recipes:
                                  g: {art: 'minecraft:block/g', scale_from: '#102030'}
                                source_copies:
                                  'vetsmod:block/retro/g': {g: '#48B518'}
                                """)));

        // A mapping names one recipe it can find, and a colour is quoted '#RRGGBB'.
        String recipe = "recipes:\n  g: {art: 'minecraft:block/g'}\n";
        for (String row :
                List.of(
                        "{source: 'minecraft:block/g'}",
                        "{tint: '#48B518'}",
                        "{g: '#48B518', tint: '#48B518'}",
                        "{g: 'green'}",
                        "{}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            parseTextures(
                                    recipe
                                            + "source_copies:\n  'vetsmod:block/retro/g': "
                                            + row
                                            + "\n"),
                    row);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                "recipes:\n  g: {art: 'minecraft:block/g', scale_from: 'green'}\n"));
    }

    @Test
    void sourceCopiesWriteOnlyIntoVetsmod() {
        RetroManifest manifest =
                parseTextures(
                        """
                        source_copies:
                          'vetsmod:block/retro/b': 'minecraft:block/b'
                          'vetsmod:block/retro/a': 'minecraft:entity/chest/normal'
                        """);
        assertEquals(
                List.of(
                        Map.entry("vetsmod:block/retro/b", "minecraft:block/b"),
                        Map.entry("vetsmod:block/retro/a", "minecraft:entity/chest/normal")),
                List.copyOf(copySources(manifest).entrySet()),
                "in file order");

        // Anywhere else, a copy would hide a texture of Minecraft's or Wynncraft's own.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                source_copies:
                                  'minecraft:block/b': 'minecraft:block/a'
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                source_copies:
                                  'vetsmod:block/x': 'minecraft:block/(a|b)'
                                """),
                "the original is an id, not a pattern");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                source_copies:
                                  'vetsmod:block/x': ''
                                """),
                "nothing to copy");
    }

    @Test
    void wynntilsTexturesAreBlankOrFramesAndWriteOnlyIntoWynntils() {
        RetroManifest manifest =
                parseTextures(
                        """
                        wynntils_textures:
                          'wynntils:ui_components/emerald_count_background': blank
                          'wynntils:ui_components/quick_jump_button':
                            - {source: 'minecraft:gui/sprites/widget/button', border: 3}
                            - source: 'minecraft:gui/demo_background'
                              region: [0, 0, 248, 166]
                              border: 4
                        """);

        assertEquals(
                List.of(),
                manifest.assets()
                        .wynntilsTextures()
                        .get("wynntils:ui_components/emerald_count_background"));
        assertEquals(
                List.of(
                        new Frame("minecraft:gui/sprites/widget/button", null, 3),
                        new Frame("minecraft:gui/demo_background", new Rect(0, 0, 248, 166), 4)),
                manifest.assets()
                        .wynntilsTextures()
                        .get("wynntils:ui_components/quick_jump_button"));

        for (String row :
                List.of(
                        "'minecraft:gui/x': blank",
                        "'wynntils:x': []",
                        "'wynntils:x': transparent",
                        "'wynntils:x': [{border: 3}]",
                        "'wynntils:x': [{source: 'minecraft:gui/x'}]",
                        "'wynntils:x': [{source: 'minecraft:gui/x', border: -1}]")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("wynntils_textures:\n  " + row + "\n"),
                    row);
        }
    }

    @Test
    void langReadsItsLocalesAndStringsInOrder() {
        RetroManifest manifest =
                parseTextures(
                        """
                        lang:
                          locales: ['en_us', 'en_gb']
                          strings:
                            'menu.game': 'Game Menu'
                            'container.inventory': 'Inventory'
                        """);

        assertEquals(List.of("en_us", "en_gb"), manifest.assets().lang().locales());
        assertEquals(
                List.of("menu.game", "container.inventory"),
                List.copyOf(manifest.assets().lang().strings().keySet()));

        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("lang:\n  locales: ['en US']\n"),
                "not a file name");
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("lang:\n  strings: {'menu.game': ''}\n"),
                "an empty string");
    }
}
