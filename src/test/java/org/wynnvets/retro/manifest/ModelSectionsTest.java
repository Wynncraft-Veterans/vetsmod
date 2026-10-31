package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.wynnvets.retro.manifest.ManifestFixtures.parseTextures;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ModelSections.ItemModel;
import org.wynnvets.retro.rules.Atlas;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.ModelStub;

/**
 * Tests for the parser's reading of {@link ModelSections} and {@link ItemTables}: the item
 * models, equipment and potion rows, the tables and the order of a table's rows against the item
 * models', on small fixtures.
 */
class ModelSectionsTest {

    @Test
    void itemModelRowsRefuseKeysTheyDoNotUse() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                item_models:
                                  'minecraft:item/wynn/x':
                                    texture: 'minecraft:item/stick'
                                    parent: handheld
                                    note: spare
                                """));
    }

    @Test
    void aPlainStringItemModelRowIsATextureHeldFlat() {
        RetroManifest manifest =
                parseTextures(
                        "item_models:\n  'minecraft:item/wynn/(\\w+)': 'minecraft:item/$1'\n");

        ItemModel row = manifest.models().itemModels().get(0);
        assertEquals("minecraft:item/$1", row.texture());
        assertEquals(ParentKind.GENERATED, row.parent());
        assertNull(row.model());
        for (String value : List.of("'item/stick'", "'minecraft:item/$2'", "''", "5")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("item_models:\n  'minecraft:item/wynn/(x)': " + value),
                    value);
        }
    }

    @Test
    void itemModelRowsReadAModelInPlaceOfATextureAndParent() {
        RetroManifest manifest =
                parseTextures(
                        """
                        item_models:
                          'minecraft:item/wynn/(\\w+)':
                            model: 'minecraft:item/leather_$1'
                        """);

        ItemModel row = manifest.models().itemModels().get(0);
        assertEquals("minecraft:item/leather_$1", row.model());
        assertNull(row.texture());
        assertNull(row.parent());

        for (String fields :
                List.of(
                        "{model: 'minecraft:item/stick', texture: 'minecraft:item/stick'}",
                        "{model: 'minecraft:item/stick', parent: generated}",
                        "{model: 'item/stick'}",
                        "{model: 'minecraft:item/$2'}",
                        "{model: ''}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("item_models:\n  'minecraft:item/wynn/(x)': " + fields),
                    fields);
        }
    }

    @Test
    void equipmentRowsReadAVanillaAssetOrKeepInFileOrder() {
        RetroManifest manifest =
                parseTextures(
                        """
                        equipment:
                          'minecraft:hidden': keep
                          'minecraft:(pale_)?(iron|gold)': 'minecraft:$2'
                        """);

        List<ModelSections.Equipment> rows = manifest.models().equipment();
        assertEquals(
                List.of("minecraft:hidden", "minecraft:(pale_)?(iron|gold)"),
                rows.stream().map(row -> row.assets().pattern()).toList());
        assertEquals(RetroManifest.KEEP, rows.get(0).target());
        assertEquals("minecraft:$2", rows.get(1).target());

        for (String row :
                List.of(
                        "'minecraft:a': 'iron'",
                        "'minecraft:(a)': 'minecraft:$2'",
                        "'minecraft:a': ''")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("equipment:\n  " + row + "\n"),
                    row);
        }
    }

    @Test
    void potionRowsReadALiquidColourInFileOrder() {
        RetroManifest manifest =
                parseTextures(
                        ManifestFixtures.POTION_RECIPE
                                + """
                                potions:
                                  mana: {colour: '#385DC6'}
                                  dexterity: {colour: '#ffff55', avomod: potion_sp_dexterity}
                                """);

        List<ModelSections.Potion> rows = manifest.models().potions();
        assertEquals(3, rows.size());
        assertEquals("vetsmod:item/retro/potion/mana", rows.get(0).liquid());
        assertEquals("vetsmod:item/retro/potion/dexterity", rows.get(1).liquid());
        Map<String, Integer> colours = ManifestFixtures.copyTints(manifest);
        assertEquals(0x385DC6, colours.get(rows.get(0).liquid()));
        assertEquals(0xFFFF55, colours.get(rows.get(1).liquid()), "either case");
        assertEquals(
                "minecraft:item/wynn/potion/mana_(empty|half|full)",
                rows.get(0).models().pattern());
        assertEquals("avomod2:item/potion_sp_dexterity", rows.get(2).models().pattern());
        assertEquals(
                rows.get(1).liquid(),
                rows.get(2).liquid(),
                "a family's Avomod model takes its colour");
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("potions:\n  mana: {colour: '#385DC6'}\n"),
                "no potion_overlay recipe");
        for (String row : List.of("{colour: '#385DC6', avomod: 'a.*'}", "{avomod: potion_mana}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("potions:\n  mana: " + row + "\n"),
                    row);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures("potions:\n  'mana_(a|b)': {colour: '#385DC6'}\n"),
                "a family is a name, not a pattern");
    }

    @Test
    void anUnknownOrMissingParentFailsTheParse() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                item_models:
                                  'minecraft:item/wynn/x':
                                    texture: 'minecraft:item/stick'
                                    parent: held
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                """
                                item_models:
                                  'minecraft:item/wynn/x': {texture: 'minecraft:item/stick'}
                                """));
    }

    @Test
    void aMalformedTableFailsTheParse() {
        String recipe = "recipes:\n  a: {art: 'minecraft:item/a'}\n";
        for (String yaml :
                List.of(
                        // A family without its models, with an unknown recipe, or with a name
                        // under two headings, which YAML's duplicate check cannot see.
                        "materials:\n  f: {vanilla: {x: 'minecraft:item/x'}}\n",
                        "materials:\n  f: {models: 'minecraft:item/f_', b: {x: '#102030'}}\n",
                        "materials:\n  f: {models: 'minecraft:item/f_', a: {x: '#102030'},"
                                + " vanilla: {x: 'minecraft:item/x'}}\n",
                        "recipes:\n  a: {art: 'minecraft:item/a'}\n  b: {art: 'minecraft:item/b'}\n"
                                + "materials:\n  f: {models: 'minecraft:item/f_',"
                                + " a: {x: '#102030'}, b: {x: '#102030'}}\n",
                        // A name that is not one, a bad colour and a pattern for a prefix.
                        "materials:\n  f: {models: 'minecraft:item/f_', a: {'X.*': '#102030'}}\n",
                        "materials:\n  f: {models: 'minecraft:item/f_', a: {x: 'green'}}\n",
                        "materials:\n  f: {models: 'minecraft:item/(f)_', a: {x: '#102030'}}\n",
                        // A recipe whose name a family's own key would hide, and one without art.
                        "recipes:\n  vanilla: {art: 'minecraft:item/a'}\n",
                        "recipes:\n  b: {scale_from: '#102030'}\n",
                        // A copy a table makes and a source_copies row repeats.
                        "materials:\n  f: {models: 'minecraft:item/f_', a: {x: '#102030'}}\n"
                                + "source_copies:\n  'vetsmod:item/retro/material/f_x': 'minecraft:a'\n",
                        // The content book's table without its models, or a state's colour.
                        "content_book_activities:\n  activities: {x: 'minecraft:item/x'}\n",
                        "content_book_activities:\n  models: 'minecraft:item/b_'\n"
                                + "  states: {s: {scale_from: '#102030'}}\n"
                                + "  activities: {x: 'minecraft:item/x'}\n")) {
            String fixture = yaml.startsWith("recipes:") ? yaml : recipe + yaml;
            assertThrows(IllegalArgumentException.class, () -> parseTextures(fixture), fixture);
        }
    }

    @Test
    void aTablesRowsComeBeforeItemModelsOwn() {
        RetroManifest manifest =
                parseTextures(
                        """
                        item_models:
                          'minecraft:item/.*': 'minecraft:item/other'
                        materials:
                          f:
                            models: 'minecraft:item/f_'
                            vanilla: {x: 'minecraft:item/x'}
                        """);
        assertEquals(
                new ModelStub.TintProof("minecraft:item/x", ParentKind.GENERATED, Atlas.ITEMS),
                new ModelRules(manifest.models()).itemStub("minecraft:item/f_x").orElseThrow());
    }
}
