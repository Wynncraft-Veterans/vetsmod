package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.copySources;
import static org.wynnvets.retro.manifest.ManifestFixtures.copyTints;
import static org.wynnvets.retro.manifest.ShippedFiles.model;
import static org.wynnvets.retro.manifest.ShippedFiles.texture;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.colors.Recolours;
import org.wynnvets.retro.rules.Atlas;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.ModelStub;
import org.wynnvets.retro.rules.RetroAssetIds;
import org.wynnvets.util.Json;

/**
 * Tests for the shipped potion rows ({@link ModelSections}): that every potion model gets its
 * family's liquid, and that the potion parent draws it under the vanilla bottle. See
 * {@link ShippedFiles ShippedFiles}.
 */
class ShippedPotionsTest {

    /**
     * The Wynncraft potion families, each with the colour of its liquid. The colours are the
     * skill-point colours and vanilla's effect colours; see the {@code potions} section of
     * {@code retro_textures.yml}.
     */
    private static final Map<String, Integer> POTION_FAMILIES =
            Map.of(
                    "strength", 0x00AA00,
                    "dexterity", 0xFFFF55,
                    "intelligence", 0x55FFFF,
                    "defense", 0xFF5555,
                    "agility", 0xFFFFFF,
                    "healing", 0xF82423,
                    "mana", 0x385DC6,
                    "wisdom", 0x59C106,
                    "drunkness", 0x551D4A);

    /** The Avomod potion models, each with its family. */
    private static final Map<String, String> AVOMOD_POTIONS =
            Map.of(
                    "potion_sp_strength", "strength",
                    "potion_sp_dexterity", "dexterity",
                    "potion_sp_intelligence", "intelligence",
                    "potion_sp_defence", "defense",
                    "potion_sp_agility", "agility",
                    "potion_health", "healing",
                    "potion_mana", "mana");

    private static final String POTION_OVERLAY = "minecraft:item/potion_overlay";

    @Test
    void everyPotionModelGetsItsFamilysLiquidWhateverItsFill() throws IOException {
        // Wynncraft's empty, half and full potions are all full of liquid in its own art (pack
        // a43ae4e6), and "empty" is not an empty bottle: a Potion of Dexterity [1/2], custom
        // model data 3441, draws dexterity_empty.
        RetroManifest manifest = RetroManifest.load();
        ModelRules rules = new ModelRules(manifest.models());
        for (Map.Entry<String, Integer> family : POTION_FAMILIES.entrySet()) {
            String prefix = "minecraft:item/wynn/potion/" + family.getKey() + "_";
            String liquid = RetroAssetIds.potionLiquid(family.getKey());
            assertEquals(POTION_OVERLAY, copySources(manifest).get(liquid), liquid);
            assertEquals(family.getValue(), copyTints(manifest).get(liquid), liquid);
            for (String fill : List.of("empty", "half", "full")) {
                ModelStub.Potion stub = rules.potionStub(prefix + fill).orElseThrow();
                assertEquals(liquid, stub.liquid(), prefix + fill);
            }
        }
        for (Map.Entry<String, String> avomod : AVOMOD_POTIONS.entrySet()) {
            String model = "avomod2:item/" + avomod.getKey();
            assertEquals(
                    RetroAssetIds.potionLiquid(avomod.getValue()),
                    rules.potionStub(model).orElseThrow().liquid(),
                    model);
        }
        assertEquals(
                Optional.empty(),
                rules.potionStub("avomod2:item/potion_rainbow"),
                "Avomod's crafted and mixed potions keep Avomod's art");
    }

    @Test
    void everyPotionRowCoversAPinnedModel() throws IOException {
        List<String> pinned = new ArrayList<>();
        for (String family : POTION_FAMILIES.keySet()) {
            for (String fill : List.of("empty", "half", "full")) {
                pinned.add("minecraft:item/wynn/potion/" + family + "_" + fill);
            }
        }
        AVOMOD_POTIONS.keySet().forEach(model -> pinned.add("avomod2:item/" + model));
        for (ModelSections.Potion row : RetroManifest.load().models().potions()) {
            assertTrue(
                    pinned.stream().anyMatch(id -> row.models().matcher(id).matches()),
                    row.models().pattern());
        }
    }

    @Test
    void thePotionParentDrawsTheTintedLiquidUnderTheVanillaBottleFromOneAtlas() throws IOException {
        // A potion stub drawing from two atlases would send Wynncraft's whole potion.json to the
        // missing model. Layer 0 takes the stack's colour, so it is a blank.
        JsonObject parent = model(RetroAssetIds.TINTED_POTION);
        JsonObject textures = parent.getAsJsonObject("textures");
        String liquid = RetroAssetIds.potionLiquid("mana");

        assertEquals("minecraft:item/generated", Json.stringOrEmpty(parent, "parent"));
        assertEquals("vetsmod:item/retro/blank", Json.stringOrEmpty(textures, "layer0"));
        assertEquals("#liquid", Json.stringOrEmpty(textures, "layer1"));
        assertEquals(RetroAssetIds.POTION_BOTTLE, Json.stringOrEmpty(textures, "layer2"));
        assertEquals("#liquid", Json.stringOrEmpty(textures, "particle"));
        for (String texture :
                List.of(
                        "vetsmod:item/retro/blank",
                        liquid,
                        RetroAssetIds.POTION_BOTTLE,
                        POTION_OVERLAY)) {
            assertEquals(Optional.of(Atlas.ITEMS), Atlas.of(texture), texture);
        }
        assertTrue(textureInClientJar(POTION_OVERLAY));
        assertTrue(textureInClientJar(RetroAssetIds.POTION_BOTTLE));
    }

    @Test
    void thePotionRecipeTintsEveryVisiblePixelOfTheLiquid() throws IOException {
        // The recipe's outline rule keeps a near-black line; the liquid has none to keep, so a
        // potion is its colour all over.
        Recipe recipe = RetroManifest.load().assets().recipes().get("potion_overlay");
        assertEquals(POTION_OVERLAY, recipe.art());
        IntUnaryOperator recolour = recipe.recolour(0x385DC6);
        IntUnaryOperator tint = Recolours.tint(0x385DC6);
        BufferedImage overlay = texture(POTION_OVERLAY);
        for (int y = 0; y < overlay.getHeight(); y++) {
            for (int x = 0; x < overlay.getWidth(); x++) {
                int pixel = overlay.getRGB(x, y);
                if (pixel >>> 24 == 0) continue;
                assertEquals(tint.applyAsInt(pixel), recolour.applyAsInt(pixel), x + "," + y);
            }
        }
    }
}
