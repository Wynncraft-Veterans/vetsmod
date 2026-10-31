package org.wynnvets.retro.manifest;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyFroms;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyHueOnly;
import static org.wynnvets.retro.manifest.ManifestFixtures.copySources;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyTints;
import static org.wynnvets.retro.manifest.ShippedFiles.inFull;
import static org.wynnvets.retro.manifest.ShippedFiles.model;
import static org.wynnvets.retro.manifest.ShippedFiles.onClasspath;
import static org.wynnvets.retro.manifest.ShippedFiles.realModelRules;
import static org.wynnvets.retro.manifest.ShippedFiles.texture;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.AssetSections.SourceCopy;
import org.wynnvets.retro.manifest.ModelSections.ItemModel;
import org.wynnvets.retro.rules.Atlas;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.ModelStub;
import org.wynnvets.retro.rules.RetroAssetIds;
import org.wynnvets.util.Json;

/**
 * Tests for the shipped item model rows and tables ({@link ModelSections}, {@link ItemTables})
 * against the client jar and vetsmod's own assets: that the rows cover the pinned models and send
 * them to the art each row names, that every stub is held like the item it shows and its parent ships
 * with a blank, and the ingredients. See {@link ShippedFiles ShippedFiles}.
 */
class ShippedItemModelsTest {

    /**
     * Wynncraft item models, each with what its stub shows: the texture a tint-proof stub draws,
     * or the item model a model stub parents, as a path under {@code minecraft:} or, for one of
     * vetsmod's own, in full: weapons, armour and tools, and the keys, scrolls and papers,
     * meals, saddles, gear boxes, the ingredient pouch, the crafter bags, the powders, the runes,
     * the wards, the tier layer, the content book and its activities, the ability shard, the
     * augments, Hashr's Crown, the compass menu's buttons, the tomes, and of each table's family
     * one name of each kind: {@link #everyTableFamilyHasAPinOfEachKind} says which.
     */
    static final Map<String, String> PINNED_MODELS =
            Map.ofEntries(
                    entry("weapon/warrior/spear_air_a", "item/iron_shovel"),
                    entry("skin/spear/dune_corseque", "item/iron_shovel"),
                    entry("weapon/mage/wand_basic_wood", "item/stick"),
                    entry("skin/wand/abyssal_staff", "item/stick"),
                    entry("weapon/assassin/dagger_fire_c", "item/shears"),
                    entry("skin/dagger/abyssal_blade", "item/shears"),
                    entry("weapon/archer/bow_thunder_b", "item/bow"),
                    entry("skin/bow/abyssal_bow", "item/bow"),
                    entry("weapon/shaman/relik_basic_wooden", "item/bone"),
                    entry("skin/relik/abyssal_anomaly", "item/bone"),
                    entry("armor/boots/gold_boots", "item/golden_boots"),
                    entry("armor/helmet/pale_gold_helmet", "item/golden_helmet"),
                    entry("armor/leggings/chainmail_leggings", "item/chainmail_leggings"),
                    entry("armor/boots/iron_boots", "item/iron_boots"),
                    entry("armor/helmet/diamond_helmet", "item/diamond_helmet"),
                    entry("armor/chestplate/netherite_chestplate", "item/netherite_chestplate"),
                    entry("armor/leggings/pale_iron_leggings", "item/iron_leggings"),
                    entry("armor/helmet/titanium_helmet", "item/netherite_helmet"),
                    entry("armor/boots/pale_titanium_boots", "item/netherite_boots"),
                    entry("armor/chestplate/leather_chestplate", "item/leather_chestplate"),
                    entry("armor/leggings/pale_leather_leggings", "item/leather_leggings"),
                    entry("tool/iron_axe", "item/iron_axe"),
                    entry("tool/golden_pickaxe", "item/iron_pickaxe"),
                    entry("tool/wooden_rod", "item/fishing_rod"),
                    entry("tool/void_scythe", "item/iron_hoe"),
                    entry("dungeon/key", "vetsmod:block/retro/tripwire_hook"),
                    entry("dungeon/key_broken", "vetsmod:block/retro/tripwire_hook"),
                    entry("scroll/scroll_teleport", "item/map"),
                    entry("economy/woodcutting/paper_white", "item/paper"),
                    entry("economy/woodcutting/paper_oak", "vetsmod:item/retro/material/paper_oak"),
                    entry("economy/woodcutting/scroll_white", "item/paper"),
                    entry("economy/meals/fried", "item/cooked_chicken"),
                    entry("economy/meals/generic", "item/cooked_beef"),
                    entry("economy/meals/generic_item", "item/cooked_beef"),
                    entry("economy/meals/salad", "item/beetroot_soup"),
                    entry("economy/meals/soup", "item/mushroom_stew"),
                    entry("economy/meals/spicy", "item/bread"),
                    entry("economy/meals/steamed", "item/baked_potato"),
                    entry("mount/horse_saddle", "item/saddle"),
                    entry("mount/raptor_saddle", "vetsmod:item/retro/saddle_red"),
                    entry("mount/wyvern_saddle", "vetsmod:item/retro/saddle_white"),
                    entry("mount/horse_whistle", "vetsmod:item/retro/leather_horse_armor"),
                    entry("mount/wyvern_whistle", "item/iron_horse_armor"),
                    entry("mount/raptor_whistle", "item/copper_horse_armor"),
                    entry("gui/loot/unique", "vetsmod:item/retro/chest"),
                    entry("loot/set", "vetsmod:item/retro/chest"),
                    entry("pouch/ingredient_full", "item/bundle"),
                    entry("pouch/crafter_varied", "item/bundle"),
                    entry("pouch/crafter_packed", "item/bundle"),
                    entry("pouch/crafter_stuffed", "item/bundle"),
                    entry("rune/nii", "item/music_disc_mellohi"),
                    entry("rune/az", "item/music_disc_wait"),
                    entry("rune/tol", "item/music_disc_cat"),
                    entry("rune/ek", "item/music_disc_13"),
                    entry("rune/uth", "item/music_disc_mall"),
                    entry("ward/black", "block/black_stained_glass"),
                    entry("ward/blue", "block/blue_stained_glass"),
                    entry("ward/cyan", "block/cyan_stained_glass"),
                    entry("ward/green", "block/green_stained_glass"),
                    entry("ward/orange", "block/orange_stained_glass"),
                    entry("ward/pink", "block/pink_stained_glass"),
                    entry("ward/purple", "block/purple_stained_glass"),
                    entry("ward/red", "block/red_stained_glass"),
                    entry("ward/white", "block/white_stained_glass"),
                    entry("ward/yellow", "block/yellow_stained_glass"),
                    entry("gui/layer/tier/tab", "vetsmod:item/retro/tier_tag"),
                    entry("gui/layer/tier/full", "vetsmod:item/retro/tier_tag"),
                    entry("gui/player/content_book", "item/knowledge_book"),
                    entry("player/content_book", "item/knowledge_book"),
                    entry("player/ability_shard", "vetsmod:item/retro/ability_shard"),
                    entry("augment/corkian_amplifier", "item/shulker_shell"),
                    entry("augment/corkian_simulator", "item/ender_eye"),
                    entry("augment/corkian_insulator", "item/spectral_arrow"),
                    entry("dungeon/token/sand_swept_tomb", "item/golden_helmet"),
                    entry("powder/small_air", "item/gray_dye"),
                    entry("powder/large_air", "item/light_gray_dye"),
                    entry("powder/small_earth", "item/lime_dye"),
                    entry("powder/large_earth", "item/green_dye"),
                    entry("powder/small_thunder", "item/yellow_dye"),
                    entry("powder/large_thunder", "item/orange_dye"),
                    entry("powder/small_water", "item/light_blue_dye"),
                    entry("powder/large_water", "item/cyan_dye"),
                    entry("powder/small_fire", "item/pink_dye"),
                    entry("powder/large_fire", "item/red_dye"),
                    entry("ingredient/alga", "vetsmod:block/retro/green/vine"),
                    entry("ingredient/etheric_fern", "vetsmod:block/retro/green/fern"),
                    entry("ingredient/mega_fern", "vetsmod:block/retro/green/large_fern_top"),
                    entry("ingredient/waterfall_thyme", "vetsmod:block/retro/green/short_grass"),
                    entry("ingredient/green_fin", "vetsmod:block/retro/green/lily_pad"),
                    entry(
                            "economy/mining/ingot_bronze",
                            "vetsmod:item/retro/material/ingot_bronze"),
                    entry("economy/mining/ingot_gold", "item/gold_ingot"),
                    entry("economy/mining/gem_cobalt", "vetsmod:item/retro/material/gem_cobalt"),
                    entry("economy/mining/gem_diamond", "item/diamond"),
                    entry(
                            "economy/farming/grain_heather",
                            "vetsmod:item/retro/material/grain_heather"),
                    entry("economy/farming/grain_wheat", "item/wheat_seeds"),
                    entry(
                            "economy/farming/string_barley",
                            "vetsmod:item/retro/material/string_barley"),
                    entry("economy/farming/string_rice", "item/string"),
                    entry("economy/fishing/oil_bass", "vetsmod:item/retro/material/oil_bass"),
                    entry("economy/fishing/oil_koi", "item/milk_bucket"),
                    entry("economy/fishing/meat_bass", "item/cooked_cod"),
                    entry("economy/fishing/meat_carp", "vetsmod:item/retro/material/meat_carp"),
                    entry(
                            "economy/woodcutting/plank_pine",
                            "vetsmod:item/retro/material/plank_pine"),
                    entry(
                            "economy/woodcutting/plank_willow",
                            "vetsmod:item/retro/material/plank_willow"),
                    entry("economy/woodcutting/plank_oak", "item/oak_door"),
                    entry("gui/slot/ring", "vetsmod:item/retro/accessory/ring_empty"),
                    entry("gui/slot/bracelet", "vetsmod:item/retro/accessory/bracelet_empty"),
                    entry("gui/slot/necklace", "vetsmod:item/retro/accessory/necklace_empty"),
                    entry("gui/trade_icon", "vetsmod:item/retro/blank"),
                    entry("gui/column", "vetsmod:item/retro/blank"),
                    entry("gui/character_info/recruit_friend", "item/structure_void"),
                    entry("gui/character_info/daily_available", "vetsmod:item/retro/chest"),
                    entry("gui/character_info/daily_cooldown", "vetsmod:item/retro/chest"),
                    entry("gui/character_info/store", "vetsmod:item/retro/ender_chest"),
                    entry("gui/character_info/ability_tree", "item/enchanted_book"),
                    entry("gui/character_info/skill_crystal", "item/slime_ball"),
                    entry("gui/character_info/book_strength", "item/written_book"),
                    entry("gui/store/silverbull_emblem", "block/sea_lantern"),
                    entry("mastery_tome/weapon", "item/enchanted_book"),
                    entry("gui/content_book/raid_active", "item/red_dye"),
                    entry("gui/content_book/raid_tracked", "vetsmod:item/retro/book/raid_tracked"),
                    entry("gui/content_book/raid_locked", "vetsmod:item/retro/book/raid_locked"),
                    entry("gui/content_book/cave_locked", "vetsmod:item/retro/book/cave_locked"),
                    entry("gui/content_book/world_event_complete", "item/green_dye"),
                    entry("gui/content_book/complete", "item/bone_meal"),
                    entry("gui/content_book/locked", "item/gray_dye"),
                    entry("outer_void/drop/arable_chunk", "vetsmod:item/retro/arable_chunk"));

    /** What the real {@code retro_textures.yml}'s tables make. */
    static ItemTables realTables() throws IOException {
        return ItemTables.parse(realSections());
    }

    /** The sections of the real {@code retro_textures.yml}. */
    private static Map<?, ?> realSections() throws IOException {
        try (InputStream in =
                ShippedItemModelsTest.class.getResourceAsStream("/retro_textures.yml")) {
            return ManifestYaml.sections(
                    in, ManifestYaml.TEXTURES_FILE, RetroManifest.TEXTURE_SECTIONS);
        }
    }

    /** The one model id a table's row matches. */
    private static String tableModel(ItemModel row) {
        return row.models().pattern();
    }

    /**
     * The texture a {@code source_copies} copy holds, read from the real rule files, or
     * {@code textureId} itself when no row copies to it.
     */
    private static String original(String textureId) throws IOException {
        SourceCopy copy = RetroManifest.load().assets().sourceCopies().get(textureId);
        return copy == null ? textureId : copy.source();
    }

    /** Asserts a tint-proof stub is held as the vanilla item whose icon it shows is. */
    private static void assertHeldLikeItsItem(ModelRules rules, String model) throws IOException {
        ModelStub stub = rules.itemStub(model).orElseThrow();
        if (!(stub instanceof ModelStub.TintProof tintProof)) return;
        // A copy (source_copies) draws its original's art. The large fern's icon is its top.
        String art = original(stub.art());
        String item =
                "minecraft:item/"
                        + art.substring(art.lastIndexOf('/') + 1).replaceFirst("_top$", "");
        JsonObject itemModel = model(item);
        // The fish's own display only places them on a head, where a stub is never worn.
        boolean ownDisplay =
                itemModel.has("display")
                        && !itemModel.getAsJsonObject("display").keySet().equals(Set.of("head"));
        String expected = ownDisplay ? item : inFull(Json.stringOrEmpty(itemModel, "parent"));

        assertEquals(
                art,
                inFull(Json.stringOrEmpty(itemModel.getAsJsonObject("textures"), "layer0")),
                item + " is the item whose icon " + model + " shows");
        assertEquals(
                expected, "minecraft:item/" + tintProof.parent().yamlName(), model + " -> " + item);
    }

    /** The models the pack writes for {@code accessory_tiers}; no classpath file is one. */
    private static Set<String> generatedAccessoryModels() throws IOException {
        StackSections.AccessoryTiers rows = RetroManifest.load().stacks().accessoryTiers();
        Set<String> models = new HashSet<>();
        for (String type : rows.art().keySet()) {
            models.add(
                    RetroAssetIds.generatedAsset(
                            RetroAssetIds.accessoryPath(type, RetroAssetIds.ACCESSORY_EMPTY)));
            for (StackSections.AccessoryTier tier : rows.tiers()) {
                models.add(
                        RetroAssetIds.generatedAsset(
                                RetroAssetIds.accessoryPath(type, tier.name())));
            }
        }
        return models;
    }

    @Test
    void redstoneTorchesAreVanillasCandlesAndEveryParentExists() throws IOException {
        Map<String, String> rows = RetroManifest.load().models().blockModels();

        assertEquals(
                Map.of(
                        "minecraft:block/redstone_torch",
                        "minecraft:block/candle_one_candle_lit",
                        "minecraft:block/redstone_torch_off",
                        "minecraft:block/candle_one_candle",
                        "minecraft:block/redstone_wall_torch",
                        "vetsmod:block/retro/wall_candle_lit",
                        "minecraft:block/redstone_wall_torch_off",
                        "vetsmod:block/retro/wall_candle"),
                rows);
        for (String parent : rows.values()) {
            assertTrue(onClasspath(parent, "models", ".json"), parent);
        }
    }

    @Test
    void eachWoodsPaperIsInItsPlanksColourAndItsPlankIsItsDoor() throws IOException {
        RetroManifest manifest = RetroManifest.load();
        ModelRules rules = new ModelRules(manifest.models());
        String paper = "vetsmod:item/retro/material/paper_";

        assertEquals("minecraft:item/paper", copySources(manifest).get(paper + "oak"));
        assertEquals(0x9C7F4E, copyTints(manifest).get(paper + "oak"));
        assertEquals(0x4B5C39, copyTints(manifest).get(paper + "willow"));
        for (String wood : List.of("oak", "dark", "sky", "dernic", "willow", "maple")) {
            assertEquals(
                    paper + wood,
                    rules.itemStub("minecraft:item/wynn/economy/woodcutting/paper_" + wood)
                            .orElseThrow()
                            .art(),
                    wood);
        }
        // Poplar's door came in 26.3, so willow, which stands for it, is a tint of oak's. Pine's
        // art is darker than any door's, so it is a tint of spruce's.
        assertFalse(textureInClientJar("minecraft:item/poplar_door"));
        assertEquals(
                "minecraft:item/spruce_door",
                copySources(manifest).get("vetsmod:item/retro/material/plank_pine"));
        assertEquals(
                "minecraft:item/oak_door",
                copySources(manifest).get("vetsmod:item/retro/material/plank_willow"));
    }

    @Test
    void everyItemModelRowOutsideTheTablesCoversAPinnedModel() throws IOException {
        Set<String> tableRows = new HashSet<>();
        realTables().itemModels().forEach(row -> tableRows.add(row.models().pattern()));
        for (ItemModel row : RetroManifest.load().models().itemModels()) {
            if (tableRows.contains(row.models().pattern())) continue;
            assertTrue(
                    PINNED_MODELS.keySet().stream()
                            .anyMatch(
                                    model ->
                                            row.models()
                                                    .matcher("minecraft:item/wynn/" + model)
                                                    .matches()),
                    row.models().pattern());
        }
    }

    @Test
    void everyTableFamilyHasAPinOfEachKind() throws IOException {
        // Each materials family pins a name drawn from vanilla's own art and one in a recipe's
        // colour, whichever it has; the content book pins one activity in every state.
        String wynn = "minecraft:item/wynn/";
        for (Map.Entry<?, ?> family : ((Map<?, ?>) realSections().get("materials")).entrySet()) {
            Map<?, ?> fields = (Map<?, ?>) family.getValue();
            String models = ((String) fields.get("models")).substring(wynn.length());
            Set<?> headings = new HashSet<>(fields.keySet());
            headings.remove("models");
            boolean vanilla = headings.remove("vanilla");
            List<Map.Entry<String, String>> pins =
                    PINNED_MODELS.entrySet().stream()
                            .filter(pin -> pin.getKey().startsWith(models))
                            .toList();
            assertTrue(
                    !vanilla
                            || pins.stream()
                                    .anyMatch(pin -> !pin.getValue().startsWith("vetsmod:")),
                    family.getKey() + " from vanilla's art");
            assertTrue(
                    headings.isEmpty()
                            || pins.stream().anyMatch(pin -> pin.getValue().startsWith("vetsmod:")),
                    family.getKey() + " in a recipe's colour");
        }
        for (String state : List.of("active", "tracked", "locked")) {
            assertTrue(PINNED_MODELS.containsKey("gui/content_book/raid_" + state), state);
        }
    }

    @Test
    void everyTableRowMatchesOneModelAndNoOtherRowDoes() throws IOException {
        // A table's row is one literal id, so an earlier row can never shadow it nor it another;
        // this also finds a name two families would both make.
        List<ItemModel> rows = RetroManifest.load().models().itemModels();
        for (ItemModel row : realTables().itemModels()) {
            String model = tableModel(row);
            assertEquals(
                    1,
                    rows.stream().filter(r -> r.models().matcher(model).matches()).count(),
                    model);
        }
    }

    @Test
    void everyTableRowDrawsArtThatExistsHeldAsItsItemIs() throws IOException {
        ModelRules rules = realModelRules();
        for (ItemModel row : realTables().itemModels()) {
            String model = tableModel(row);
            assertEquals(
                    ParentKind.GENERATED,
                    assertInstanceOf(ModelStub.TintProof.class, rules.itemStub(model).orElseThrow())
                            .parent(),
                    model);
            assertTrue(textureInClientJar(original(row.texture())), row.texture());
            assertHeldLikeItsItem(rules, model);
        }
    }

    @Test
    void theMaterialTablesExpandToACopyAndARowPerName() throws IOException {
        // A coloured name is a copy at material/<family>_<name> and a row on it; a vanilla name a
        // row on its texture and no copy.
        RetroManifest manifest = RetroManifest.load();
        String oil = "vetsmod:item/retro/material/oil_bass";

        assertEquals("minecraft:item/water_bucket", copySources(manifest).get(oil));
        assertEquals(0x345FDA, copyFroms(manifest).get(oil));
        assertEquals(0x7E9A50, copyTints(manifest).get(oil));
        assertTrue(copyHueOnly(manifest).contains(oil));
        assertEquals(
                oil,
                new ModelRules(manifest.models())
                        .itemStub("minecraft:item/wynn/economy/fishing/oil_bass")
                        .orElseThrow()
                        .art());
        assertFalse(copySources(manifest).containsKey("vetsmod:item/retro/material/oil_koi"));
    }

    @Test
    void theContentBookTableMakesEachActivitysThreeRowsAndTwoCopies() throws IOException {
        ItemTables tables = realTables();
        Set<String> book =
                tables.itemModels().stream()
                        .map(ShippedItemModelsTest::tableModel)
                        .filter(model -> model.contains("/content_book/"))
                        .collect(java.util.stream.Collectors.toSet());
        Map<?, ?> table = (Map<?, ?>) realSections().get("content_book_activities");
        int activities = ((Map<?, ?>) table.get("activities")).size();
        int states = ((Map<?, ?>) table.get("states")).size();
        assertEquals(activities * (1 + states), book.size(), "each active and in each state");

        RetroManifest manifest = RetroManifest.load();
        String tracked = "vetsmod:item/retro/book/raid_tracked";
        String locked = "vetsmod:item/retro/book/raid_locked";
        assertEquals("minecraft:item/red_dye", copySources(manifest).get(tracked));
        assertEquals(0x808080, copyFroms(manifest).get(tracked));
        assertEquals(0xFFFFFF, copyTints(manifest).get(tracked));
        assertEquals("minecraft:item/red_dye", copySources(manifest).get(locked));
        assertNull(copyFroms(manifest).get(locked));
        assertEquals(0x707070, copyTints(manifest).get(locked));
    }

    @Test
    void everyItemModelRowNamesAnAtlasTextureOrAnItemModel() throws IOException {
        // The folder comes before any $n, so the template already shows the stub's atlas, or
        // that its model is a vanilla item's or block's or one of vetsmod's own retro models.
        for (ItemModel row : RetroManifest.load().models().itemModels()) {
            if (row.model() != null) {
                assertTrue(
                        row.model().startsWith("minecraft:item/")
                                || row.model().startsWith("minecraft:block/")
                                || row.model().startsWith("vetsmod:item/retro/"),
                        row.model());
            } else {
                assertTrue(Atlas.of(row.texture()).isPresent(), row.texture());
            }
        }
    }

    @Test
    void pinnedModelsGetTheirVanillaItemFromTheClientJar() throws IOException {
        ModelRules rules = realModelRules();
        for (Map.Entry<String, String> pinned : PINNED_MODELS.entrySet()) {
            String model = "minecraft:item/wynn/" + pinned.getKey();
            String shown = inFull(pinned.getValue());
            Optional<ModelStub> stub = rules.itemStub(model);

            assertTrue(stub.isPresent(), model);
            assertEquals(shown, stub.get().art(), model);
            if (stub.get() instanceof ModelStub.TintProof tintProof) {
                assertEquals(Atlas.of(shown), Optional.of(tintProof.atlas()), model);
                // A copy's original is in the client jar; the copy itself is the pack's.
                assertTrue(textureInClientJar(original(shown)), shown);
            } else {
                assertTrue(
                        onClasspath(shown, "models", ".json")
                                || generatedAccessoryModels().contains(shown),
                        shown);
            }
        }
    }

    @Test
    void everyStubIsHeldLikeTheVanillaItemItShows() throws IOException {
        // A stub is held as minecraft:item/<kind>, so that must be the model that positions the
        // vanilla item whose icon its art is (item/<name>, for a block texture as for an item
        // one: the poppy's icon is block/poppy): the item's own model when it carries a
        // display (the bow), otherwise its parent (item/handheld, item/handheld_rod or
        // item/generated). A model stub parents the vanilla item's model itself, so it is held as
        // the item is.
        ModelRules rules = realModelRules();
        for (String pinned : new TreeSet<>(PINNED_MODELS.keySet())) {
            assertHeldLikeItsItem(rules, "minecraft:item/wynn/" + pinned);
        }
    }

    @Test
    void everyStubParentShipsWithABlankFromItsArtsAtlas() throws IOException {
        // A stub drawing from two atlases sends Wynncraft's whole item definition to the
        // missing model, so the parent's tint layer must come from the atlas its name promises.
        for (ParentKind kind : ParentKind.values()) {
            for (Atlas atlas : Atlas.values()) {
                ModelStub.TintProof stub =
                        new ModelStub.TintProof(
                                "minecraft:" + atlas.folder() + "/stone", kind, atlas);
                JsonObject parent = model(stub.parentModel());
                JsonObject textures = parent.getAsJsonObject("textures");
                String where = stub.parentModel();

                assertEquals(
                        "minecraft:item/" + kind.yamlName(),
                        Json.stringOrEmpty(parent, "parent"),
                        where);
                assertEquals(stub.blank(), Json.stringOrEmpty(textures, "layer0"), where);
                assertEquals("#art", Json.stringOrEmpty(textures, "layer1"), where);
                assertEquals("#art", Json.stringOrEmpty(textures, "particle"), where);
                assertEquals(Optional.of(atlas), Atlas.of(stub.blank()), where);
            }
        }
    }

    @Test
    void theBlanksAreFullyTransparent() throws IOException {
        for (Atlas atlas : Atlas.values()) {
            String file = "/assets/vetsmod/textures/" + atlas.folder() + "/retro/blank.png";
            try (InputStream in = ShippedItemModelsTest.class.getResourceAsStream(file)) {
                assertNotNull(in, file);
                BufferedImage blank = ImageIO.read(in);

                assertEquals(16, blank.getWidth(), file);
                assertEquals(16, blank.getHeight(), file);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(0, blank.getRGB(x, y) >>> 24, file + " at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void modelsNoRowCoversKeepWynncraftsModel() throws IOException {
        // Materials with no 2013 armour, Wynncraft's own armour, cosmetics, potions (which the
        // potions rows cover instead), and the models Wynntils reads itself.
        ModelRules rules = realModelRules();
        List<String> covered = new ArrayList<>();
        for (String model :
                List.of(
                        "minecraft:item/wynn/armor/boots/copper_boots",
                        "minecraft:item/wynn/armor/chestplate/pale_copper_chestplate",
                        "minecraft:item/wynn/armor/helmet/quartz_helmet",
                        "minecraft:item/wynn/armor/leggings/hidden_leggings",
                        "minecraft:item/wynn/armor/chestplate/tan_chestplate",
                        "minecraft:item/wynn/armor/helmet/shaman_helmet",
                        "minecraft:item/wynn/potion/mana_full",
                        "minecraft:item/wynn/skin/hat/adventurer_hat",
                        "minecraft:item/wynn/accessory/ring/ring_base_a",
                        "minecraft:item/wynn/ingredient/gold_nugget",
                        "minecraft:item/wynn/ingredient/skeleton_key",
                        "minecraft:item/wynn/dungeon/fragment/decrepit_sewers",
                        "minecraft:item/wynn/dungeon/token/ice_barrows",
                        "minecraft:item/wynn/prop/quest_scroll",
                        "minecraft:item/wynn/skin/dagger/frying_pan",
                        "minecraft:item/wynn/outer_void/drop/frying_pan",
                        "minecraft:item/wynn/gui/item_upgrader/scrolls",
                        "minecraft:item/wynn/gui/trade_market/filter/scroll",
                        "minecraft:item/wynn/gui/trade_market/filter/food",
                        "minecraft:item/wynn/gui/lootrun/boon/loot",
                        "minecraft:item/wynn/loot/cache",
                        "minecraft:item/wynn/pouch/ingredient_add",
                        "minecraft:item/wynn/pouch/ingredient_default",
                        "minecraft:item/wynn/pouch/emerald_full",
                        "minecraft:item/wynn/rune/blank",
                        "minecraft:item/wynn/gui/layer/tier/fade",
                        "minecraft:item/oak_boat",
                        "minecraft:w1000")) {
            if (rules.itemStub(model).isPresent()) covered.add(model);
        }

        assertEquals(List.of(), covered);
    }

    @Test
    void everyIngredientModelIsInTheClientJar() throws IOException {
        for (Map.Entry<String, String> row :
                RetroManifest.load().models().ingredients().entrySet()) {
            if (row.getValue().equals(RetroManifest.KEEP)) continue;
            if (row.getValue().startsWith("vetsmod:")) {
                assertTrue(generatedAccessoryModels().contains(row.getValue()), row.toString());
            } else {
                assertTrue(onClasspath(row.getValue(), "models", ".json"), row.toString());
            }
        }
    }

    @Test
    void ingredientsWithNoVanillaParentGetTheirLegacyIconOrKeepWynncrafts() throws IOException {
        // Pinned from Wynncraft pack a43ae4e6, whose models for these parent no vanilla model,
        // and from the legacy icons in Wynncraft's item database.
        ModelRules rules = realModelRules();

        assertEquals(
                Optional.of("minecraft:item/enderman_spawn_egg"),
                rules.ingredientParent("aspect_of_the_void"),
                "legacy 383:58, Wynncraft's model parents the missing item/spawn_egg");
        assertEquals(
                Optional.of("minecraft:item/egg"),
                rules.ingredientParent("penguin_egg"),
                "legacy 383:0, a spawn egg naming no mob");
        assertEquals(
                Optional.of("minecraft:item/light_blue_dye"),
                rules.ingredientParent("rocky_spirit"),
                "legacy 351:12, Wynncraft's model parents its own powder art");
        assertEquals(
                Optional.of("minecraft:item/golden_chestplate"),
                rules.ingredientParent("gilded_regalia"));
        assertEquals(Optional.of("minecraft:item/elytra"), rules.ingredientParent("cursed_wings"));
        assertEquals(
                Optional.of("vetsmod:item/retro/accessory/necklace_netherite"),
                rules.ingredientParent("burial_talisman"));
        assertEquals(Optional.of("minecraft:item/rabbit_hide"), rules.ingredientParent("fox_coat"));
        assertEquals(
                rules.itemStub("minecraft:item/wynn/ingredient/liff_leaf").orElseThrow(),
                rules.modelStub("minecraft:item/wynn/ingredient/liff_leaf").orElseThrow(),
                "no row of its own, so an item_models row reaches it");
        for (String kept : List.of("butterfly_buckler", "router_shield")) {
            assertEquals(Optional.empty(), rules.ingredientParent(kept), kept);
        }
    }

    @Test
    void everyPlantIngredientFallsThroughToItsGreenCopy() throws IOException {
        // These stems have no row in retro_ingredients.yml, so item_models reaches them. A row
        // added there would silently shadow the green copy; move the stem here if that is meant.
        Map<String, String> greenCopies =
                Map.ofEntries(
                        entry("alga", "vine"),
                        entry("algae_mat", "vine"),
                        entry("ancient_moss", "vine"),
                        entry("bioluminescent_moss", "vine"),
                        entry("creepvine_cluster", "vine"),
                        entry("thick_vines", "vine"),
                        entry("decomposing_snapdragon", "fern"),
                        entry("etheric_fern", "fern"),
                        entry("liff_leaf", "fern"),
                        entry("pristine_liff_leaf", "fern"),
                        entry("munched_grass", "fern"),
                        entry("secret_herb", "fern"),
                        entry("sticky_fern", "fern"),
                        entry("undiscovered_plant", "fern"),
                        entry("viral_tentacle", "fern"),
                        entry("canyon_parsley", "large_fern_top"),
                        entry("illicit_helmlock", "large_fern_top"),
                        entry("mega_fern", "large_fern_top"),
                        entry("water_dropwort", "large_fern_top"),
                        entry("hunters_disguise", "short_grass"),
                        entry("waterfall_thyme", "short_grass"),
                        entry("gert_skin", "lily_pad"),
                        entry("green_fin", "lily_pad"),
                        entry("green_foot", "lily_pad"),
                        entry("green_scale", "lily_pad"),
                        entry("sludge_parasite", "lily_pad"));
        ModelRules rules = realModelRules();
        Map<String, String> ingredients = RetroManifest.load().models().ingredients();

        assertEquals(26, greenCopies.size());
        greenCopies.forEach(
                (stem, copy) -> {
                    assertEquals(
                            Optional.of(
                                    new ModelStub.TintProof(
                                            "vetsmod:block/retro/green/" + copy,
                                            ParentKind.GENERATED,
                                            Atlas.BLOCKS)),
                            rules.modelStub("minecraft:item/wynn/ingredient/" + stem),
                            stem);
                    assertFalse(ingredients.containsKey(stem), stem + " has a row of its own");
                });
    }

    @Test
    void theGearBoxChestIsVanillasChestModelDrawnFromOneAtlas() throws IOException {
        // vetsmod:item/retro/chest is 1.21.11's ChestModel (createSingleBodyLayer) as block-model
        // elements, in a template both chests share, held as vanilla's chest item is
        // (item/template_chest), every face on the chest's copy in the blocks atlas.
        JsonObject chest = model("vetsmod:item/retro/chest");
        JsonObject textures = chest.getAsJsonObject("textures");
        String copy = "vetsmod:block/retro/chest";
        String templateId = "vetsmod:item/retro/template_retro_chest";
        JsonObject template = model(templateId);

        assertEquals(templateId, Json.stringOrEmpty(chest, "parent"));
        assertEquals(
                templateId, Json.stringOrEmpty(model("vetsmod:item/retro/ender_chest"), "parent"));
        assertEquals("minecraft:item/template_chest", Json.stringOrEmpty(template, "parent"));
        assertFalse(template.has("textures"), "each chest names its own");
        assertTrue(model("minecraft:item/template_chest").has("display"), "the chest's display");
        assertEquals(copy, Json.stringOrEmpty(textures, "chest"));
        assertEquals(copy, Json.stringOrEmpty(textures, "particle"));
        assertEquals(2, textures.size());
        assertEquals("minecraft:entity/chest/normal", original(copy));

        // The bottom, the lid and the latch, as ChestModel places them (the lid's offset added).
        List<String> boxes = new ArrayList<>();
        for (JsonElement element : template.getAsJsonArray("elements")) {
            JsonObject box = element.getAsJsonObject();
            boxes.add(box.get("from") + " " + box.get("to"));
        }
        assertEquals(
                List.of("[1,0,1] [15,10,15]", "[1,9,1] [15,14,15]", "[7,7,15] [9,11,16]"), boxes);

        // Every face draws #chest from a rectangle of the 64x64 texture as big as the face, so
        // nothing is stretched, and every pixel there is opaque, so no face samples the empty
        // parts of the chest's layout.
        BufferedImage texture = texture("minecraft:entity/chest/normal");
        assertEquals(64, texture.getWidth());
        assertEquals(64, texture.getHeight());
        double pixels = texture.getWidth() / 16.0;
        for (JsonElement element : template.getAsJsonArray("elements")) {
            JsonObject box = element.getAsJsonObject();
            double[] size = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                size[axis] =
                        box.getAsJsonArray("to").get(axis).getAsDouble()
                                - box.getAsJsonArray("from").get(axis).getAsDouble();
            }
            JsonObject faces = box.getAsJsonObject("faces");
            assertEquals(Set.of("north", "east", "south", "west", "up", "down"), faces.keySet());
            for (String face : faces.keySet()) {
                JsonObject drawn = faces.getAsJsonObject(face);
                String where = box.get("from") + " " + face;
                assertEquals("#chest", Json.stringOrEmpty(drawn, "texture"), where);
                assertFalse(drawn.has("tintindex"), where + ": no tint reaches it");
                double[] uv = new double[4];
                for (int i = 0; i < 4; i++) {
                    uv[i] = drawn.getAsJsonArray("uv").get(i).getAsDouble() * pixels;
                }
                boolean side = !face.equals("up") && !face.equals("down");
                double across = face.equals("east") || face.equals("west") ? size[2] : size[0];
                double down = side ? size[1] : size[2];
                assertEquals(across, Math.abs(uv[2] - uv[0]), 1e-9, where + " width");
                assertEquals(down, Math.abs(uv[3] - uv[1]), 1e-9, where + " height");
                int left = (int) Math.min(uv[0], uv[2]);
                int top = (int) Math.min(uv[1], uv[3]);
                for (int y = top; y < (int) Math.max(uv[1], uv[3]); y++) {
                    for (int x = left; x < (int) Math.max(uv[0], uv[2]); x++) {
                        assertEquals(
                                0xFF, texture.getRGB(x, y) >>> 24, where + " at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void theGearBoxesAreTheChestInTheGuiAndInTheHand() throws IOException {
        // Wynncraft draws a gear box from gui/loot/<tier> in the GUI and loot/<tier> elsewhere
        // (its potion.json selects on the display context); set boxes draw loot/set throughout.
        ModelRules rules = realModelRules();
        ModelStub chest = new ModelStub.ItemParent("vetsmod:item/retro/chest");
        for (String tier : List.of("unique", "rare", "legendary", "fabled", "mythic", "set")) {
            for (String folder : List.of("gui/loot/", "loot/")) {
                String model = "minecraft:item/wynn/" + folder + tier;
                assertEquals(Optional.of(chest), rules.itemStub(model), model);
            }
        }
    }

    @Test
    void eachPowderIsTheDyeWynncraftsOwnItemsSendToIt() throws IOException {
        // Wynncraft's items/<colour>_dye.json send each dye item to a powder (a43ae4e6); potion
        // stacks draw the same models. Both sizes of an element have a dye of their own.
        ModelRules rules = realModelRules();
        Map<String, String> dyes =
                Map.ofEntries(
                        entry("small_air", "gray_dye"),
                        entry("large_air", "light_gray_dye"),
                        entry("small_earth", "lime_dye"),
                        entry("large_earth", "green_dye"),
                        entry("small_thunder", "yellow_dye"),
                        entry("large_thunder", "orange_dye"),
                        entry("small_water", "light_blue_dye"),
                        entry("large_water", "cyan_dye"),
                        entry("small_fire", "pink_dye"),
                        entry("large_fire", "red_dye"));
        for (Map.Entry<String, String> powder : dyes.entrySet()) {
            String model = "minecraft:item/wynn/powder/" + powder.getKey();
            String dye = "minecraft:item/" + powder.getValue();
            assertEquals(
                    Optional.of(new ModelStub.TintProof(dye, ParentKind.GENERATED, Atlas.ITEMS)),
                    rules.itemStub(model),
                    model);
            assertTrue(textureInClientJar(dye), dye);
        }
    }
}
