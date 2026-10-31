package org.wynnvets.retro.manifest;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ModelSections.ItemModel;
import org.wynnvets.retro.manifest.StackSections.StackModel;
import org.wynnvets.retro.rules.PartFilter;

/**
 * Tests for the shipped {@code parts} rows ({@link PartSections}): that every part has a row and
 * that the ids the rule files act on land in the part they should. See
 * {@link ShippedFiles ShippedFiles}.
 */
class ShippedPartsTest {

    /** Wynncraft's models under {@code minecraft:item/wynn/}, each with the part that owns it. */
    private static final Map<String, RetroPart> MODELS =
            Map.ofEntries(
                    entry("weapon/warrior/spear_air_a", RetroPart.WEAPONS),
                    entry("skin/relik/abyssal_anomaly", RetroPart.WEAPONS),
                    entry("armor/boots/gold_boots", RetroPart.ARMOUR),
                    entry("gui/slot/ring", RetroPart.SCREENS),
                    entry("gui/slot/necklace", RetroPart.SCREENS),
                    entry("gui/slot/helmet", RetroPart.SCREENS),
                    entry("tool/iron_axe", RetroPart.TOOLS),
                    entry("ingredient/gold_nugget", RetroPart.CRAFTING),
                    entry("economy/mining/ingot_cobalt", RetroPart.CRAFTING),
                    entry("economy/meals/soup", RetroPart.CRAFTING),
                    entry("economy/woodcutting/paper_oak", RetroPart.CRAFTING),
                    entry("potion/mana_full", RetroPart.POTIONS),
                    entry("gui/loot/rare", RetroPart.GEAR_BOXES),
                    entry("loot/rare", RetroPart.GEAR_BOXES),
                    entry("mastery_tome/weapon", RetroPart.GEAR_BOXES),
                    entry("gui/layer/tier/tab", RetroPart.HIGHLIGHTS),
                    entry("gui/layer/tier/full", RetroPart.HIGHLIGHTS),
                    entry("gui/content_book/raid_active", RetroPart.SCREENS),
                    entry("gui/character_info/ability_tree", RetroPart.SCREENS),
                    entry("gui/trade_icon", RetroPart.SCREENS),
                    entry("gui/column", RetroPart.SCREENS),
                    entry("dungeon/key", RetroPart.ITEMS),
                    entry("scroll/teleport", RetroPart.ITEMS),
                    entry("powder/small_air", RetroPart.ITEMS),
                    entry("rune/az", RetroPart.ITEMS),
                    entry("ward/red", RetroPart.ITEMS),
                    entry("player/content_book", RetroPart.ITEMS),
                    entry("gui/player/content_book", RetroPart.ITEMS));

    /**
     * The part of each family of Wynncraft's models under {@code minecraft:item/wynn/}, first match
     * by folder first; a model of any other folder is {@link RetroPart#ITEMS}'s.
     */
    private static final List<Map.Entry<String, RetroPart>> FAMILIES =
            List.of(
                    entry("weapon/", RetroPart.WEAPONS),
                    entry("skin/", RetroPart.WEAPONS),
                    entry("armor/", RetroPart.ARMOUR),
                    entry("tool/", RetroPart.TOOLS),
                    entry("ingredient/", RetroPart.CRAFTING),
                    entry("economy/", RetroPart.CRAFTING),
                    entry("potion/", RetroPart.POTIONS),
                    entry("loot/", RetroPart.GEAR_BOXES),
                    entry("gui/loot/", RetroPart.GEAR_BOXES),
                    entry("mastery_tome/", RetroPart.GEAR_BOXES),
                    entry("gui/layer/tier/", RetroPart.HIGHLIGHTS),
                    entry("gui/player/", RetroPart.ITEMS),
                    entry("gui/", RetroPart.SCREENS));

    /** A sample of what each row of the texture sections decides, with the part that owns it. */
    private static final Map<String, RetroPart> TEXTURES =
            Map.ofEntries(
                    entry("minecraft:block/stone", RetroPart.BLOCKS),
                    entry("minecraft:block/variants/dark_stone_0", RetroPart.BLOCKS),
                    entry("minecraft:entity/signs/oak", RetroPart.BLOCKS),
                    entry("minecraft:entity/signs/hanging/oak", RetroPart.BLOCKS),
                    entry("minecraft:entity/bed/red", RetroPart.BLOCKS),
                    entry("minecraft:entity/banner/base", RetroPart.BLOCKS),
                    entry("minecraft:entity/banner_base", RetroPart.BLOCKS),
                    entry("minecraft:entity/chest/normal", RetroPart.BLOCKS),
                    entry("minecraft:entity/conduit/base", RetroPart.BLOCKS),
                    entry("minecraft:entity/decorated_pot/decorated_pot_base", RetroPart.BLOCKS),
                    entry("minecraft:entity/bell/bell_body", RetroPart.BLOCKS),
                    entry("minecraft:entity/shulker/shulker", RetroPart.BLOCKS),
                    entry("minecraft:entity/enchanting_table_book", RetroPart.BLOCKS),
                    entry("minecraft:entity/beacon_beam", RetroPart.BLOCKS),
                    entry("minecraft:entity/end_gateway_beam", RetroPart.BLOCKS),
                    entry("minecraft:entity/end_portal", RetroPart.BLOCKS),
                    entry("minecraft:entity/shield_base_nopattern", RetroPart.MOBS),
                    entry("minecraft:entity/zombie/zombie", RetroPart.MOBS),
                    entry("minecraft:entity/villager/villager", RetroPart.MOBS),
                    entry("minecraft:entity/equipment/llama_body/white", RetroPart.MOBS),
                    entry("minecraft:entity/equipment/humanoid/iron", RetroPart.ARMOUR),
                    entry("minecraft:entity/equipment/humanoid_leggings/gold", RetroPart.ARMOUR),
                    entry("minecraft:item/stick", RetroPart.ITEMS),
                    entry("minecraft:item/iron_shovel", RetroPart.ITEMS),
                    entry("minecraft:mob_effect/speed", RetroPart.EFFECTS),
                    entry("minecraft:gui/container/generic_54", RetroPart.SCREENS),
                    entry("minecraft:gui/sprites/widget/button", RetroPart.SCREENS),
                    entry("minecraft:gui/sprites/container/slot/helmet", RetroPart.SCREENS),
                    entry("minecraft:wynn/gui/slot/helmet", RetroPart.SCREENS),
                    entry("minecraft:gui/sprites/tooltip/background", RetroPart.TOOLTIPS),
                    entry("minecraft:gui/sprites/tooltip/unique_frame", RetroPart.TOOLTIPS),
                    entry("minecraft:gui/sprites/hud/heart/full", RetroPart.HUD),
                    entry("minecraft:gui/sprites/hud/hotbar", RetroPart.HUD));

    private static PartSections rows() throws IOException {
        return RetroManifest.load().parts();
    }

    private static PartFilter withOff(PartSections rows, RetroPart part) {
        return new PartFilter(rows, RetroPart.allBut(Set.of(part)));
    }

    /** The part whose going off turns a model off, or {@code null} when none does. */
    private static RetroPart modelOwner(PartSections rows, String modelId) {
        for (RetroPart part : RetroPart.values()) {
            if (!withOff(rows, part).modelOn(modelId)) return part;
        }
        return null;
    }

    /** The part whose going off turns a texture off, or {@code null} when none does. */
    private static RetroPart textureOwner(PartSections rows, String textureId) {
        for (RetroPart part : RetroPart.values()) {
            if (!withOff(rows, part).textureOn(textureId)) return part;
        }
        return null;
    }

    /** The part a model of {@code minecraft:item/wynn/} belongs to, by its folders. */
    private static RetroPart family(String model) {
        for (Map.Entry<String, RetroPart> family : FAMILIES) {
            if (model.startsWith(family.getKey())) return family.getValue();
        }
        return RetroPart.ITEMS;
    }

    @Test
    void everyPartHasARowInTheOrderTheEnumDeclaresThem() throws IOException {
        assertEquals(List.of(RetroPart.values()), List.copyOf(rows().parts().keySet()));
    }

    @Test
    void everyPinnedModelLandsInItsFamilysPart() throws IOException {
        PartSections rows = rows();
        for (String model : ShippedItemModelsTest.PINNED_MODELS.keySet()) {
            assertEquals(family(model), modelOwner(rows, "minecraft:item/wynn/" + model), model);
        }
    }

    @Test
    void everyTableRowsModelLandsInItsTablesPart() throws IOException {
        PartSections rows = rows();
        ItemTables tables = ShippedItemModelsTest.realTables();
        for (ItemModel row : tables.itemModels()) {
            String model = row.models().pattern();
            assertEquals(
                    model.startsWith("minecraft:item/wynn/gui/content_book/")
                            ? RetroPart.SCREENS
                            : RetroPart.CRAFTING,
                    modelOwner(rows, model),
                    model);
        }
        for (ModelSections.Potion potion : tables.potions()) {
            String model = potion.models().pattern().replace("(empty|half|full)", "full");
            assertEquals(RetroPart.POTIONS, modelOwner(rows, model), model);
        }
    }

    @Test
    void wynncraftsModelsLandInTheirParts() throws IOException {
        PartSections rows = rows();
        MODELS.forEach(
                (model, part) ->
                        assertEquals(
                                part, modelOwner(rows, "minecraft:item/wynn/" + model), model));
        assertEquals(RetroPart.POTIONS, modelOwner(rows, "avomod2:item/potion_health"));
    }

    @Test
    void theBlockModelsAreTheBlocksPart() throws IOException {
        RetroManifest manifest = RetroManifest.load();
        for (String model : manifest.models().blockModels().keySet()) {
            assertEquals(RetroPart.BLOCKS, modelOwner(manifest.parts(), model), model);
        }
    }

    @Test
    void theTextureSamplesLandInTheirParts() throws IOException {
        PartSections rows = rows();
        TEXTURES.forEach(
                (texture, part) -> assertEquals(part, textureOwner(rows, texture), texture));
    }

    @Test
    void everyPinnedTintAndVariantIsTheBlocksPart() throws IOException {
        PartSections rows = rows();
        for (String texture : ShippedTexturesTest.PINNED_TINTS.keySet()) {
            assertEquals(
                    RetroPart.BLOCKS, textureOwner(rows, ShippedFiles.inFull(texture)), texture);
        }
        for (String variant : ShippedTexturesTest.PINNED_VARIANTS.keySet()) {
            assertEquals(
                    RetroPart.BLOCKS,
                    textureOwner(rows, "minecraft:block/variants/" + variant),
                    variant);
        }
    }

    @Test
    void theTexturesTheStepsDrawLandInTheirParts() throws IOException {
        RetroManifest manifest = RetroManifest.load();
        for (String texture : manifest.assets().blankTextures()) {
            assertEquals(RetroPart.HUD, textureOwner(manifest.parts(), texture), texture);
        }
        for (String texture : manifest.assets().wynntilsTextures().keySet()) {
            assertEquals(RetroPart.SCREENS, textureOwner(manifest.parts(), texture), texture);
        }
        for (String texture : manifest.assets().imageEdits().keySet()) {
            assertEquals(RetroPart.SCREENS, textureOwner(manifest.parts(), texture), texture);
        }
    }

    @Test
    void everyWynntilsSettingBelongsToAPart() throws IOException {
        RetroManifest manifest = RetroManifest.load();
        Map<String, RetroPart> owners =
                Map.of(
                        "ItemHighlight", RetroPart.HIGHLIGHTS,
                        "CustomPlayerListOverlay", RetroPart.PLAYER_LIST,
                        "UnidentifiedItemIcon", RetroPart.GEAR_BOXES,
                        "WynntilsContentBook", RetroPart.SCREENS);

        assertEquals(
                owners.keySet(),
                Set.copyOf(
                        manifest.wynntilsConfig().stream().map(WynntilsSetting::feature).toList()));
        owners.forEach(
                (feature, part) -> {
                    for (RetroPart off : RetroPart.values()) {
                        assertEquals(
                                off != part,
                                withOff(manifest.parts(), off).settingOn(feature),
                                feature + " with " + off + " off");
                    }
                });
    }

    @Test
    void theShieldsRowIsCraftingsAndTheOtherStackRowsItems() throws IOException {
        for (StackModel row : RetroManifest.load().stacks().stackModels()) {
            assertEquals(
                    row.model().equals("minecraft:shield") ? RetroPart.CRAFTING : RetroPart.ITEMS,
                    row.part(),
                    row.model());
        }
    }
}
