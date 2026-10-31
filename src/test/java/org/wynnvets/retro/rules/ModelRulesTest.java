package org.wynnvets.retro.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.AssetSections;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.ParentKind;
import org.wynnvets.retro.manifest.RetroManifest;

/**
 * Tests for {@link ModelRules}, {@link ModelStub} and {@link Atlas}, on small parsed fixtures.
 *
 * <p>The suite pins item-model stubs of both kinds and their atlas, potion stubs, the equipment
 * lookup, the ingredient lookup, the model stub that picks among those rules, what each stub tells
 * the report, and the ingredient stem. What the shipped files decide is
 * {@link org.wynnvets.retro.manifest.ShippedItemModelsTest ShippedItemModelsTest}'s,
 * {@link org.wynnvets.retro.manifest.ShippedEquipmentTest ShippedEquipmentTest}'s and
 * {@link org.wynnvets.retro.manifest.ShippedPotionsTest ShippedPotionsTest}'s.</p>
 */
class ModelRulesTest {

    private static final String STONE = "minecraft:block/stone";
    private static final String HEART = "minecraft:gui/sprites/hud/heart/full";

    private static ModelRules rules(String textures) {
        return rules(textures, "");
    }

    private static ModelRules rules(String textures, String ingredients) {
        return new ModelRules(ManifestFixtures.parse(textures, ingredients).models());
    }

    // ----- Item models -----

    @Test
    void anItemStubFillsItsRowsGroupsIn() {
        ModelRules rules =
                rules(
                        """
                        item_models:
                          'minecraft:item/wynn/(\\w+)_x':
                            texture: 'minecraft:item/$1'
                            parent: handheld
                        """);

        ModelStub.TintProof stub =
                assertInstanceOf(
                        ModelStub.TintProof.class,
                        rules.itemStub("minecraft:item/wynn/stick_x").orElseThrow());
        assertEquals("minecraft:item/stick", stub.art());
        assertEquals(ParentKind.HANDHELD, stub.parent());
        assertEquals(Atlas.ITEMS, stub.atlas());
        assertEquals("vetsmod:item/retro/tint_proof_handheld_item", stub.parentModel());
        assertEquals("vetsmod:item/retro/blank", stub.blank());
    }

    @Test
    void aBlockTextureGetsTheBlocksAtlasAndItsParent() {
        ModelRules rules =
                rules(
                        """
                        item_models:
                          'minecraft:item/wynn/flower':
                            texture: 'minecraft:block/poppy'
                            parent: generated
                        """);

        ModelStub.TintProof stub =
                assertInstanceOf(
                        ModelStub.TintProof.class,
                        rules.itemStub("minecraft:item/wynn/flower").orElseThrow());
        assertEquals(Atlas.BLOCKS, stub.atlas());
        assertEquals("vetsmod:item/retro/tint_proof_generated_block", stub.parentModel());
        assertEquals("vetsmod:block/retro/blank", stub.blank());
    }

    @Test
    void theFirstMatchingItemModelRowDecidesEvenWhenItsTextureFitsNoAtlas() {
        // A stub that mixed atlases would fail to bake, so the row gives none; a later row is not
        // a fallback.
        ModelRules rules =
                rules(
                        """
                        item_models:
                          'minecraft:item/wynn/trident':
                            texture: 'minecraft:entity/trident'
                            parent: handheld
                          'minecraft:item/wynn/\\w+':
                            texture: 'minecraft:item/stick'
                            parent: handheld
                        """);

        assertEquals(Optional.empty(), rules.itemStub("minecraft:item/wynn/trident"));
        assertEquals(
                Optional.of(
                        new ModelStub.TintProof(
                                "minecraft:item/stick", ParentKind.HANDHELD, Atlas.ITEMS)),
                rules.itemStub("minecraft:item/wynn/spear"));
        assertEquals(Optional.empty(), rules.itemStub("minecraft:item/other/spear"), "no row");
    }

    @Test
    void aRowWithAModelStubsThatModelAndTheFirstMatchingRowDecidesAcrossBothKinds() {
        // A model stub only names the vanilla model as its parent, so the stack's dye tints it as
        // it tints the vanilla item.
        ModelRules rules =
                rules(
                        """
                        item_models:
                          'minecraft:item/wynn/armor/(?:pale_)?leather_(\\w+)':
                            model: 'minecraft:item/leather_$1'
                          'minecraft:item/wynn/armor/\\w+':
                            texture: 'minecraft:item/iron_helmet'
                            parent: generated
                        """);

        assertEquals(
                Optional.of(new ModelStub.ItemParent("minecraft:item/leather_helmet")),
                rules.itemStub("minecraft:item/wynn/armor/pale_leather_helmet"));
        assertEquals(
                Optional.of(
                        new ModelStub.TintProof(
                                "minecraft:item/iron_helmet", ParentKind.GENERATED, Atlas.ITEMS)),
                rules.itemStub("minecraft:item/wynn/armor/leather"),
                "the model row does not match, so the next row decides");
    }

    @Test
    void atlasOfReadsTheTexturesFolder() {
        assertEquals(Optional.of(Atlas.BLOCKS), Atlas.of(STONE));
        assertEquals(Optional.of(Atlas.BLOCKS), Atlas.of("minecraft:block/variants/stone_0"));
        assertEquals(Optional.of(Atlas.ITEMS), Atlas.of("minecraft:item/stick"));
        assertEquals(
                Optional.of(Atlas.ITEMS), Atlas.of("vetsmod:item/retro/blank"), "any namespace");

        for (String other :
                Arrays.asList(
                        "minecraft:entity/trident",
                        HEART,
                        "minecraft:items/stick",
                        "minecraft:item",
                        "minecraft:item/",
                        "item/stick",
                        ":item/stick",
                        null)) {
            assertEquals(Optional.empty(), Atlas.of(other), String.valueOf(other));
        }
    }

    // ----- Potions -----

    @Test
    void aPotionStubTintsTheVanillaLiquidItsFamilysColour() {
        RetroManifest manifest =
                ManifestFixtures.parseTextures(
                        ManifestFixtures.POTION_RECIPE
                                + """
                                potions:
                                  mana: {colour: '#385DC6', avomod: potion_mana}
                                  agility: {colour: '#FFFFFF'}
                                """);
        ModelRules rules = new ModelRules(manifest.models());

        ModelStub.Potion mana =
                rules.potionStub("minecraft:item/wynn/potion/mana_full").orElseThrow();
        assertEquals("vetsmod:item/retro/potion/mana", mana.liquid());
        AssetSections.SourceCopy manaLiquid = manifest.assets().sourceCopies().get(mana.liquid());
        assertEquals("minecraft:item/potion_overlay", manaLiquid.source());
        assertEquals(0x385DC6, manaLiquid.colour());
        assertEquals(
                mana,
                rules.potionStub("minecraft:item/wynn/potion/mana_empty").orElseThrow(),
                "every fill of a family");

        String agility =
                rules.potionStub("minecraft:item/wynn/potion/agility_half").orElseThrow().liquid();
        assertEquals(0xFFFFFF, manifest.assets().sourceCopies().get(agility).colour());
        assertEquals(mana, rules.potionStub("avomod2:item/potion_mana").orElseThrow());
        assertEquals(Optional.empty(), rules.potionStub("avomod2:item/potion_rainbow"), "no row");
    }

    // ----- Equipment -----

    @Test
    void anEquipmentAssetGoesToItsFirstMatchingRowsVanillaAssetOrStays() {
        ModelRules rules =
                rules(
                        """
                        equipment:
                          'minecraft:hidden': keep
                          'minecraft:(pale_)?(iron|gold)': 'minecraft:$2'
                          'minecraft:(pale_)?titanium': 'minecraft:netherite'
                          'minecraft:\\w+': 'minecraft:leather'
                        """);

        assertEquals(Optional.of("minecraft:iron"), rules.equipmentAsset("minecraft:pale_iron"));
        assertEquals(Optional.of("minecraft:gold"), rules.equipmentAsset("minecraft:gold"));
        assertEquals(
                Optional.of("minecraft:netherite"), rules.equipmentAsset("minecraft:titanium"));
        assertEquals(Optional.empty(), rules.equipmentAsset("minecraft:hidden"), "keep");
        assertEquals(
                Optional.of("minecraft:leather"),
                rules.equipmentAsset("minecraft:tan"),
                "a later row matches");
        assertEquals(Optional.empty(), rules.equipmentAsset("wynn:tan"), "no row");
    }

    // ----- Ingredients -----

    @Test
    void anIngredientsParentIsItsRow() {
        ModelRules rules =
                rules(
                        "",
                        """
                        ingredients:
                          gold_nugget: 'minecraft:item/gold_nugget'
                          ram_horn: keep
                        """);

        assertEquals(
                Optional.of("minecraft:item/gold_nugget"), rules.ingredientParent("gold_nugget"));
        assertEquals(Optional.empty(), rules.ingredientParent("ram_horn"), "keep");
        assertEquals(Optional.empty(), rules.ingredientParent("pink_wool"), "no row");
    }

    // ----- Model stubs -----

    @Test
    void aModelStubComesFromTheStemsRowThenPotionsThenItemModels() {
        ModelRules rules =
                rules(
                        ManifestFixtures.POTION_RECIPE
                                + """
                        potions:
                          mana: {colour: '#385DC6'}
                        item_models:
                          'minecraft:item/wynn/(potion|weapon|ingredient)/\\w+':
                            texture: 'minecraft:item/iron_shovel'
                            parent: handheld
                        """,
                        """
                        ingredients:
                          gold_nugget: 'minecraft:item/gold_nugget'
                          ram_horn: keep
                        """);

        assertEquals(
                Optional.of(new ModelStub.Ingredient("gold_nugget", "minecraft:item/gold_nugget")),
                rules.modelStub("minecraft:item/wynn/ingredient/gold_nugget"));
        assertEquals(
                Optional.empty(),
                rules.modelStub("minecraft:item/wynn/ingredient/ram_horn"),
                "keep, and no item_models row is tried");
        assertEquals(
                Optional.of(
                        new ModelStub.TintProof(
                                "minecraft:item/iron_shovel", ParentKind.HANDHELD, Atlas.ITEMS)),
                rules.modelStub("minecraft:item/wynn/ingredient/pink_wool"),
                "no row of its own, so its item_models row");
        assertEquals(
                Optional.of(new ModelStub.Potion("vetsmod:item/retro/potion/mana")),
                rules.modelStub("minecraft:item/wynn/potion/mana_empty"),
                "potions before item_models");
        assertEquals(
                Optional.of(
                        new ModelStub.TintProof(
                                "minecraft:item/iron_shovel", ParentKind.HANDHELD, Atlas.ITEMS)),
                rules.modelStub("minecraft:item/wynn/potion/agility_empty"),
                "a family with no potions row");
        assertEquals(
                Optional.of(
                        new ModelStub.TintProof(
                                "minecraft:item/iron_shovel", ParentKind.HANDHELD, Atlas.ITEMS)),
                rules.modelStub("minecraft:item/wynn/weapon/spear"));
        assertEquals(Optional.empty(), rules.modelStub("minecraft:item/stick"), "no rule");
    }

    @Test
    void aModelStubNamesItsRuleAndWhatItDraws() {
        ModelStub ingredient = new ModelStub.Ingredient("egg", "minecraft:item/egg");
        assertEquals("ingredients", ingredient.rule());
        assertEquals("minecraft:item/egg", ingredient.art());

        ModelStub potion = new ModelStub.Potion("vetsmod:item/retro/potion/mana");
        assertEquals("potions", potion.rule());
        assertEquals("vetsmod:item/retro/potion/mana", potion.art());

        ModelStub tintProof =
                new ModelStub.TintProof("minecraft:item/bow", ParentKind.BOW, Atlas.ITEMS);
        assertEquals("item_models (tint-proof)", tintProof.rule());
        assertEquals("minecraft:item/bow", tintProof.art());

        ModelStub model = new ModelStub.ItemParent("minecraft:item/leather_boots");
        assertEquals("item_models (model)", model.rule());
        assertEquals("minecraft:item/leather_boots", model.art());
    }

    @Test
    void anIngredientsStemIsWhatFollowsItsFolder() {
        assertEquals(
                "gold_nugget",
                ModelRules.ingredientStem("minecraft:item/wynn/ingredient/gold_nugget"));
        assertNull(ModelRules.ingredientStem("minecraft:item/wynn/potion/mana_full"));
        assertNull(
                ModelRules.ingredientStem("avomod2:item/wynn/ingredient/gold_nugget"),
                "Wynncraft's namespace only");
        assertNull(ModelRules.ingredientStem(null));
    }

    // ----- Null -----

    @Test
    void noLookupThrowsForNull() {
        ModelRules rules =
                rules(
                        """
                        item_models:
                          '.*': {texture: 'minecraft:item/stick', parent: handheld}
                        equipment:
                          '.*': 'minecraft:iron'
                        """,
                        "ingredients: {gold_nugget: 'minecraft:item/gold_nugget'}\n");

        assertEquals(Optional.empty(), rules.itemStub(null));
        assertEquals(Optional.empty(), rules.potionStub(null));
        assertEquals(Optional.empty(), rules.equipmentAsset(null));
        assertEquals(Optional.empty(), rules.ingredientParent(null));
        assertEquals(Optional.empty(), rules.modelStub(null));
    }
}
