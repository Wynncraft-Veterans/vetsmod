package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.rendering.image.TestImages.filled;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.PngCodec;
import org.wynnvets.retro.RetroReport.Count;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.pack.MemoryPack;
import org.wynnvets.retro.rules.KeepReason;
import org.wynnvets.retro.rules.RetroAssetIds;
import org.wynnvets.retro.rules.TextureDecision;

/**
 * Tests for {@link DrawnArt} and what {@link TextureStep} does with it, over rule-file fixtures and
 * packs held in memory: one for vanilla, the only source, and one for Wynncraft, above it.
 */
class DrawnArtTest {

    private static final byte[] PNG = PngCodec.png(filled(2, 2, 0xFF808080));

    /** Three parts, the catch-all of {@code items} last, and the rows these tests stub with. */
    private static final String RULES =
            """
            sources:
              '.*': [vanilla]
            parts:
              weapons: {about: 'w', models: ['minecraft:item/wynn/weapon/.*']}
              crafting: {about: 'c', models: ['minecraft:item/wynn/ingredient/.*']}
              potions: {about: 'p', models: ['minecraft:item/wynn/potion/.*']}
              items:
                about: 'i'
                models: ['minecraft:item/wynn/.*']
                textures: ['minecraft:item/.*']
            same_name:
              - 'minecraft:item/.*'
            item_models:
              'minecraft:item/wynn/weapon/spear': {texture: 'minecraft:item/iron_shovel', parent: handheld}
              'minecraft:item/wynn/ingredient/stubbed': 'minecraft:item/stubbed_art'
              'minecraft:item/wynn/rune/az': {model: 'minecraft:item/music_disc_wait'}
            """;

    private final MemoryPack vanilla = new MemoryPack("vanilla");
    private final MemoryPack wynncraft = new MemoryPack("wynncraft");

    private GenerationContext gen(Set<RetroPart> on, String extra) {
        return gen(ManifestFixtures.parseTextures(RULES + extra), on);
    }

    private GenerationContext gen(RetroManifest manifest, Set<RetroPart> on) {
        return new GenerationContext(
                manifest,
                List.of(vanilla, wynncraft),
                1,
                ProgrammerArtSources.open(null, null, vanilla),
                new PackOutput(),
                on);
    }

    private static Set<RetroPart> only(RetroPart... parts) {
        return RetroPart.allBut(RetroPart.allBut(Set.of(parts)));
    }

    /** A texture vanilla has and Wynncraft ships, so a copy of it is always written. */
    private void shipped(String textureId) {
        vanilla.texture(textureId, PNG);
        wynncraft.texture(textureId, PNG);
    }

    /** A model of Wynncraft's item folder, with the text of its JSON. */
    private void model(String path, String json) {
        wynncraft.file("minecraft:models/item/wynn/" + path + ".json", json);
    }

    /** A flat vanilla item: its item definition, its model and that model's one texture. */
    private void vanillaItem(String item) {
        vanilla.file(
                "minecraft:items/" + item + ".json",
                "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/"
                        + item
                        + "\"}}");
        vanilla.file(
                "minecraft:models/item/" + item + ".json",
                "{\"textures\": {\"layer0\": \"minecraft:item/" + item + "\"}}");
    }

    private static byte[] file(GenerationContext gen, String file) {
        return gen.out.files.get(Identifier.parse(file));
    }

    @Test
    void withOnlyWeaponsOnTheArtTheirStubsDrawIsStillReplaced() {
        shipped("minecraft:item/iron_shovel");
        shipped("minecraft:item/feather");
        model("weapon/spear", "{}");
        GenerationContext gen = gen(only(RetroPart.WEAPONS), "");

        new TextureStep().run(gen);

        assertNotNull(file(gen, "minecraft:textures/item/iron_shovel.png"));
        assertNull(file(gen, "minecraft:textures/item/feather.png"), "items is off");
        assertEquals(
                new TextureDecision.Keep(KeepReason.PART_OFF),
                gen.out.decisions.get("minecraft:item/feather"));
        assertEquals(1, gen.out.counts.get(Count.PARTS_OFF));
        assertEquals(List.of(), gen.out.skipped);
    }

    @Test
    void anOffPartsOwnArtStaysWynncraftsThoughItemsIsOn() {
        shipped("minecraft:item/gold_nugget");
        shipped("minecraft:item/feather");
        vanilla.file(
                "minecraft:models/item/gold_nugget.json",
                "{\"textures\": {\"layer0\": \"minecraft:item/gold_nugget\"}}");
        model("ingredient/nugget", "{\"parent\": \"minecraft:item/gold_nugget\"}");
        GenerationContext gen = gen(RetroPart.allBut(Set.of(RetroPart.CRAFTING)), "");

        new TextureStep().run(gen);

        assertNull(file(gen, "minecraft:textures/item/gold_nugget.png"));
        assertNotNull(file(gen, "minecraft:textures/item/feather.png"));
    }

    @Test
    void anOnPartDrawingTheSameArtReplacesItEverywhere() {
        shipped("minecraft:item/iron_shovel");
        vanilla.file(
                "minecraft:models/item/iron_shovel.json",
                "{\"textures\": {\"layer0\": \"minecraft:item/iron_shovel\"}}");
        model("weapon/spear", "{}");
        model("ingredient/shovel", "{\"parent\": \"minecraft:item/iron_shovel\"}");
        GenerationContext gen = gen(only(RetroPart.WEAPONS, RetroPart.ITEMS), "");

        new TextureStep().run(gen);

        assertNotNull(file(gen, "minecraft:textures/item/iron_shovel.png"));
    }

    @Test
    void aModelsOwnTexturesAreFollowedThroughItsParents() {
        model(
                "ingredient/leaf",
                """
                {
                  "parent": "item/middle",
                  "textures": {
                    "layer0": "minecraft:item/own",
                    "layer1": "item/no_namespace",
                    "layer2": "#layer0",
                    "layer3": {"sprite": "minecraft:item/in_an_object"}
                  }
                }
                """);
        vanilla.file(
                "minecraft:models/item/middle.json",
                "{\"parent\": \"builtin/generated\","
                        + " \"textures\": {\"layer4\": \"minecraft:item/from_the_parent\"}}");

        DrawnArt art = DrawnArt.of(gen(RetroPart.allBut(Set.of(RetroPart.CRAFTING)), ""));

        assertEquals(
                Set.of(
                        "minecraft:item/own",
                        "minecraft:item/no_namespace",
                        "minecraft:item/in_an_object",
                        "minecraft:item/from_the_parent"),
                art.off());
        assertEquals(Set.of(), art.on());
    }

    @Test
    void aModelsSlotHidesTheSameSlotOfItsParent() {
        model(
                "ingredient/chunk",
                """
                {
                  "parent": "minecraft:item/grass",
                  "textures": {"top": "minecraft:item/green_top", "bottom": "#top"}
                }
                """);
        vanilla.file(
                "minecraft:models/item/grass.json",
                "{\"textures\": {\"top\": \"minecraft:block/grass_top\","
                        + " \"bottom\": \"minecraft:block/dirt\","
                        + " \"side\": \"minecraft:block/grass_side\"}}");

        DrawnArt art = DrawnArt.of(gen(RetroPart.allBut(Set.of(RetroPart.CRAFTING)), ""));

        assertEquals(Set.of("minecraft:item/green_top", "minecraft:block/grass_side"), art.off());
    }

    @Test
    void aModelsParticleIsNotArtItDraws() {
        vanilla.file(
                "minecraft:models/item/parent.json",
                "{\"textures\": {\"particle\": \"minecraft:block/soul_sand\"}}");
        model("ingredient/head", "{\"parent\": \"minecraft:item/parent\"}");
        model(
                "ingredient/both",
                "{\"textures\": {\"layer0\": \"minecraft:item/own\","
                        + " \"particle\": \"minecraft:block/gravel\"}}");

        DrawnArt art = DrawnArt.of(gen(only(RetroPart.CRAFTING), ""));

        assertEquals(Set.of("minecraft:item/own"), art.on());
    }

    @Test
    void aStubbedModelThatIsOffDrawsItsOwnArtAndOnItsStubs() {
        model("ingredient/stubbed", "{\"textures\": {\"layer0\": \"minecraft:item/own\"}}");

        DrawnArt off = DrawnArt.of(gen(RetroPart.allBut(Set.of(RetroPart.CRAFTING)), ""));
        DrawnArt on = DrawnArt.of(gen(only(RetroPart.CRAFTING), ""));

        assertEquals(Set.of("minecraft:item/own"), off.off());
        assertEquals(Set.of(), off.on());
        assertEquals(Set.of("minecraft:item/stubbed_art"), on.on(), "not what its own model names");
        assertEquals(Set.of(), on.off());
    }

    @Test
    void aStubOfAModelDrawsThatModelsTextures() {
        model("rune/az", "{}");
        vanilla.file(
                "minecraft:models/item/music_disc_wait.json",
                "{\"textures\": {\"layer0\": \"minecraft:item/music_disc_wait\"}}");

        DrawnArt art = DrawnArt.of(gen(only(RetroPart.ITEMS), ""));

        assertEquals(Set.of("minecraft:item/music_disc_wait"), art.on());
    }

    @Test
    void aPotionsStubDrawsTheBottle() {
        model("potion/mana_full", "{}");

        DrawnArt art =
                DrawnArt.of(
                        gen(
                                only(RetroPart.POTIONS),
                                """
                                recipes:
                                  potion_overlay: {art: 'minecraft:item/potion_overlay'}
                                potions:
                                  mana: {colour: '#385DC6'}
                                """));

        assertEquals(Set.of("minecraft:item/potion"), art.on());
    }

    @Test
    void whatGearBoxesDrawByCodeIsTheirsWhileTheyAreOn() {
        vanillaItem("iron_helmet");
        vanillaItem("enchanted_book");
        String icons = "gear_box_icons:\n  'HELMET': 'minecraft:iron_helmet'\n";

        assertEquals(
                Set.of("minecraft:item/iron_helmet", "minecraft:item/enchanted_book"),
                DrawnArt.of(gen(only(RetroPart.GEAR_BOXES), icons)).on());
        assertEquals(Set.of(), DrawnArt.of(gen(only(RetroPart.ITEMS), icons)).on());
    }

    @Test
    void aStackModelsTargetIsItsRowsPartsWhileThatIsOn() {
        vanillaItem("nether_star");
        String row =
                """
                stack_models:
                  - {item: 'minecraft:potion', name: 'A', model: 'minecraft:nether_star', part: crafting}
                """;

        assertEquals(
                Set.of("minecraft:item/nether_star"),
                DrawnArt.of(gen(only(RetroPart.CRAFTING), row)).on());
        assertEquals(Set.of(), DrawnArt.of(gen(only(RetroPart.ITEMS), row)).on());
    }

    @Test
    void anItemDrawsEveryModelItsDefinitionCanPick() {
        vanilla.file(
                "minecraft:items/dial.json",
                """
                {
                  "model": {
                    "type": "minecraft:range_dispatch",
                    "entries": [
                      {"model": {"type": "minecraft:model", "model": "minecraft:item/dial_00"}},
                      {"model": {"type": "minecraft:model", "model": "minecraft:item/dial_01"}}
                    ],
                    "fallback": {"type": "minecraft:special", "base": "minecraft:item/dial_02",
                      "model": {"type": "minecraft:other"}}
                  }
                }
                """);
        for (String frame : List.of("00", "01", "02")) {
            vanilla.file(
                    "minecraft:models/item/dial_" + frame + ".json",
                    "{\"textures\": {\"layer0\": \"minecraft:item/dial_" + frame + "\"}}");
        }

        assertEquals(
                Set.of(
                        "minecraft:item/dial_00",
                        "minecraft:item/dial_01",
                        "minecraft:item/dial_02"),
                DrawnArt.itemArt(gen(RetroPart.all(), ""), "minecraft:dial"));
    }

    @Test
    void anItemThatCannotBeReadDrawsNothing() {
        vanilla.file("minecraft:items/broken.json", "{ this is no json");
        GenerationContext gen = gen(RetroPart.all(), "");

        assertEquals(Set.of(), DrawnArt.itemArt(gen, "minecraft:gone"));
        assertEquals(Set.of(), DrawnArt.itemArt(gen, "minecraft:broken"));
        assertEquals(1, gen.out.skipped.size(), "a gone file is no line, a broken one is");
    }

    @Test
    void everyItemTheCodeDrawsHasArtInTheClientJar() throws IOException {
        vanilla.overClasspath();
        RetroManifest manifest = RetroManifest.load();
        GenerationContext gen = gen(manifest, RetroPart.all());
        Set<String> items = new TreeSet<>(manifest.stacks().gearBoxIcons().values());
        manifest.stacks().stackModels().forEach(row -> items.add(row.model()));
        items.add(RetroAssetIds.GLINTING_BOOK);

        for (String item : items) {
            assertFalse(DrawnArt.itemArt(gen, item).isEmpty(), item);
        }
        assertEquals(List.of(), gen.out.skipped);
    }

    @Test
    void theClockDrawsAllItsFrames() throws IOException {
        vanilla.overClasspath();
        Set<String> art =
                DrawnArt.itemArt(gen(RetroManifest.load(), RetroPart.all()), "minecraft:clock");

        assertTrue(art.contains("minecraft:item/clock_00"));
        assertTrue(art.contains("minecraft:item/clock_63"));
    }

    @Test
    void theShieldDrawsItsRenderersArtAndNotItsModelsParticle() throws IOException {
        vanilla.overClasspath();
        GenerationContext gen = gen(RetroManifest.load(), RetroPart.all());

        assertEquals(
                Set.of("minecraft:entity/shield_base_nopattern"),
                DrawnArt.itemArt(gen, "minecraft:shield"));
        assertNotNull(
                DrawnArtTest.class.getResource(
                        "/assets/minecraft/textures/entity/shield_base_nopattern.png"));
    }

    @Test
    void whatTheShippedPartsDrawByCodeIsOnWithoutTheirOwners() throws IOException {
        vanilla.overClasspath();
        RetroManifest manifest = RetroManifest.load();

        assertTrue(
                DrawnArt.of(gen(manifest, only(RetroPart.CRAFTING)))
                        .on()
                        .contains(RetroAssetIds.SHIELD_ART),
                "the shields among the ingredients");
        assertTrue(
                DrawnArt.of(gen(manifest, only(RetroPart.GEAR_BOXES)))
                        .on()
                        .containsAll(Set.of(RetroAssetIds.SHIELD_ART, "minecraft:item/clock_00")),
                "the Allegiance tome's shield and the bracelet box's clock");
        assertEquals(Set.of(), DrawnArt.of(gen(manifest, only(RetroPart.HUD))).on());
    }

    @Test
    void aModelThatCannotBeReadAddsNothingAndCostsNoOther() {
        wynncraft.unreadable("minecraft:models/item/wynn/ingredient/unreadable.json");
        model("ingredient/broken", "{ this is no json");
        model("ingredient/orphan", "{\"parent\": \"minecraft:item/gone\"}");
        model("ingredient/fine", "{\"textures\": {\"layer0\": \"minecraft:item/fine\"}}");
        GenerationContext gen = gen(RetroPart.allBut(Set.of(RetroPart.CRAFTING)), "");

        DrawnArt art = DrawnArt.of(gen);

        assertEquals(Set.of("minecraft:item/fine"), art.off());
        assertEquals(2, gen.out.skipped.size(), "the unreadable and the broken; a gone file is no");
    }

    @Test
    void aCycleOfParentsEnds() {
        vanilla.file(
                "minecraft:models/item/a.json",
                "{\"parent\": \"minecraft:item/b\","
                        + " \"textures\": {\"layer0\": \"minecraft:item/a_art\"}}");
        vanilla.file(
                "minecraft:models/item/b.json",
                "{\"parent\": \"minecraft:item/a\","
                        + " \"textures\": {\"layer1\": \"minecraft:item/b_art\"}}");
        model("ingredient/loop", "{\"parent\": \"minecraft:item/a\"}");

        DrawnArt art = DrawnArt.of(gen(RetroPart.allBut(Set.of(RetroPart.CRAFTING)), ""));

        assertEquals(Set.of("minecraft:item/a_art", "minecraft:item/b_art"), art.off());
    }

    @Test
    void withEveryPartOnNothingIsWalkedAndTheRulesDecide() {
        shipped("minecraft:item/feather");
        wynncraft.unreadable("minecraft:models/item/wynn/ingredient/unreadable.json");
        GenerationContext gen = gen(RetroPart.all(), "");

        new TextureStep().run(gen);

        assertEquals(List.of(), gen.out.skipped, "no model was read");
        assertEquals(
                gen.textureRules.decideAll(Set.of("minecraft:item/feather"), gen.facts),
                gen.out.decisions);
        assertFalse(gen.out.counts.containsKey(Count.PARTS_OFF));
        assertNotNull(file(gen, "minecraft:textures/item/feather.png"));
    }

    @Test
    void withAPartOffTheModelsAreWalked() {
        wynncraft.unreadable("minecraft:models/item/wynn/ingredient/unreadable.json");
        GenerationContext gen = gen(RetroPart.allBut(Set.of(RetroPart.TOOLS)), "");

        new TextureStep().run(gen);

        assertTrue(gen.out.skipped.get(0).contains("ingredient/unreadable"));
    }
}
