package org.wynnvets.retro.manifest;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.copySources;
import static org.wynnvets.retro.manifest.ManifestFixtures.parse;
import static org.wynnvets.retro.manifest.ManifestFixtures.parseTextures;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.AssetSections.SourceCopy;
import org.wynnvets.retro.manifest.ScreenSections.SlotFill;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Tests for {@link RetroManifest}'s parser on small fixtures: what every section's mapping
 * must satisfy (one document, no repeated keys, rows in file order, patterns that compile and are
 * not empty, entries of the right shape), the empty manifest and its immutability, and the
 * Wynntils settings. Each family's own rows are {@link TextureSectionsTest TextureSectionsTest}'s,
 * {@link ModelSectionsTest ModelSectionsTest}'s, {@link AssetSectionsTest AssetSectionsTest}'s,
 * {@link StackSectionsTest StackSectionsTest}'s and
 * {@link ScreenSectionsTest ScreenSectionsTest}'s.
 */
class ManifestParserTest {

    @Test
    void aRepeatedSectionFailsTheParse() {
        // In one mapping a repeated key would otherwise silently replace the earlier section.
        assertThrows(
                YAMLException.class,
                () ->
                        parseTextures(
                                """
                                same_name:
                                  - 'minecraft:block/.*'
                                same_name:
                                  - 'minecraft:item/.*'
                                """));
    }

    @Test
    void aRepeatedRowFailsTheParse() {
        // The duplicate-key check covers the mappings inside a section too.
        assertThrows(
                YAMLException.class,
                () ->
                        parseTextures(
                                """
                                aliases:
                                  'minecraft:block/a': 'minecraft:block/b'
                                  'minecraft:block/a': 'minecraft:block/c'
                                """));
        assertThrows(
                YAMLException.class,
                () ->
                        parse(
                                "",
                                """
                                ingredients:
                                  gold_nugget: 'minecraft:item/gold_nugget'
                                  gold_nugget: keep
                                """));
    }

    @Test
    void aSecondDocumentFailsTheParse() {
        // Each file is one document; a "---" would otherwise start a second set of sections.
        assertThrows(
                YAMLException.class,
                () ->
                        parseTextures(
                                """
                                same_name:
                                  - 'minecraft:block/.*'
                                ---
                                same_name:
                                  - 'minecraft:item/.*'
                                """));
    }

    @Test
    void anUnknownSectionIsIgnored() {
        // As in definitions.yml, a typo in a section name loses that section and nothing else.
        RetroManifest manifest =
                parseTextures(
                        """
                        same_nmae:
                          - 'minecraft:item/.*'
                        same_name:
                          - 'minecraft:block/.*'
                        """);

        assertEquals(1, manifest.textures().sameName().size());
        assertEquals("minecraft:block/.*", manifest.textures().sameName().get(0).pattern());
    }

    @Test
    void emptyFilesGiveAnEmptyManifest() {
        RetroManifest manifest = parse("", "");

        assertTrue(manifest.textures().sources().isEmpty());
        assertTrue(manifest.textures().keepWynncraft().isEmpty());
        assertTrue(manifest.textures().tints().isEmpty());
        assertTrue(manifest.textures().aliases().isEmpty());
        assertTrue(manifest.textures().sameName().isEmpty());
        assertTrue(manifest.textures().layerGroups().isEmpty());
        assertTrue(manifest.models().itemModels().isEmpty());
        assertTrue(manifest.models().potions().isEmpty());
        assertTrue(manifest.models().equipment().isEmpty());
        assertTrue(manifest.assets().recipes().isEmpty());
        assertTrue(manifest.assets().imageEdits().isEmpty());
        assertTrue(manifest.assets().wynncraftCopies().isEmpty());
        assertTrue(copySources(manifest).isEmpty());
        assertTrue(manifest.assets().wynntilsTextures().isEmpty());
        assertTrue(manifest.stacks().gearBoxIcons().isEmpty());
        assertTrue(manifest.assets().lang().locales().isEmpty());
        assertTrue(manifest.assets().lang().strings().isEmpty());
        assertTrue(manifest.models().ingredients().isEmpty());
    }

    @Test
    void anEmptyPatternIsIgnored() {
        // Under matches() an empty pattern matches only the empty id, so it could never apply.
        RetroManifest manifest =
                parse(
                        """
                        same_name:
                          - ''
                          -
                          - 'minecraft:block/.*'
                        aliases:
                          '': 'minecraft:block/stone'
                        """,
                        """
                        ingredients:
                          '': 'minecraft:item/stick'
                        """);

        assertEquals(
                1, manifest.textures().sameName().size(), "the section's other entries still load");
        assertTrue(manifest.textures().aliases().isEmpty());
        assertTrue(manifest.models().ingredients().isEmpty());
    }

    @Test
    void aBadOrUnquotedColourFailsTheParse() {
        for (String tint : List.of("'A06540'", "'#A0654'", "'#A06540FF'", "red", "#A06540")) {
            // Unquoted, '#A06540' is a YAML comment, which leaves the key empty.
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            parseTextures(
                                    """
                                    tints:
                                      'minecraft:block/x':
                                        tint: %s
                                    """
                                            .formatted(tint)),
                    tint);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("potions:\n  x: {colour: '00AA00'}\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("potions:\n  x: {colour: #00AA00\n  }\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("potions:\n  x: empty\n"),
                "every potion row is a liquid's colour");
    }

    @Test
    void theEmptyManifestHasNoRows() {
        RetroManifest empty = RetroManifest.empty();

        assertTrue(empty.textures().sources().isEmpty());
        assertTrue(empty.models().ingredients().isEmpty());
        assertTrue(empty.assets().lang().strings().isEmpty());
        assertTrue(empty.stacks().accessoryTiers().tiers().isEmpty());
        assertTrue(empty.screens().slotFills().isEmpty());
        assertTrue(empty.wynntilsConfig().isEmpty());
    }

    @Test
    void aRowKeyItsSectionDoesNotHaveFailsTheParse() {
        for (String row :
                List.of(
                        "tints:\n  'minecraft:block/a': {tint: auto, strenght: 0.7}\n",
                        "item_models:\n  'minecraft:item/a': {model: 'minecraft:item/b', note: x}\n",
                        "image_edits:\n  'minecraft:gui/a': [{fill: [0, 0, 1, 1], sample: [0, 0],"
                                + " palette: ['#000000'], colour: '#000000'}]\n",
                        "recipes:\n  a: {art: 'minecraft:item/a', huo_of: '#102030'}\n",
                        "potions:\n  a: {colour: '#102030', avomood: b}\n",
                        "content_book_activities:\n  models: 'minecraft:a'\n  activity: {}\n",
                        "content_book_activities:\n  models: 'minecraft:a'\n  activities:\n"
                                + "    a: 'minecraft:b'\n  states:\n    s: {colour: '#102030', from: 1}\n",
                        "wynntils_textures:\n  'wynntils:a': [{source: 'minecraft:gui/a',"
                                + " border: 1, regoin: [0, 0, 1, 1]}]\n",
                        "lang:\n  locales: [en_us]\n  string: {}\n",
                        "stack_models:\n  - {item: 'minecraft:a', name: 'b', model: 'minecraft:c',"
                                + " extra: 'd'}\n",
                        "outlined_icons:\n  - {outline: {pixels: 1, colour: '#000000'},"
                                + " scale: 0.5, art: {}, scales: 1}\n",
                        "outlined_icons:\n  - {outline: {pixel: 1, colour: '#000000'},"
                                + " scale: 0.5, art: {}}\n",
                        "accessory_tiers:\n  outline_below_luma: 1\n  arts: {}\n",
                        "accessory_tiers:\n  outline: 1\n",
                        "accessory_tiers:\n  tiers:\n    - {name: gold, ramp: ['#000000', '#FFFFFF'],"
                                + " belo: 5}\n",
                        "slot_fills:\n  - {container: 'a', rows: [0], row: [1]}\n",
                        "slot_values:\n  - {container: 'a', name: 'b', lore: '(c)', extra: 'd'}\n")) {
            IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class, () -> parseTextures(row), row);
            assertTrue(refused.getMessage().contains("unknown key"), refused.getMessage());
        }
    }

    @Test
    void wynntilsConfigReadsFeatureSettingAndValueInFileOrder() {
        RetroManifest manifest =
                parseTextures(
                        """
                        wynntils_config:
                          'ItemHighlight':
                            'highlightTexture': 'boxTransparent'
                            'hotbarHighlightEnabled': 'false'
                          'CustomPlayerListOverlay':
                            'userEnabled': 'false'
                        """);

        assertEquals(
                List.of(
                        new WynntilsSetting("ItemHighlight", "highlightTexture", "boxTransparent"),
                        new WynntilsSetting("ItemHighlight", "hotbarHighlightEnabled", "false"),
                        new WynntilsSetting("CustomPlayerListOverlay", "userEnabled", "false")),
                manifest.wynntilsConfig());

        for (String entry :
                List.of(
                        "'Item Highlight':\n    'a': 'b'",
                        "'ItemHighlight':\n    'a b': 'c'",
                        "'ItemHighlight': {}",
                        "'ItemHighlight':\n    'userEnabled': false",
                        "'ItemHighlight':\n    'userEnabled': ''")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("wynntils_config:\n  " + entry + "\n"),
                    entry);
        }
    }

    @Test
    void theManifestIsImmutable() {
        RetroManifest manifest =
                parseTextures(
                        """
                        sources:
                          '.*': [vanilla]
                        same_name:
                          - 'minecraft:block/.*'
                        """);

        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.textures().sameName().add(Pattern.compile("x")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.textures().sources().get(0).order().add(RetroSource.PROGRAMMER_ART));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        manifest.textures()
                                .tints()
                                .add(
                                        new TextureSections.Tint(
                                                Pattern.compile("x"),
                                                null,
                                                new TintFactor.Auto(),
                                                1,
                                                1)));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        manifest.models()
                                .equipment()
                                .add(
                                        new ModelSections.Equipment(
                                                Pattern.compile("x"), RetroManifest.KEEP)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.models().ingredients().put("a", "b"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.assets().imageEdits().put("a", List.of()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.assets().sourceCopies().put("a", SourceCopy.of("b")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.stacks().gearBoxIcons().put("a", "b"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.assets().lang().strings().put("a", "b"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.screens().slotFills().add(new SlotFill.PlayerSlots()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.wynntilsConfig().add(new WynntilsSetting("a", "b", "c")));
    }

    @Test
    void aGroupThePatternLacksFailsTheParse() {
        // Matcher.appendReplacement would throw on it at decision time, inside a reload.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                aliases:
                                  'minecraft:block/a_(\\d)': 'minecraft:block/$2'
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                item_models:
                                  'minecraft:item/wynn/(\\w+)':
                                    texture: 'minecraft:item/$2'
                                    parent: generated
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                aliases:
                                  'minecraft:block/(?<n>a)': 'minecraft:block/${n}'
                                """),
                "a named group is not supported");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                aliases:
                                  'minecraft:block/a': 'minecraft:block/a\\'
                                """),
                "a trailing backslash escapes nothing");

        RetroManifest manifest =
                parseTextures(
                        """
                        aliases:
                          'minecraft:block/(a)_(\\d)': 'minecraft:block/$2$1$0'
                        """);
        assertEquals(
                "minecraft:block/$2$1$0",
                manifest.textures().aliases().get(0).target(),
                "$0 is fine");
    }

    @Test
    void aReplacementWithoutItsNamespaceFailsTheParse() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                aliases:
                                  'minecraft:block/a': 'block/stone'
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                aliases:
                                  '(minecraft):block/a': '$1:block/stone'
                                """),
                "the namespace must be literal");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                item_models:
                                  'minecraft:item/wynn/x': {texture: 'item/stick', parent: handheld}
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                "",
                                """
                                ingredients:
                                  gold_nugget: 'item/gold_nugget'
                                """));

        RetroManifest manifest =
                parse(
                        "",
                        """
                        ingredients:
                          gold_nugget: 'minecraft:item/gold_nugget'
                          ram_horn: keep
                        """);
        assertEquals(
                Map.of("gold_nugget", "minecraft:item/gold_nugget", "ram_horn", RetroManifest.KEEP),
                manifest.models().ingredients());
    }

    @Test
    void aBadRegexFailsTheParse() {
        assertThrows(
                PatternSyntaxException.class,
                () ->
                        parseTextures(
                                """
                                keep_wynncraft:
                                  - 'minecraft:block/(tripwire'
                                """));
    }

    @Test
    void anEntryOrSectionOfTheWrongShapeFailsTheParse() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("same_name:\n  - 1\n"),
                "a pattern YAML reads as a number");
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("same_name:\n  'minecraft:block/.*': yes\n"),
                "a list section written as a mapping");
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("aliases:\n  - 'minecraft:block/a'\n"),
                "a mapping section written as a list");
        assertThrows(
                IllegalArgumentException.class,
                () -> parse("", "ingredients:\n  1234: 'minecraft:item/stick'\n"),
                "a stem YAML reads as a number");
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("- 'minecraft:block/.*'\n"),
                "a document that is not a mapping of sections");
    }
}
