package org.wynnvets.retro.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ParentKind;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.StackSections;

/**
 * Tests for {@link RetroAssetIds}. The expected ids are literals, so a change to a template fails
 * here even though every caller shares it.
 */
class RetroAssetIdsTest {

    @Test
    void anOverhealthHeartsSpriteIsItsTextureInTheGuiAtlas() {
        assertEquals(
                "vetsmod:retro/heart/orange_full",
                RetroAssetIds.overhealthSprite("orange", RetroAssetIds.HEART_FULL));
        assertEquals(
                "vetsmod:gui/sprites/retro/heart/green_half",
                RetroAssetIds.overhealthTexture("green", RetroAssetIds.HEART_HALF));
    }

    @Test
    void anItemDefinitionsCopyIsInVetsmodsNamespaceUnderTheItemsOwnId() {
        assertEquals(
                "vetsmod:retro/minecraft/iron_shovel",
                RetroAssetIds.itemDefinitionCopy("minecraft:iron_shovel"));
        assertEquals(
                "vetsmod:retro/avomod2/potion_mana",
                RetroAssetIds.itemDefinitionCopy("avomod2:potion_mana"),
                "any namespace keeps its own folder");
        assertNull(RetroAssetIds.itemDefinitionCopy("iron_shovel"), "no namespace");
        assertNull(RetroAssetIds.itemDefinitionCopy("minecraft:"), "no path");
        assertNull(RetroAssetIds.itemDefinitionCopy(":iron_shovel"), "an empty namespace");
        assertNull(RetroAssetIds.itemDefinitionCopy(null));
    }

    @Test
    void theLegacyStyleIsWhatTheRuleFilesCopy() throws IOException {
        // The sprites of vetsmod:legacy are vetsmod:gui/sprites/tooltip/legacy_(background|frame),
        // copies of Wynncraft's unique style.
        Map<String, String> copies = RetroManifest.load().assets().wynncraftCopies();

        assertEquals("vetsmod:legacy", RetroAssetIds.LEGACY_TOOLTIP_STYLE);
        for (String sprite : RetroAssetIds.LEGACY_TOOLTIP_SPRITES) {
            assertEquals(
                    sprite.replace("vetsmod:", "minecraft:").replace("legacy_", "unique_"),
                    copies.get(sprite),
                    sprite);
        }
    }

    @Test
    void anAccessorysItemAndAssetShareOnePath() {
        assertEquals("retro/accessory/ring_gold", RetroAssetIds.accessoryPath("RING", "gold"));
        assertEquals(
                "vetsmod:retro/accessory/necklace_iron",
                RetroAssetIds.accessoryItem("NECKLACE", "iron"));
        assertEquals(
                "vetsmod:item/retro/accessory/ring_empty",
                RetroAssetIds.generatedAsset(
                        RetroAssetIds.accessoryPath("RING", RetroAssetIds.ACCESSORY_EMPTY)));
    }

    @Test
    void anOutlinedIconsItemAndAssetShareOnePath() {
        assertEquals("retro/icon/weapon_tome", RetroAssetIds.outlinedIconPath("WEAPON_TOME"));
        assertEquals("vetsmod:retro/icon/spear", RetroAssetIds.outlinedIconItem("SPEAR"));
        assertEquals(
                "vetsmod:item/retro/icon/spear",
                RetroAssetIds.generatedAsset(RetroAssetIds.outlinedIconPath("SPEAR")));
    }

    @Test
    void aTableCopyNamesItsFamilyOrActivity() {
        assertEquals(
                "vetsmod:item/retro/material/paper_oak",
                RetroAssetIds.materialCopy("paper", "oak"));
        assertEquals(
                "vetsmod:item/retro/book/raid_locked", RetroAssetIds.bookCopy("raid", "locked"));
        assertEquals("vetsmod:item/retro/potion/mana", RetroAssetIds.potionLiquid("mana"));
    }

    @Test
    void aStubsParentAndBlankFollowItsKindAndAtlas() {
        assertEquals(
                "vetsmod:item/retro/tint_proof_handheld_item",
                RetroAssetIds.tintProofParent(ParentKind.HANDHELD, Atlas.ITEMS));
        assertEquals(
                "vetsmod:item/retro/tint_proof_generated_block",
                RetroAssetIds.tintProofParent(ParentKind.GENERATED, Atlas.BLOCKS));
        assertEquals("vetsmod:item/retro/blank", RetroAssetIds.blank(Atlas.ITEMS));
        assertEquals("vetsmod:block/retro/blank", RetroAssetIds.blank(Atlas.BLOCKS));
    }

    @Test
    void theIngredientsFilesAccessoryRowsAreGeneratedAccessories() throws IOException {
        // Those rows spell the ids out, as the file has only ids to name a parent with.
        RetroManifest manifest = RetroManifest.load();
        StackSections.AccessoryTiers rows = manifest.stacks().accessoryTiers();
        Set<String> generated = new HashSet<>();
        for (String type : rows.art().keySet()) {
            for (StackSections.AccessoryTier tier : rows.tiers()) {
                generated.add(
                        RetroAssetIds.generatedAsset(
                                RetroAssetIds.accessoryPath(type, tier.name())));
            }
        }
        Map<String, String> ingredients = manifest.models().ingredients();
        boolean any = false;
        for (Map.Entry<String, String> row : ingredients.entrySet()) {
            if (!row.getValue().startsWith("vetsmod:item/retro/accessory/")) continue;
            any = true;
            assertTrue(generated.contains(row.getValue()), row.getKey());
        }
        assertTrue(any, "the file has such rows");
    }
}
