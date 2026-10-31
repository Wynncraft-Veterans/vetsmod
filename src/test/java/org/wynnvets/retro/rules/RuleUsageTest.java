package org.wynnvets.retro.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.rules.RuleUsage.RowUse;

/** Tests for {@link RuleUsage}: the per-row counts the retro report lists. */
class RuleUsageTest {

    private static final String STONE = "minecraft:block/stone";

    @Test
    void usageCountsWhatEachRowDecidedBelowTheSectionsAboveIt() {
        RetroManifest manifest =
                ManifestFixtures.parse(
                        """
                        sources:
                          'minecraft:entity/.*': [vanilla]
                          '.*': [vanilla]
                          'minecraft:block/.*': [vanilla]
                        keep_wynncraft:
                          - 'minecraft:block/tripwire'
                          - 'minecraft:block/gone'
                        tints:
                          'minecraft:block/(tripwire|purpur_block)': {tint: auto}
                        aliases:
                          'minecraft:block/variants/stone_\\d+': 'minecraft:block/stone'
                          'minecraft:block/purpur_block': 'minecraft:block/oak_planks'
                        same_name:
                          - 'minecraft:block/.*'
                          - 'minecraft:item/.*'
                        layer_groups:
                          - 'minecraft:block/variants/.*'
                        recipes:
                          potion_overlay: {art: 'minecraft:item/potion_overlay'}
                        potions:
                          mana: {colour: '#FFFFFF'}
                        item_models:
                          'minecraft:item/wynn/potion/\\w+':
                            texture: 'minecraft:item/stick'
                            parent: handheld
                          'minecraft:item/wynn/\\w+/\\w+':
                            texture: 'minecraft:item/stick'
                            parent: handheld
                        equipment:
                          'minecraft:(pale_)?iron': 'minecraft:iron'
                          'minecraft:tan': keep
                        """,
                        "ingredients: {gold_nugget: 'minecraft:item/gold_nugget'}\n");

        RuleUsage usage =
                RuleUsage.of(
                        manifest.textures(),
                        manifest.models(),
                        Arrays.asList(
                                "minecraft:block/tripwire",
                                "minecraft:block/purpur_block",
                                STONE,
                                "minecraft:block/variants/stone_1",
                                "minecraft:block/variants/stone_2",
                                null),
                        Arrays.asList(
                                "minecraft:item/wynn/potion/mana_full",
                                "minecraft:item/wynn/weapon/idol",
                                "minecraft:item/wynn/ingredient/gold_nugget",
                                "minecraft:item/wynn/ingredient/pink_wool",
                                null),
                        List.of("minecraft:iron", "minecraft:pale_iron", "minecraft:shaman"));

        assertEquals(
                List.of(
                        new RowUse("sources", "minecraft:entity/.*", 0),
                        new RowUse("sources", ".*", 5),
                        new RowUse("sources", "minecraft:block/.*", 0),
                        new RowUse("keep_wynncraft", "minecraft:block/tripwire", 1),
                        new RowUse("keep_wynncraft", "minecraft:block/gone", 0),
                        new RowUse("tints", "minecraft:block/(tripwire|purpur_block)", 1),
                        new RowUse("aliases", "minecraft:block/variants/stone_\\d+", 2),
                        new RowUse("aliases", "minecraft:block/purpur_block", 0),
                        new RowUse("same_name", "minecraft:block/.*", 1),
                        new RowUse("same_name", "minecraft:item/.*", 0),
                        new RowUse("layer_groups", "minecraft:block/variants/.*", 2),
                        new RowUse(
                                "potions", "minecraft:item/wynn/potion/mana_(empty|half|full)", 1),
                        new RowUse("item_models", "minecraft:item/wynn/potion/\\w+", 0),
                        new RowUse("item_models", "minecraft:item/wynn/\\w+/\\w+", 1),
                        new RowUse("equipment", "minecraft:(pale_)?iron", 2),
                        new RowUse("equipment", "minecraft:tan", 0)),
                usage.rows());
        assertEquals(List.of("minecraft:shaman"), usage.equipmentWithoutRow());
        assertEquals(List.of("pink_wool"), usage.stemsWithoutRow());
    }
}
