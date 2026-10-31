package org.wynnvets.retro.pack.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ModelJson}: each file's exact text, as Minecraft's loaders read it, and what a
 * model's file names and draws.
 */
class ModelJsonTest {

    private static String text(byte[] file) {
        return new String(file, StandardCharsets.UTF_8);
    }

    @Test
    void aParentOnlyModelNamesItsParentAndNothingElse() {
        assertEquals(
                "{\"parent\":\"minecraft:item/egg\"}",
                text(ModelJson.parentOnly("minecraft:item/egg")));
    }

    @Test
    void aModelWithATextureFillsItsParentsSlot() {
        assertEquals(
                "{\"parent\":\"vetsmod:item/p\",\"textures\":{\"art\":\"minecraft:item/stick\"}}",
                text(ModelJson.withTexture("vetsmod:item/p", "art", "minecraft:item/stick")));
    }

    @Test
    void aGeneratedItemIsFlatOverItsLayerZero() {
        assertEquals(
                "{\"parent\":\"minecraft:item/generated\","
                        + "\"textures\":{\"layer0\":\"vetsmod:item/a\"}}",
                text(ModelJson.generatedItem("vetsmod:item/a")));
    }

    @Test
    void anItemDefinitionDrawsItsModel() {
        assertEquals(
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"vetsmod:item/a\"}}",
                text(ModelJson.itemDefinition("vetsmod:item/a")));
    }

    @Test
    void aModelsFileNamesItsSlotsAndItsParent() {
        ModelJson.Named named =
                ModelJson.read(
                        """
                        {
                          "parent": "minecraft:item/generated",
                          "textures": {
                            "a": "minecraft:item/a",
                            "b": "#a",
                            "c": {"sprite": "minecraft:item/c", "atlas": "minecraft:blocks"}
                          }
                        }
                        """
                                .getBytes(StandardCharsets.UTF_8));

        assertEquals("minecraft:item/generated", named.parent());
        assertEquals(
                Map.of("a", "minecraft:item/a", "b", "#a", "c", "minecraft:item/c"), named.slots());
    }

    @Test
    void aModelWithNeitherNamesNothing() {
        ModelJson.Named named = ModelJson.read("{}".getBytes(StandardCharsets.UTF_8));

        assertNull(named.parent());
        assertEquals(Map.of(), named.slots());
    }

    @Test
    void whatAModelDrawsFollowsItsReferencesAndLeavesItsParticleOut() {
        assertEquals(
                Set.of("minecraft:item/a"),
                ModelJson.drawn(
                        Map.of(
                                "a", "minecraft:item/a",
                                "b", "#a",
                                "particle", "minecraft:block/dirt")));
        assertEquals(
                Set.of("minecraft:block/dirt"),
                ModelJson.drawn(Map.of("a", "#particle", "particle", "minecraft:block/dirt")),
                "a slot that draws the particle's texture does draw it");
        assertEquals(Set.of(), ModelJson.drawn(Map.of("particle", "minecraft:block/dirt")));
    }

    @Test
    void aReferenceThatLeadsNowhereDrawsNothing() {
        assertEquals(Set.of(), ModelJson.drawn(Map.of("a", "#gone")));
        assertEquals(Set.of(), ModelJson.drawn(Map.of("a", "#b", "b", "#a")), "a loop ends");
    }
}
