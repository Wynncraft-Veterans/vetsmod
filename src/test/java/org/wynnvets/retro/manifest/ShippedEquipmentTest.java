package org.wynnvets.retro.manifest;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.CLIENT_JAR;
import static org.wynnvets.retro.manifest.ShippedFiles.model;
import static org.wynnvets.retro.manifest.ShippedFiles.realModelRules;
import static org.wynnvets.retro.manifest.ShippedFiles.realTextureRules;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.rules.Atlas;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.ModelStub;
import org.wynnvets.retro.rules.TextureDecision;
import org.wynnvets.retro.rules.TextureRules;
import org.wynnvets.util.Json;

/**
 * Tests for the shipped equipment rows ({@link ModelSections}): that every Wynncraft equipment
 * asset has a row, and that the vanilla armour its rows name exists. See
 * {@link ShippedFiles ShippedFiles}.
 */
class ShippedEquipmentTest {

    /**
     * Every equipment asset Wynncraft ships ({@code equipment/*.json}), each with the vanilla
     * asset whose file replaces it, or {@link RetroManifest#KEEP} for its own.
     */
    private static final Map<String, String> WYNNCRAFT_EQUIPMENT =
            Map.ofEntries(
                    entry("hidden", RetroManifest.KEEP),
                    entry("leather", "minecraft:leather"),
                    entry("pale_leather", "minecraft:leather"),
                    entry("chainmail", "minecraft:chainmail"),
                    entry("pale_chainmail", "minecraft:chainmail"),
                    entry("iron", "minecraft:iron"),
                    entry("pale_iron", "minecraft:iron"),
                    entry("gold", "minecraft:gold"),
                    entry("pale_gold", "minecraft:gold"),
                    entry("diamond", "minecraft:diamond"),
                    entry("pale_diamond", "minecraft:diamond"),
                    entry("netherite", "minecraft:netherite"),
                    entry("pale_netherite", "minecraft:netherite"),
                    entry("titanium", "minecraft:netherite"),
                    entry("pale_titanium", "minecraft:netherite"),
                    entry("tan", RetroManifest.KEEP),
                    entry("shaman", RetroManifest.KEEP),
                    entry("copper", RetroManifest.KEEP),
                    entry("pale_copper", RetroManifest.KEEP),
                    entry("quartz", RetroManifest.KEEP),
                    entry("infernal", RetroManifest.KEEP),
                    entry("gendarme", RetroManifest.KEEP),
                    entry("phantom", RetroManifest.KEEP),
                    entry("wings", RetroManifest.KEEP));

    /** The client jar's equipment asset {@code assetId}, as JSON. */
    private static JsonObject equipment(String assetId) throws IOException {
        int colon = assetId.indexOf(':');
        String file =
                "/assets/"
                        + assetId.substring(0, colon)
                        + "/equipment/"
                        + assetId.substring(colon + 1)
                        + ".json";
        try (InputStream in = ShippedEquipmentTest.class.getResourceAsStream(file)) {
            assertNotNull(in, file);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void everyArmourMaterialWithAVanillaPieceShowsOneThatExists() throws IOException {
        // Gold is vanilla's golden, titanium (above diamond) netherite; leather is vanilla's
        // leather piece's own model.
        ModelRules rules = realModelRules();
        Map<String, String> materials =
                Map.of(
                        "gold", "golden",
                        "chainmail", "chainmail",
                        "iron", "iron",
                        "diamond", "diamond",
                        "netherite", "netherite",
                        "titanium", "netherite",
                        "leather", "leather");
        for (Map.Entry<String, String> material : materials.entrySet()) {
            for (String pale : List.of("", "pale_")) {
                for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
                    String model =
                            "minecraft:item/wynn/armor/"
                                    + piece
                                    + "/"
                                    + pale
                                    + material.getKey()
                                    + "_"
                                    + piece;
                    ModelStub stub = rules.itemStub(model).orElseThrow();
                    String texture = "minecraft:item/" + material.getValue() + "_" + piece;

                    assertEquals(texture, stub.art(), model);
                    if (stub instanceof ModelStub.TintProof tintProof) {
                        assertEquals(ParentKind.GENERATED, tintProof.parent(), model);
                    }
                    assertTrue(textureInClientJar(texture), texture);
                }
            }
        }
    }

    @Test
    void leatherIsTheVanillaLeatherPieceInTheStacksOwnDye() throws IOException {
        // Wynncraft's leather and pale_leather models take the stack's dye on tint index 0, as
        // vanilla's leather items do, so the stub is the vanilla model itself: its layer 0, the
        // piece, takes the dye; layer 1, the overlay, does not. Both are in the items atlas.
        ModelRules rules = realModelRules();
        for (String pale : List.of("", "pale_")) {
            for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
                String wynncraft = "minecraft:item/wynn/armor/" + piece + "/" + pale + "leather_";
                String vanilla = "minecraft:item/leather_" + piece;

                assertEquals(
                        Optional.of(new ModelStub.ItemParent(vanilla)),
                        rules.itemStub(wynncraft + piece),
                        wynncraft + piece);
                JsonObject textures = model(vanilla).getAsJsonObject("textures");
                assertEquals(
                        "minecraft:item/generated", Json.stringOrEmpty(model(vanilla), "parent"));
                assertEquals(vanilla, Json.stringOrEmpty(textures, "layer0"), vanilla);
                assertEquals(vanilla + "_overlay", Json.stringOrEmpty(textures, "layer1"), vanilla);
                assertEquals(2, textures.size(), vanilla);
                for (String texture : List.of(vanilla, vanilla + "_overlay")) {
                    assertTrue(textureInClientJar(texture), texture);
                    assertEquals(Optional.of(Atlas.ITEMS), Atlas.of(texture), texture);
                }
            }
        }
    }

    @Test
    void everyWynncraftEquipmentAssetHasARowAndItsVanillaAsset() throws IOException {
        // Each of the 24 assets of pack a43ae4e6 is decided by a row of its own, keep included,
        // so a new one of Wynncraft's shows up as a test to extend rather than a silent keep.
        RetroManifest manifest = RetroManifest.load();
        ModelRules rules = new ModelRules(manifest.models());
        for (Map.Entry<String, String> asset : WYNNCRAFT_EQUIPMENT.entrySet()) {
            String id = "minecraft:" + asset.getKey();
            Optional<String> expected =
                    asset.getValue().equals(RetroManifest.KEEP)
                            ? Optional.empty()
                            : Optional.of(asset.getValue());

            assertTrue(
                    manifest.models().equipment().stream()
                            .anyMatch(row -> row.assets().matcher(id).matches()),
                    id + " has a row");
            assertEquals(expected, rules.equipmentAsset(id), id);
        }
    }

    @Test
    void everyEquipmentRowCoversAPinnedAsset() throws IOException {
        for (ModelSections.Equipment row : RetroManifest.load().models().equipment()) {
            assertTrue(
                    WYNNCRAFT_EQUIPMENT.keySet().stream()
                            .anyMatch(
                                    asset -> row.assets().matcher("minecraft:" + asset).matches()),
                    row.assets().pattern());
        }
    }

    @Test
    void everyEquipmentTargetIsAVanillaAssetWhoseTexturesSameNameCopies() throws IOException {
        // The generator copies vanilla's file as it is, so each layer's texture must be one the
        // client jar has (entity/equipment/<layer>/<texture>), and one same_name gives its 2013
        // art; vanilla stands for every source here.
        TextureRules rules = realTextureRules();
        ModelRules models = realModelRules();
        Set<String> targets = new TreeSet<>();
        for (String asset : WYNNCRAFT_EQUIPMENT.keySet()) {
            models.equipmentAsset("minecraft:" + asset).ifPresent(targets::add);
        }
        assertEquals(
                Set.of(
                        "minecraft:leather",
                        "minecraft:chainmail",
                        "minecraft:iron",
                        "minecraft:gold",
                        "minecraft:diamond",
                        "minecraft:netherite"),
                targets);
        for (String target : targets) {
            JsonObject layers = equipment(target).getAsJsonObject("layers");
            assertFalse(layers.isEmpty(), target);
            for (String layer : layers.keySet()) {
                for (JsonElement entry : layers.getAsJsonArray(layer)) {
                    String texture = Json.stringOrEmpty(entry.getAsJsonObject(), "texture");
                    int colon = texture.indexOf(':');
                    String id =
                            texture.substring(0, colon)
                                    + ":entity/equipment/"
                                    + layer
                                    + "/"
                                    + texture.substring(colon + 1);
                    assertTrue(textureInClientJar(id), target + " " + id);
                    assertEquals(
                            new TextureDecision.Copy(id, RetroSource.VANILLA),
                            rules.decide(id, CLIENT_JAR),
                            id);
                }
            }
        }
    }
}
