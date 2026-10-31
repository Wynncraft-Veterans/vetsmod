package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyFroms;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyHueOnly;
import static org.wynnvets.retro.manifest.ManifestFixtures.copySources;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyTints;
import static org.wynnvets.retro.manifest.ShippedFiles.CLIENT_JAR;
import static org.wynnvets.retro.manifest.ShippedFiles.EVERYTHING_BLANK;
import static org.wynnvets.retro.manifest.ShippedFiles.model;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;
import static org.wynnvets.retro.manifest.ShippedFiles.texture;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.colors.VanillaGuiColors;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.manifest.AssetSections.Fill;
import org.wynnvets.retro.manifest.AssetSections.Frame;
import org.wynnvets.retro.rules.Atlas;
import org.wynnvets.retro.rules.KeepReason;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.ModelStub;
import org.wynnvets.retro.rules.RetroAssetIds;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;
import org.wynnvets.util.Json;

/**
 * Tests for the shipped asset rows ({@link AssetSections}) against the client jar: the source
 * copies and recipes, the image edits, the Wynntils frames and the language. See
 * {@link ShippedFiles ShippedFiles}.
 */
class ShippedAssetsTest {

    @Test
    void anOilTintsOnlyTheWater() throws IOException {
        Set<String> hueOnly = copyHueOnly(RetroManifest.load());

        assertTrue(hueOnly.contains("vetsmod:item/retro/material/oil_salmon"));
        assertTrue(hueOnly.contains("vetsmod:item/retro/material/gem_dernic"));
        assertFalse(hueOnly.contains("vetsmod:item/retro/material/ingot_cobalt"));
    }

    @Test
    void aRecipeGivesItsArtInAColourFromOneLine() throws IOException {
        Recipe bucket = RetroManifest.load().assets().recipes().get("bucket");
        int water = 0xFF345FDA;
        int rim = 0xFF808080;

        assertEquals(0xFF7E9A50, bucket.recolour(0x7E9A50).applyAsInt(water));
        assertEquals(rim, bucket.recolour(0x7E9A50).applyAsInt(rim), "the bucket stays a bucket");
    }

    @Test
    void everyRecipeIsUsed() throws IOException {
        RetroManifest manifest = RetroManifest.load();
        Set<Recipe> used = new HashSet<>();
        manifest.assets().sourceCopies().values().forEach(copy -> used.add(copy.recipe()));
        manifest.assets()
                .recipes()
                .forEach((name, recipe) -> assertTrue(used.contains(recipe), name));
    }

    @Test
    void theGreyPlantsAreBakedGreen() throws IOException {
        // These textures are grey and take their colour from the vanilla item's definition or the
        // biome; Wynncraft's items are potions, whose white tint leaves them grey. The colours are
        // vanilla's: the vine's and lily pad's constants, and colormap/grass.png at (127, 127).
        RetroManifest manifest = RetroManifest.load();
        assertEquals(
                Map.of(
                        "vine", 0x48B518,
                        "lily_pad", 0x71C35C,
                        "fern", 0x7CBD6B,
                        "large_fern_top", 0x7CBD6B,
                        "short_grass", 0x7CBD6B,
                        "grass_block_top", 0x7CBD6B,
                        "grass_block_side_overlay", 0x7CBD6B),
                copyTints(manifest).entrySet().stream()
                        .filter(tint -> tint.getKey().startsWith("vetsmod:block/retro/green/"))
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        tint ->
                                                tint.getKey()
                                                        .substring(
                                                                "vetsmod:block/retro/green/"
                                                                        .length()),
                                        Map.Entry::getValue)));
        for (String copy : copyTints(manifest).keySet()) {
            if (!copy.startsWith("vetsmod:block/retro/green/")) continue;
            String name = copy.substring(copy.lastIndexOf('/') + 1);
            assertEquals("minecraft:block/" + name, copySources(manifest).get(copy), copy);
        }

        ModelRules rules = new ModelRules(manifest.models());
        Map<String, List<String>> plants =
                Map.of(
                        "vine",
                        List.of(
                                "dungeon/fragment/undergrowth_ruins",
                                "ingredient/alga",
                                "ingredient/algae_mat",
                                "ingredient/ancient_moss",
                                "ingredient/bioluminescent_moss",
                                "ingredient/creepvine_cluster",
                                "ingredient/thick_vines"),
                        "fern",
                        List.of(
                                "ingredient/decomposing_snapdragon",
                                "ingredient/etheric_fern",
                                "ingredient/munched_grass",
                                "ingredient/secret_herb",
                                "ingredient/sticky_fern",
                                "ingredient/undiscovered_plant",
                                "ingredient/viral_tentacle"),
                        "large_fern_top",
                        List.of(
                                "ingredient/canyon_parsley",
                                "ingredient/illicit_helmlock",
                                "ingredient/mega_fern",
                                "ingredient/water_dropwort"),
                        "short_grass",
                        List.of("ingredient/hunters_disguise", "ingredient/waterfall_thyme"),
                        "lily_pad",
                        List.of(
                                "ingredient/gert_skin",
                                "ingredient/green_fin",
                                "ingredient/green_foot",
                                "ingredient/green_scale",
                                "ingredient/sludge_parasite"));
        for (Map.Entry<String, List<String>> plant : plants.entrySet()) {
            for (String stem : plant.getValue()) {
                ModelStub stub = rules.itemStub("minecraft:item/wynn/" + stem).orElseThrow();
                ModelStub.TintProof tintProof = assertInstanceOf(ModelStub.TintProof.class, stub);
                assertEquals("vetsmod:block/retro/green/" + plant.getKey(), tintProof.art(), stem);
            }
        }

        assertEquals(
                new ModelStub.ItemParent("vetsmod:item/retro/arable_chunk"),
                rules.itemStub("minecraft:item/wynn/outer_void/drop/arable_chunk").orElseThrow());
        JsonObject arable = model("vetsmod:item/retro/arable_chunk").getAsJsonObject("textures");
        for (String texture : List.of("top", "overlay")) {
            assertTrue(
                    copyTints(manifest).containsKey(Json.stringOrEmpty(arable, texture)), texture);
        }
    }

    @Test
    void everyOverhealthHeartIsACopyOfVanillasHeartOfItsFill() throws IOException {
        RetroManifest manifest = RetroManifest.load();

        for (String colour : RetroAssetIds.OVERHEALTH_COLOURS) {
            for (String fill : List.of(RetroAssetIds.HEART_FULL, RetroAssetIds.HEART_HALF)) {
                String texture = RetroAssetIds.overhealthTexture(colour, fill);
                assertEquals(
                        "minecraft:gui/sprites/hud/heart/" + fill,
                        copySources(manifest).get(texture),
                        texture);
            }
        }
    }

    @Test
    void theSourceCopiesAreTheKeysHookAndTheGearBoxesChest() throws IOException {
        // block/tripwire_hook is kept (its blockstate draws a blank, and Wynncraft ships no such
        // texture, so it stays today's vanilla hook), so the keys draw a copy of the 2013 one; the
        // chest is an entity texture, which no item atlas takes, so the gear boxes' chest model
        // draws a copy in the blocks atlas.
        RetroManifest manifest = RetroManifest.load();
        TextureRules rules = new TextureRules(manifest.textures());

        assertEquals(
                "minecraft:block/tripwire_hook",
                copySources(manifest).get("vetsmod:block/retro/tripwire_hook"));
        assertEquals(
                "minecraft:entity/chest/normal",
                copySources(manifest).get("vetsmod:block/retro/chest"));
        assertEquals(
                new TextureDecision.Keep(KeepReason.KEEP_WYNNCRAFT),
                rules.decide("minecraft:block/tripwire_hook", EVERYTHING_BLANK),
                "the hook a stub could not draw from its own id");
        for (Map.Entry<String, String> copy : copySources(manifest).entrySet()) {
            assertTrue(textureInClientJar(copy.getValue()), copy.getValue());
            // The HUD draws a GUI sprite itself; every other copy is for a stub.
            if (copy.getKey().startsWith("vetsmod:gui/sprites/")) continue;
            assertTrue(Atlas.of(copy.getKey()).isPresent(), copy.getKey());
        }
        assertEquals(
                List.of(
                        RetroSource.VANILLA_TWEAKS,
                        RetroSource.PROGRAMMER_ART,
                        RetroSource.VANILLA),
                rules.sourceOrder("minecraft:block/tripwire_hook"),
                "the keys' hook reads as any block texture");
        assertEquals(
                List.of(
                        RetroSource.VANILLA_TWEAKS,
                        RetroSource.PROGRAMMER_ART,
                        RetroSource.VANILLA),
                rules.sourceOrder("minecraft:entity/chest/normal"),
                "the chest reads as any entity texture");
    }

    @Test
    void everyImageEditFitsItsVanillaTextureAndPassesItsGuard() throws IOException {
        // The client jar has the 1.21.11 textures the edits are made for (Programmer Art has no
        // container textures, and the GUI reads vanilla only), so a rectangle the guard would
        // refuse fails here first.
        TextureRules rules = realTextureRules();
        Map<String, List<Fill>> edits = RetroManifest.load().assets().imageEdits();
        assertFalse(edits.isEmpty());
        for (Map.Entry<String, List<Fill>> edit : edits.entrySet()) {
            String textureId = edit.getKey();
            assertEquals(
                    new TextureDecision.Copy(textureId, RetroSource.VANILLA),
                    rules.decide(textureId, CLIENT_JAR),
                    textureId + " must be copied for its edits to apply");
            BufferedImage image = texture(textureId);
            for (Fill fill : edit.getValue()) {
                Rect rect = fill.rect();
                String where = textureId + " " + rect;
                assertTrue(fill.sampleX() < image.getWidth() && fill.sampleY() < image.getHeight());
                assertTrue(rect.x() + rect.width() <= image.getWidth(), where);
                assertTrue(rect.y() + rect.height() <= image.getHeight(), where);
                for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
                    for (int x = rect.x(); x < rect.x() + rect.width(); x++) {
                        int pixel = image.getRGB(x, y);
                        assertEquals(0xFF, pixel >>> 24, where + " at " + x + "," + y);
                        assertTrue(
                                fill.palette().contains(pixel & 0xFFFFFF),
                                where + " at " + x + "," + y + ": " + Integer.toHexString(pixel));
                    }
                }
            }
        }
    }

    @Test
    void theInventoryLosesItsCraftingGridAndOffhandToThePanelGrey() throws IOException {
        // InventoryMenu's slots: the 2x2 grid from (98, 18), the result at (154, 28), the
        // offhand at (77, 62); each slot is drawn one pixel out on every side.
        List<Fill> fills =
                RetroManifest.load().assets().imageEdits().get("minecraft:gui/container/inventory");
        BufferedImage inventory = texture("minecraft:gui/container/inventory");

        assertEquals(
                List.of(new Rect(97, 17, 74, 36), new Rect(76, 61, 18, 18)),
                fills.stream().map(Fill::rect).toList());
        // The panel, a slot's inside, its dark edge and its light edge.
        Set<Integer> vanillaPalette =
                Set.of(0xC6C6C6, 0x8B8B8B, 0x373737, VanillaGuiColors.SLOT_HIGHLIGHT & 0xFFFFFF);
        for (Fill fill : fills) {
            assertEquals(
                    0xFFC6C6C6,
                    inventory.getRGB(fill.sampleX(), fill.sampleY()),
                    "the sample is the panel grey");
            assertEquals(vanillaPalette, fill.palette(), "the guard allows the panel and slots");
        }
    }

    @Test
    void theCopiesAreTheLegacyBorderAndTheContainerBackgrounds() throws IOException {
        // The tooltip alias turns the unique style vanilla; the copy keeps it for vetsmod:legacy.
        // same_name turns the chest, hopper and horse screens vanilla; a container that keeps
        // Wynncraft's label is drawn on the copy of its own. The inventory screen is always
        // retro's, so its texture needs no copy.
        TextureRules rules = realTextureRules();
        Map<String, String> copies = RetroManifest.load().assets().wynncraftCopies();

        assertEquals(
                Map.of(
                        "vetsmod:gui/sprites/tooltip/legacy_background",
                        "minecraft:gui/sprites/tooltip/unique_background",
                        "vetsmod:gui/sprites/tooltip/legacy_frame",
                        "minecraft:gui/sprites/tooltip/unique_frame",
                        "vetsmod:gui/container/wynncraft/generic_54",
                        "minecraft:gui/container/generic_54",
                        "vetsmod:gui/container/wynncraft/hopper",
                        "minecraft:gui/container/hopper",
                        "vetsmod:gui/container/wynncraft/horse",
                        "minecraft:gui/container/horse",
                        "vetsmod:item/retro/tier_tag",
                        "wynntils:ui_components/sprites/highlight_tag"),
                copies);
        for (String original : copies.values()) {
            // Nothing replaces Wynntils' tag: its copy is there to reach the items atlas.
            if (original.startsWith("wynntils:")) continue;
            assertInstanceOf(
                    TextureDecision.Copy.class,
                    rules.decide(original, CLIENT_JAR),
                    original + " is replaced, so only the copy keeps Wynncraft's");
        }
    }

    @Test
    void theRaptorAndWyvernSaddlesAreRecolouredFromTheLeather() throws IOException {
        // The leather's colour is in both art sources, so the copy comes out the same from either.
        RetroManifest manifest = RetroManifest.load();
        Map<String, Integer> tints =
                Map.of(
                        "vetsmod:item/retro/saddle_red", 0xC8241C,
                        "vetsmod:item/retro/saddle_white", 0xE9E9E9);
        for (Map.Entry<String, Integer> saddle : tints.entrySet()) {
            String copy = saddle.getKey();
            assertEquals("minecraft:item/saddle", copySources(manifest).get(copy), copy);
            assertEquals(0xDA662C, copyFroms(manifest).get(copy), copy);
            assertEquals(saddle.getValue(), copyTints(manifest).get(copy), copy);
        }
    }

    @Test
    void theHorseWhistleIsLeatherHorseArmourInVanillasColour() throws IOException {
        // Vanilla's art is grey and its item definition dyes it: a white stack would leave it
        // grey, so the dyed layer is a copy baked in that colour, under vanilla's undyed overlay.
        RetroManifest manifest = RetroManifest.load();
        String dyed = "vetsmod:item/retro/leather_horse_armor_dyed";
        assertEquals("minecraft:item/leather_horse_armor", copySources(manifest).get(dyed));
        assertEquals(0xA06540, copyTints(manifest).get(dyed));
        JsonObject textures =
                model("vetsmod:item/retro/leather_horse_armor").getAsJsonObject("textures");
        assertEquals("vetsmod:item/retro/blank", textures.get("layer0").getAsString());
        assertEquals(dyed, textures.get("layer1").getAsString());
        assertEquals(
                "minecraft:item/leather_horse_armor_overlay", textures.get("layer2").getAsString());
    }

    @Test
    void theAbilityShardIsALingeringPotionInItsNamesColour() throws IOException {
        // A shard's stack is white, which would leave vanilla's liquid pale, so its liquid is a
        // copy baked in the colour of the shard's name.
        RetroManifest manifest = RetroManifest.load();
        assertEquals(
                "minecraft:item/potion_overlay",
                copySources(manifest).get("vetsmod:item/retro/ability_shard_liquid"));
        assertEquals(0x82EFF4, copyTints(manifest).get("vetsmod:item/retro/ability_shard_liquid"));
        JsonObject textures = model("vetsmod:item/retro/ability_shard").getAsJsonObject("textures");
        assertEquals("vetsmod:item/retro/blank", textures.get("layer0").getAsString());
        assertEquals(
                "vetsmod:item/retro/ability_shard_liquid", textures.get("layer1").getAsString());
        assertEquals("minecraft:item/lingering_potion", textures.get("layer2").getAsString());
    }

    @Test
    void everyWynntilsFrameStretchesAVanillaTextureItFits() throws IOException {
        RetroManifest manifest = RetroManifest.load();
        TextureRules rules = new TextureRules(manifest.textures());
        assertEquals(
                List.of(),
                manifest.assets()
                        .wynntilsTextures()
                        .get("wynntils:ui_components/emerald_count_background"),
                "blank");
        assertEquals(
                2,
                manifest.assets()
                        .wynntilsTextures()
                        .get("wynntils:ui_components/quick_jump_button")
                        .size(),
                "normal over hovered");
        for (Map.Entry<String, List<Frame>> row : manifest.assets().wynntilsTextures().entrySet()) {
            for (Frame frame : row.getValue()) {
                String where = row.getKey() + " <- " + frame.source();
                assertEquals(
                        RetroSource.VANILLA,
                        rules.firstSourceWith(frame.source(), CLIENT_JAR),
                        where);
                BufferedImage source = texture(frame.source());
                Rect region =
                        frame.region() != null
                                ? frame.region()
                                : new Rect(0, 0, source.getWidth(), source.getHeight());
                assertTrue(region.x() + region.width() <= source.getWidth(), where);
                assertTrue(region.y() + region.height() <= source.getHeight(), where);
                assertTrue(region.width() > 2 * frame.border(), where);
                assertTrue(region.height() > 2 * frame.border(), where);
            }
        }
    }

    @Test
    void theLangSectionRestoresVanillasStringsInWynncraftsLocales() throws IOException {
        // Wynncraft pack a43ae4e6 overrides these five locales and blanks or renames these
        // strings; the values are vanilla's en_us ones. container.crafting stays blank: the
        // inventory has no crafting grid.
        AssetSections.Lang lang = RetroManifest.load().assets().lang();
        JsonObject vanilla;
        try (InputStream in =
                ShippedAssetsTest.class.getResourceAsStream("/assets/minecraft/lang/en_us.json")) {
            assertNotNull(in);
            vanilla =
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .getAsJsonObject();
        }

        assertEquals(List.of("en_us", "en_gb", "en_ca", "en_au", "en_nz"), lang.locales());
        assertEquals(
                Set.of("container.inventory", "menu.returnToGame", "menu.game"),
                lang.strings().keySet());
        for (Map.Entry<String, String> string : lang.strings().entrySet()) {
            assertEquals(Json.stringOrEmpty(vanilla, string.getKey()), string.getValue());
        }
        assertFalse(lang.strings().containsKey("container.crafting"));
    }
}
