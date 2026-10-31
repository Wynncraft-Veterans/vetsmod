package org.wynnvets.retro.manifest;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.CLIENT_JAR;
import static org.wynnvets.retro.manifest.ShippedFiles.EVERYTHING_BLANK;
import static org.wynnvets.retro.manifest.ShippedFiles.inFull;
import static org.wynnvets.retro.manifest.ShippedFiles.model;
import static org.wynnvets.retro.manifest.ShippedFiles.onClasspath;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;
import static org.wynnvets.retro.manifest.ShippedFiles.texture;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.rules.Atlas;
import org.wynnvets.retro.rules.KeepReason;
import org.wynnvets.retro.rules.PackFacts;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;

/**
 * Tests for the shipped texture rows ({@link TextureSections}) against the client jar: the
 * sources, the aliases, the variants, the tints and the decisions the rows give the textures
 * Wynncraft repaints. See {@link ShippedFiles ShippedFiles}.
 */
class ShippedTexturesTest {

    /**
     * Textures the {@code tints} rows replace, each with the decision
     * {@link ShippedFiles#CLIENT_JAR} gives it: where only vanilla has anything, every tint reads
     * its textures from vanilla.
     */
    static final Map<String, TextureDecision> PINNED_TINTS =
            Map.ofEntries(
                    entry("block/fern", tint("block/fern", new TintFactor.Auto())),
                    entry(
                            "block/large_fern_top",
                            tint("block/large_fern_top", new TintFactor.Auto())),
                    entry(
                            "block/variants/short_grass_2",
                            tint("block/short_grass", new TintFactor.Auto())),
                    entry("block/note_block", tint("block/note_block", match("block/oak_log"))),
                    entry("block/jukebox_side", tint("block/jukebox_side", match("block/oak_log"))),
                    entry("block/jukebox_top", tint("block/jukebox_top", match("block/oak_log"))),
                    entry("block/diorite", tint("block/diorite", match("block/quartz_block_side"))),
                    entry(
                            "block/polished_diorite",
                            tint("block/polished_diorite", match("block/quartz_block_side"))),
                    entry(
                            "block/red_nether_bricks",
                            tint("block/nether_bricks", match("block/red_nether_bricks"))),
                    entry(
                            "block/variants/dark_stone",
                            tint("block/stone", relative("block/stone"))),
                    entry(
                            "block/variants/dark_stone_1",
                            tint("block/stone", relative("block/stone"))),
                    entry(
                            "block/variants/dark_cobblestone_0",
                            tint("block/cobblestone", relative("block/cobblestone"))),
                    entry(
                            "block/variants/dark_stone_bricks",
                            tint("block/stone_bricks", relative("block/stone_bricks"))),
                    entry(
                            "block/variants/dark_cracked_stone_bricks_1",
                            tint(
                                    "block/cracked_stone_bricks",
                                    relative("block/cracked_stone_bricks"))),
                    entry(
                            "block/variants/dark_chiseled_stone_bricks",
                            tint(
                                    "block/chiseled_stone_bricks",
                                    relative("block/chiseled_stone_bricks"))),
                    entry(
                            "block/variants/dark_mossy_stone_bricks_2",
                            tint("block/mossy_stone_bricks", relative("block/mossy_stone_bricks"))),
                    entry(
                            "block/bedrock",
                            new TextureDecision.Tint(
                                    "minecraft:block/bedrock",
                                    RetroSource.VANILLA,
                                    match("block/black_concrete_powder"),
                                    0.7,
                                    1)),
                    entry("block/variants/molten_rock", tint("block/magma", new TintFactor.Auto())),
                    entry(
                            "block/variants/molten_rock_2",
                            tint("block/magma", new TintFactor.Auto())),
                    entry(
                            "block/furnace_side",
                            calmed("block/furnace_side", match("block/stone_bricks"), 0.45)),
                    entry(
                            "block/furnace_top",
                            calmed("block/furnace_top", match("block/stone_bricks"), 0.45)),
                    entry(
                            "block/dispenser_front_vertical",
                            like(
                                    "block/dispenser_front_vertical",
                                    "block/furnace_top",
                                    calmed(
                                            "block/furnace_top",
                                            match("block/stone_bricks"),
                                            0.45))),
                    entry(
                            "block/dropper_front_vertical",
                            like(
                                    "block/dropper_front_vertical",
                                    "block/furnace_top",
                                    calmed(
                                            "block/furnace_top",
                                            match("block/stone_bricks"),
                                            0.45))),
                    entry(
                            "block/furnace_front",
                            like(
                                    "block/furnace_front",
                                    "block/furnace_side",
                                    calmed(
                                            "block/furnace_side",
                                            match("block/stone_bricks"),
                                            0.45))),
                    entry(
                            "block/furnace_front_on",
                            like(
                                    "block/furnace_front_on",
                                    "block/furnace_side",
                                    calmed(
                                            "block/furnace_side",
                                            match("block/stone_bricks"),
                                            0.45))),
                    entry(
                            "block/dispenser_front",
                            like(
                                    "block/dispenser_front",
                                    "block/furnace_side",
                                    calmed(
                                            "block/furnace_side",
                                            match("block/stone_bricks"),
                                            0.45))),
                    entry(
                            "block/dropper_front",
                            like(
                                    "block/dropper_front",
                                    "block/furnace_side",
                                    calmed(
                                            "block/furnace_side",
                                            match("block/stone_bricks"),
                                            0.45))));

    /** Wynncraft's tooltip styles; each has a {@code _background} and a {@code _frame} sprite. */
    private static final List<String> TOOLTIP_STYLES =
            List.of(
                    "normal",
                    "unique",
                    "rare",
                    "legendary",
                    "fabled",
                    "mythic",
                    "crafted",
                    "normal_shiny",
                    "unique_shiny",
                    "rare_shiny",
                    "legendary_shiny",
                    "fabled_shiny",
                    "mythic_shiny",
                    "crafted_shiny",
                    "consumable",
                    "resource",
                    "profession_ingredient",
                    "profession_material");

    /** Wynncraft's variants of vanilla blocks, each with the vanilla block texture it becomes. */
    static final Map<String, String> PINNED_VARIANTS =
            Map.ofEntries(
                    entry("stone_0", "stone"),
                    entry("stone_1", "stone"),
                    entry("cobblestone_0", "cobblestone"),
                    entry("mossy_cobblestone_0", "mossy_cobblestone"),
                    entry("cracked_stone_bricks_1", "cracked_stone_bricks"),
                    entry("mossy_stone_bricks_2", "mossy_stone_bricks"),
                    entry("coal_ore_0", "coal_ore"),
                    entry("emerald_ore_0", "emerald_ore"),
                    entry("redstone_ore_0", "redstone_ore"),
                    entry("dirt_1", "dirt"),
                    entry("netherrack_1", "netherrack"),
                    entry("sandstone_0", "sandstone"),
                    entry("cobweb_2", "cobweb"),
                    entry("dead_leaves", "pale_oak_leaves"),
                    entry("dirt_top", "dirt"),
                    entry("dirt_top_0", "dirt"),
                    entry("grass_top_1", "grass_block_top"),
                    entry("sunflower_front", "sunflower_front"),
                    entry("sunflower_back", "sunflower_back"),
                    entry("sunflower_bottom_1", "sunflower_bottom"),
                    entry("pumpkin_bottom", "pumpkin_top"),
                    entry("oak_fence", "oak_planks"),
                    entry("oak_planks_flat", "oak_planks"),
                    entry("iron_bars_top", "iron_bars"),
                    entry("piston_bottom", "piston_top"),
                    entry("piston_inner", "piston_top"),
                    entry("piston_side_sticky", "piston_top"),
                    entry("piston_top_sticky", "piston_top"));

    /**
     * Vanilla's piston faces under {@code block/}, other than the plank face itself
     * ({@code piston_top}): Wynncraft repaints them all as a crate's.
     */
    private static final List<String> PISTON_FACES =
            List.of("piston_bottom", "piston_inner", "piston_side", "piston_top_sticky");

    /**
     * The armour pieces whose empty slot shows a placeholder item of Wynncraft's, drawn from
     * {@code minecraft:wynn/gui/slot/<piece>}, and whose slot silhouette vanilla has as
     * {@code minecraft:gui/sprites/container/slot/<piece>}.
     */
    private static final List<String> ARMOUR_PIECES =
            List.of("helmet", "chestplate", "leggings", "boots");

    /** A tint from vanilla's {@code minecraft:<from>}, at full strength and contrast. */
    private static TextureDecision tint(String from, TintFactor factor) {
        return calmed(from, factor, 1);
    }

    /** A tint from vanilla's {@code minecraft:<from>}, at full strength, with a contrast. */
    private static TextureDecision.Tint calmed(String from, TintFactor factor, double contrast) {
        return new TextureDecision.Tint(
                "minecraft:" + from, RetroSource.VANILLA, factor, 1, contrast);
    }

    /**
     * A like from vanilla's {@code minecraft:<from>} of {@code minecraft:<named>}, carrying {@code
     * decision}, the decision the named row makes for it: its strength and contrast, and that
     * decision itself.
     */
    private static TextureDecision like(String from, String named, TextureDecision.Tint decision) {
        return new TextureDecision.Tint(
                "minecraft:" + from,
                RetroSource.VANILLA,
                new TintFactor.Like("minecraft:" + named),
                decision.strength(),
                decision.contrast(),
                decision);
    }

    /** {@code {match: 'minecraft:<texture>'}}. */
    private static TintFactor match(String texture) {
        return new TintFactor.Match("minecraft:" + texture);
    }

    /** {@code {relative_to: 'minecraft:<texture>'}}. */
    private static TintFactor relative(String texture) {
        return new TintFactor.RelativeTo("minecraft:" + texture);
    }

    @Test
    void wynncraftsGreenPlantsTakeItsColourUnderTheBiomesTint() throws IOException {
        TextureRules rules = realTextureRules();

        for (String plant :
                List.of(
                        "fern",
                        "large_fern_top",
                        "large_fern_bottom",
                        "short_grass",
                        "tall_grass_top",
                        "tall_grass_bottom",
                        "vine",
                        "variants/short_grass_2")) {
            TextureDecision.Tint tint =
                    assertInstanceOf(
                            TextureDecision.Tint.class,
                            rules.decide("minecraft:block/" + plant, CLIENT_JAR),
                            plant);
            assertInstanceOf(TintFactor.Auto.class, tint.factor(), plant);
        }
    }

    @Test
    void theRealSourcesReadProgrammerArtFirstAndTheGuiFromVanilla() throws IOException {
        TextureRules rules = realTextureRules();
        List<RetroSource> all = List.of(RetroSource.PROGRAMMER_ART, RetroSource.VANILLA);
        List<RetroSource> current = List.of(RetroSource.PROGRAMMER_ART, RetroSource.VANILLA);

        assertEquals(all, rules.sourceOrder("minecraft:block/stone"));
        assertEquals(all, rules.sourceOrder("minecraft:item/stick"));
        assertEquals(current, rules.sourceOrder("minecraft:entity/chest/normal"));
        assertEquals(
                List.of(RetroSource.VANILLA),
                rules.sourceOrder("minecraft:gui/container/inventory"),
                "Programmer Art's GUI files would mix two looks");
        assertEquals(
                List.of(RetroSource.VANILLA),
                rules.sourceOrder("minecraft:gui/sprites/widget/button_highlighted"));
    }

    @Test
    void everyAliasRowCoversAPinnedTexture() throws IOException {
        // A row no Wynncraft texture matches is a typo or a stale name.
        List<String> pinned = new ArrayList<>();
        PINNED_VARIANTS.keySet().forEach(v -> pinned.add("minecraft:block/variants/" + v));
        PISTON_FACES.forEach(face -> pinned.add("minecraft:block/" + face));
        pinned.add("minecraft:block/purpur_block");
        pinned.add("minecraft:block/purpur_pillar_top");
        ARMOUR_PIECES.forEach(piece -> pinned.add("minecraft:wynn/gui/slot/" + piece));
        for (String style : TOOLTIP_STYLES) {
            pinned.add("minecraft:gui/sprites/tooltip/" + style + "_background");
            pinned.add("minecraft:gui/sprites/tooltip/" + style + "_frame");
        }
        for (TextureSections.Alias alias : RetroManifest.load().textures().aliases()) {
            assertTrue(
                    pinned.stream().anyMatch(id -> alias.textures().matcher(id).matches()),
                    alias.textures().pattern());
        }
    }

    @Test
    void everyWynncraftTooltipStyleGetsVanillasNineSliceTooltip() throws IOException {
        // The frame and background come with the .mcmeta beside them, which makes them
        // nine-slice sprites rather than one stretched image.
        TextureRules rules = realTextureRules();
        for (String part : List.of("background", "frame")) {
            String vanilla = "minecraft:gui/sprites/tooltip/" + part;
            TextureDecision expected = new TextureDecision.Copy(vanilla, RetroSource.VANILLA);
            assertTrue(onClasspath(vanilla, "textures", ".png.mcmeta"), vanilla + ".mcmeta");

            assertEquals(expected, rules.decide(vanilla, CLIENT_JAR), "Wynncraft repaints it");
            for (String style : TOOLTIP_STYLES) {
                String sprite = "minecraft:gui/sprites/tooltip/" + style + "_" + part;
                assertEquals(expected, rules.decide(sprite, CLIENT_JAR), sprite);
            }
        }
    }

    @Test
    void theContainerTexturesWynncraftRepaintsBecomeVanillas() throws IOException {
        TextureRules rules = realTextureRules();
        for (String name : List.of("generic_54", "hopper", "horse", "inventory")) {
            String texture = "minecraft:gui/container/" + name;
            assertEquals(
                    new TextureDecision.Copy(texture, RetroSource.VANILLA),
                    rules.decide(texture, CLIENT_JAR),
                    texture);
        }
    }

    @Test
    void emptyArmourSlotsShowVanillasGreySilhouettes() throws IOException {
        // Wynncraft (pack a43ae4e6) fills an empty armour slot with a placeholder item whose
        // model, item/wynn/gui/slot/<piece>, draws its orange wynn/gui/slot/<piece>, and blanks
        // the slot's own silhouette; both become vanilla's grey one. The accessory placeholders
        // have no vanilla silhouette, so their textures stay and their models become vetsmod's
        // outlines (PINNED_MODELS), and the offhand's shield stays blank under the panel grey
        // image_edits paints there.
        TextureRules rules = realTextureRules();
        for (String piece : ARMOUR_PIECES) {
            String silhouette = "minecraft:gui/sprites/container/slot/" + piece;
            TextureDecision vanilla = new TextureDecision.Copy(silhouette, RetroSource.VANILLA);

            assertEquals(
                    vanilla, rules.decide("minecraft:wynn/gui/slot/" + piece, CLIENT_JAR), piece);
            assertEquals(vanilla, rules.decide(silhouette, EVERYTHING_BLANK), silhouette);
        }
        for (String accessory : List.of("ring", "bracelet", "necklace")) {
            assertEquals(
                    new TextureDecision.Keep(KeepReason.NO_RULE),
                    rules.decide("minecraft:wynn/gui/slot/" + accessory, CLIENT_JAR),
                    accessory);
        }
        assertEquals(
                new TextureDecision.Keep(KeepReason.KEEP_WYNNCRAFT),
                rules.decide("minecraft:gui/sprites/container/slot/shield", CLIENT_JAR));
    }

    @Test
    void theHudRetroDrawsBecomesVanillasOverWynncraftsBlanks() throws IOException {
        // RetroHud draws vanilla's hearts, food and experience bar, which Wynncraft (pack
        // a43ae4e6) ships fully transparent, over the vanilla hotbar, which it repaints under its
        // ornate frame. A same_name copy beats the automatic keep of a blank, and the GUI reads
        // vanilla alone.
        TextureRules rules = realTextureRules();
        for (String sprite :
                List.of(
                        "hud/heart/container",
                        "hud/heart/half",
                        "hud/heart/full",
                        "hud/heart/poisoned_half",
                        "hud/heart/poisoned_full",
                        "widget/button",
                        "widget/button_highlighted",
                        "hud/food_empty",
                        "hud/food_half",
                        "hud/food_full",
                        "hud/experience_bar_background",
                        "hud/experience_bar_progress",
                        "hud/jump_bar_background",
                        "hud/jump_bar_cooldown",
                        "hud/jump_bar_progress",
                        "hud/hotbar",
                        "hud/hotbar_selection",
                        "hud/hotbar_offhand_left",
                        "hud/hotbar_offhand_right")) {
            String texture = "minecraft:gui/sprites/" + sprite;
            TextureDecision vanilla = new TextureDecision.Copy(texture, RetroSource.VANILLA);

            assertTrue(textureInClientJar(texture), texture);
            assertEquals(vanilla, rules.decide(texture, EVERYTHING_BLANK), texture);
            assertEquals(vanilla, rules.decide(texture, CLIENT_JAR), texture);
        }
    }

    @Test
    void wynncraftsOtherGuiSpritesStayItsOwn() throws IOException {
        // Its other HUD sprites too, which retro draws nothing new with: armour, air, a mount's
        // hearts and jump bar, the attack indicators, and the heart and food kinds RetroHud
        // never draws.
        TextureRules rules = realTextureRules();
        for (String sprite :
                List.of(
                        "hud/armor_full",
                        "hud/air",
                        "hud/heart/vehicle_full",
                        "hud/hotbar_attack_indicator_progress",
                        "hud/crosshair_attack_indicator_full",
                        "hud/heart/frozen_full",
                        "hud/food_full_hunger",
                        "container/slot_highlight_back",
                        "boss_bar/white_background")) {
            String texture = "minecraft:gui/sprites/" + sprite;
            assertEquals(
                    new TextureDecision.Keep(KeepReason.NO_RULE),
                    rules.decide(texture, CLIENT_JAR),
                    texture);
        }
    }

    @Test
    void pinnedVariantsBecomeTheirVanillaBlockFromTheClientJar() throws IOException {
        TextureRules rules = realTextureRules();
        for (Map.Entry<String, String> variant : PINNED_VARIANTS.entrySet()) {
            assertEquals(
                    new TextureDecision.Copy(
                            "minecraft:block/" + variant.getValue(), RetroSource.VANILLA),
                    rules.decide("minecraft:block/variants/" + variant.getKey(), CLIENT_JAR),
                    variant.getKey());
        }
    }

    @Test
    void variantsWithNoVanillaBlockKeepWynncraftsTexture() throws IOException {
        // Wynncraft's own blocks keep their art.
        TextureRules rules = realTextureRules();
        for (String variant :
                List.of(
                        "dead_grass_2",
                        "crops_wheat",
                        "chain",
                        "cauldron_side",
                        "enchanting_table_side",
                        "chiseled_red_sandstone_0",
                        "candle_flame",
                        "potted_cactus",
                        "books")) {
            assertEquals(
                    new TextureDecision.Keep(KeepReason.NO_RULE),
                    rules.decide("minecraft:block/variants/" + variant, CLIENT_JAR),
                    variant);
        }
    }

    @Test
    void theSixSidedPistonIsThePlankFaceOnEverySide() throws IOException {
        // Wynncraft's pistons are crates (pack a43ae4e6). Vanilla's piston blockstate, which it
        // leaves alone, draws block/piston, block/piston_base and the heads with vanilla's
        // piston textures, repainted; its own sticky_piston blockstate draws
        // variants/sticky_piston (bottom, platform, side) and variants/sticky_piston_base
        // (bottom, inside, side) with four textures of its own. Every one becomes the plank face,
        // which same_name makes 2013's.
        TextureRules rules = realTextureRules();
        TextureDecision plank =
                new TextureDecision.Copy("minecraft:block/piston_top", RetroSource.VANILLA);
        for (String face : PISTON_FACES) {
            assertEquals(plank, rules.decide("minecraft:block/" + face, CLIENT_JAR), face);
        }
        for (String face :
                List.of(
                        "piston_bottom",
                        "piston_inner",
                        "piston_side_sticky",
                        "piston_top_sticky")) {
            assertEquals(plank, rules.decide("minecraft:block/variants/" + face, CLIENT_JAR), face);
        }
        assertEquals(plank, rules.decide("minecraft:block/piston_top", CLIENT_JAR));
    }

    @Test
    void pinnedTexturesGetTheirTints() throws IOException {
        TextureRules rules = realTextureRules();
        for (Map.Entry<String, TextureDecision> pinned : PINNED_TINTS.entrySet()) {
            String texture = "minecraft:" + pinned.getKey();
            assertEquals(pinned.getValue(), rules.decide(texture, CLIENT_JAR), texture);
        }
    }

    @Test
    void everyTintRowCoversAPinnedTexture() throws IOException {
        // A row no Wynncraft or vanilla texture matches is a typo or a stale name.
        for (TextureSections.Tint tint : RetroManifest.load().textures().tints()) {
            assertTrue(
                    PINNED_TINTS.keySet().stream()
                            .anyMatch(id -> tint.textures().matcher("minecraft:" + id).matches()),
                    tint.textures().pattern());
        }
    }

    @Test
    void everyTextureATintReadsIsAVanillaBlockTexture() throws IOException {
        // Its source and its match texture come from the sources, where vanilla stands for
        // them all here; a relative_to texture is read from Wynncraft's pack or, without it,
        // vanilla's. A like also reads what the row it names reads for the texture it names.
        // Every one is in the blocks atlas, as the tinted textures are.
        for (TextureDecision decision : PINNED_TINTS.values()) {
            List<String> read = new ArrayList<>();
            for (TextureDecision.Tint tint = (TextureDecision.Tint) decision;
                    tint != null;
                    tint = tint.like()) {
                read.add(tint.from());
                switch (tint.factor()) {
                    case TintFactor.Match match -> read.add(match.texture());
                    case TintFactor.RelativeTo relative -> read.add(relative.texture());
                    case TintFactor.Colour colour -> {}
                    case TintFactor.Auto auto -> {}
                    case TintFactor.Unchanged unchanged -> {}
                    case TintFactor.Like like -> {}
                }
            }
            for (String texture : read) {
                assertTrue(textureInClientJar(texture), texture);
                assertEquals(Optional.of(Atlas.BLOCKS), Atlas.of(texture), texture);
            }
        }
    }

    @Test
    void theDarkStoneVariantsAreTheirStoneDarkenedAsWynncraftDarkenedIt() throws IOException {
        // Every dark variant of pack a43ae4e6, against Wynncraft's own copy of its stone.
        TextureRules rules = realTextureRules();
        Map<String, String> variants =
                Map.of(
                        "dark_stone_0", "stone",
                        "dark_cobblestone", "cobblestone",
                        "dark_cracked_stone_bricks_0", "cracked_stone_bricks",
                        "dark_cracked_stone_bricks", "cracked_stone_bricks",
                        "dark_mossy_stone_bricks", "mossy_stone_bricks",
                        "dark_mossy_stone_bricks_1", "mossy_stone_bricks");
        for (Map.Entry<String, String> variant : variants.entrySet()) {
            assertEquals(
                    tint("block/" + variant.getValue(), relative("block/" + variant.getValue())),
                    rules.decide("minecraft:block/variants/" + variant.getKey(), CLIENT_JAR),
                    variant.getKey());
        }
    }

    @Test
    void aTintBeatsTheSameNameCopy() throws IOException {
        // Under EVERYTHING_BLANK every source has every texture, so same_name would copy it; the
        // tint comes first. The block reads Programmer Art first.
        TextureRules rules = realTextureRules();

        assertEquals(
                new TextureDecision.Tint(
                        "minecraft:block/note_block",
                        RetroSource.PROGRAMMER_ART,
                        match("block/oak_log"),
                        1,
                        1),
                rules.decide("minecraft:block/note_block", EVERYTHING_BLANK));
    }

    @Test
    void skyWoodIsThePlainOakLogOnEveryFace() throws IOException {
        TextureRules rules = realTextureRules();

        for (String face : List.of("purpur_block", "purpur_pillar", "purpur_pillar_side")) {
            assertEquals(
                    new TextureDecision.Copy("minecraft:block/oak_log", RetroSource.PROGRAMMER_ART),
                    rules.decide("minecraft:block/" + face, EVERYTHING_BLANK),
                    face);
        }
        assertEquals(
                new TextureDecision.Copy("minecraft:block/oak_log_top", RetroSource.PROGRAMMER_ART),
                rules.decide("minecraft:block/purpur_pillar_top", EVERYTHING_BLANK));
    }

    @Test
    void furnacesDispensersAndDroppersAreCalmedAndTheLitFurnaceStaysStill() throws IOException {
        // The two plain faces take the 2013 stone bricks' colour and 0.45 of their contrast, and
        // the dispenser's, dropper's and furnace's fronts exactly the change of the face beside
        // them (PINNED_TINTS); every face, and the face a like names, reads Programmer Art
        // first, as a block does, ahead of same_name.
        TextureRules rules = realTextureRules();
        TextureDecision.Tint side =
                new TextureDecision.Tint(
                        "minecraft:block/furnace_side",
                        RetroSource.PROGRAMMER_ART,
                        match("block/stone_bricks"),
                        1,
                        0.45);
        assertEquals(
                new TextureDecision.Tint(
                        "minecraft:block/furnace_front_on",
                        RetroSource.PROGRAMMER_ART,
                        new TintFactor.Like("minecraft:block/furnace_side"),
                        1,
                        0.45,
                        side),
                rules.decide("minecraft:block/furnace_front_on", EVERYTHING_BLANK));
        assertEquals(side, rules.decide("minecraft:block/furnace_side", EVERYTHING_BLANK));
        assertEquals(
                new TextureDecision.Tint(
                        "minecraft:block/dispenser_front",
                        RetroSource.PROGRAMMER_ART,
                        new TintFactor.Like("minecraft:block/furnace_side"),
                        1,
                        0.45,
                        side),
                rules.decide("minecraft:block/dispenser_front", EVERYTHING_BLANK));
        // Only Wynncraft's lit furnace is animated (16x32, frametime 28, in a43ae4e6). Vanilla's,
        // like Programmer Art's, is one still frame with no .mcmeta, so the tint writes a still
        // texture and no .mcmeta, and Wynncraft's .mcmeta, in a pack below, is not read for it.
        assertFalse(onClasspath("minecraft:block/furnace_front_on", "textures", ".png.mcmeta"));
        BufferedImage lit = texture("minecraft:block/furnace_front_on");
        assertEquals(lit.getWidth(), lit.getHeight(), "one square frame");
    }

    @Test
    void eachDispenserAndDropperFrontIsLikeTheFaceItsModelDrawsBesideIt() throws IOException {
        // Wynncraft overrides none of these blockstates or models. A front facing sideways
        // (orientable) sits between furnace_side faces, one facing up or down (orientable_vertical)
        // among furnace_top faces; each model names that face its side.
        TextureRules rules = realTextureRules();
        for (String block :
                List.of("dispenser", "dropper", "dispenser_vertical", "dropper_vertical")) {
            JsonObject textures = model("minecraft:block/" + block).getAsJsonObject("textures");
            String front = inFull(textures.get("front").getAsString());
            String side = inFull(textures.get("side").getAsString());
            TextureDecision decision = rules.decide(front, CLIENT_JAR);
            assertInstanceOf(TextureDecision.Tint.class, decision, block);
            assertEquals(
                    new TintFactor.Like(side), ((TextureDecision.Tint) decision).factor(), block);
        }
    }

    @Test
    void deliberateBlanksAndRepurposedTexturesKeepWynncraftsTexture() throws IOException {
        // Every source has these, and same_name or an alias matches most of them.
        TextureRules rules = realTextureRules();
        for (String texture :
                List.of(
                        "block/tripwire",
                        "block/tripwire_hook",
                        "block/sponge",
                        "block/wet_sponge",
                        "block/daylight_detector_top",
                        "item/barrier",
                        "item/light_07",
                        "gui/sprites/container/slot/shield",
                        "particle/generic_0",
                        "particle/generic_1",
                        "particle/sga_k",
                        "mob_effect/haste",
                        "mob_effect/mining_fatigue",
                        "entity/trident",
                        "entity/iron_golem/iron_golem_crackiness_low",
                        "entity/equipment/humanoid/copper",
                        "entity/equipment/humanoid_leggings/copper",
                        "painting/bust",
                        "painting/back")) {
            assertEquals(
                    new TextureDecision.Keep(KeepReason.KEEP_WYNNCRAFT),
                    rules.decide("minecraft:" + texture, EVERYTHING_BLANK),
                    texture);
        }
    }

    @Test
    void theirNeighboursAreStillReplaced() throws IOException {
        // The keep rows name exactly what they keep. Wynncraft ships each of these blank in
        // EVERYTHING_BLANK, and same_name still wins over the automatic keep.
        TextureRules rules = realTextureRules();

        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:mob_effect/strength", RetroSource.PROGRAMMER_ART),
                rules.decide("minecraft:mob_effect/strength", EVERYTHING_BLANK));
        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:entity/iron_golem/iron_golem", RetroSource.PROGRAMMER_ART),
                rules.decide("minecraft:entity/iron_golem/iron_golem", EVERYTHING_BLANK));
        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:entity/equipment/humanoid/iron", RetroSource.PROGRAMMER_ART),
                rules.decide("minecraft:entity/equipment/humanoid/iron", EVERYTHING_BLANK),
                "only copper keeps Wynncraft's worn armour");
        assertEquals(
                new TextureDecision.Keep(KeepReason.TRANSPARENT),
                rules.decide("minecraft:gui/sprites/hud/armor_full", EVERYTHING_BLANK),
                "a HUD blank retro draws nothing with stays an automatic keep");
    }

    @Test
    void layeredEntitiesAreReplacedWholeOrNotAtAll() throws IOException {
        TextureRules rules = realTextureRules();
        // Programmer Art 1.21.11 has the villager body but not the profession layers.
        Set<String> programmerArt =
                Set.of(
                        "minecraft:entity/villager/villager",
                        "minecraft:entity/sheep/sheep",
                        "minecraft:entity/sheep/sheep_wool",
                        "minecraft:entity/wolf/wolf",
                        "minecraft:entity/wolf/wolf_collar");
        Set<String> wynncraft =
                Set.of(
                        "minecraft:entity/villager/villager",
                        "minecraft:entity/villager/profession/farmer");
        PackFacts facts =
                new PackFacts() {
                    @Override
                    public boolean wynncraftShips(String textureId) {
                        return wynncraft.contains(textureId);
                    }

                    @Override
                    public boolean wynncraftTransparent(String textureId) {
                        return false;
                    }

                    @Override
                    public boolean has(RetroSource source, String textureId) {
                        return switch (source) {
                            case PROGRAMMER_ART -> programmerArt.contains(textureId);
                            case VANILLA -> textureInClientJar(textureId);
                        };
                    }
                };

        Map<String, TextureDecision> decisions =
                rules.decideAll(
                        List.of(
                                "minecraft:entity/villager/villager",
                                "minecraft:entity/villager/profession/farmer",
                                "minecraft:entity/villager/farmer",
                                "minecraft:entity/sheep/sheep",
                                "minecraft:entity/sheep/sheep_wool",
                                "minecraft:entity/sheep/sheep_wool_undercoat",
                                "minecraft:entity/wolf/wolf",
                                "minecraft:entity/wolf/wolf_collar"),
                        facts);

        TextureDecision layerGroup = new TextureDecision.Keep(KeepReason.LAYER_GROUP);
        // Villagers come from vanilla whole, whose professions Programmer Art lacks. Wynncraft's
        // leftover top-level textures, which vanilla lacks, are not members, so they break nothing.
        assertEquals(
                new TextureDecision.Copy("minecraft:entity/villager/villager", RetroSource.VANILLA),
                decisions.get("minecraft:entity/villager/villager"));
        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:entity/villager/profession/farmer", RetroSource.VANILLA),
                decisions.get("minecraft:entity/villager/profession/farmer"));
        assertNotEquals(layerGroup, decisions.get("minecraft:entity/villager/farmer"));
        // Programmer Art has no undercoat, so the sheep stays vanilla throughout.
        assertEquals(layerGroup, decisions.get("minecraft:entity/sheep/sheep"));
        assertEquals(layerGroup, decisions.get("minecraft:entity/sheep/sheep_wool"));
        assertEquals(layerGroup, decisions.get("minecraft:entity/sheep/sheep_wool_undercoat"));
        // A wolf whose layers all come from Programmer Art keeps them.
        assertEquals(
                new TextureDecision.Copy("minecraft:entity/wolf/wolf", RetroSource.PROGRAMMER_ART),
                decisions.get("minecraft:entity/wolf/wolf"));
        assertEquals(
                new TextureDecision.Copy(
                        "minecraft:entity/wolf/wolf_collar", RetroSource.PROGRAMMER_ART),
                decisions.get("minecraft:entity/wolf/wolf_collar"));
    }

    @Test
    void moltenRockIsTheAnimated2013MagmaTintedLikeWynncrafts() throws IOException {
        // The double petrified oak slab and the old double wood slab draw Wynncraft's
        // variants/molten_rock(_0 to _2), animated strips; the tint reads magma, whose .mcmeta
        // goes with it, and keeps the strip whole.
        TextureRules rules = realTextureRules();
        for (String variant : List.of("molten_rock", "molten_rock_0", "molten_rock_1")) {
            assertEquals(
                    tint("block/magma", new TintFactor.Auto()),
                    rules.decide("minecraft:block/variants/" + variant, CLIENT_JAR),
                    variant);
        }
        assertTrue(onClasspath("minecraft:block/magma", "textures", ".png.mcmeta"));
        BufferedImage magma = texture("minecraft:block/magma");
        assertEquals(0, magma.getHeight() % magma.getWidth(), "a strip of square frames");
        assertTrue(magma.getHeight() > magma.getWidth(), "more than one frame");
    }
}
