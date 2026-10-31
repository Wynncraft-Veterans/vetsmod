package org.wynnvets.debug.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.debug.retro.AuditModels.Parented;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.pack.MemoryPack;
import org.wynnvets.retro.rules.ModelRules;

/**
 * Tests for how the retro report reads Wynncraft's item models over a pack in memory: which parent
 * what, and which models reach a stub that way. The ingredient lines read Wynntils and the vanilla
 * pack, and are not tested.
 */
class AuditModelsTest {

    private static final String DAGGER = "minecraft:item/wynn/weapon/assassin/dagger_earth_a";
    private static final String GLOVES =
            "minecraft:item/wynn/outer_void/utility/patchwork_climbing_gloves";

    private static final ModelRules NO_RULES = new ModelRules(RetroManifest.empty().models());

    /** A generation that stubbed the dagger alone. */
    private static final RetroReport.Seen SEEN =
            new RetroReport.Seen(
                    Map.of(), Set.of(), List.of(), Set.of(DAGGER), List.of(), Map.of());

    private static String modelFile(String model) {
        return "minecraft:models/" + model.substring("minecraft:".length()) + ".json";
    }

    @Test
    void aModelsParentIsReadAndWhetherItHasArtOfItsOwn() {
        MemoryPack pack =
                new MemoryPack("server/abc")
                        .file(modelFile(GLOVES), "{\"parent\": \"" + DAGGER + "\"}")
                        .file(
                                modelFile("minecraft:item/wynn/textured"),
                                "{\"parent\": \"" + DAGGER + "\", \"textures\": {\"a\": \"b\"}}")
                        .file(
                                modelFile("minecraft:item/wynn/shaped"),
                                "{\"parent\": \"item/generated\", \"elements\": []}")
                        .file(
                                modelFile("minecraft:item/wynn/shown"),
                                "{\"parent\": \"" + DAGGER + "\", \"display\": {}}")
                        .file(modelFile("minecraft:item/wynn/orphan"), "{\"textures\": {}}")
                        .file(modelFile("minecraft:item/wynn/not_an_object"), "[]")
                        .unreadable(modelFile("minecraft:item/wynn/unreadable"))
                        .file("minecraft:models/block/stone.json", "{\"parent\": \"block/cube\"}");

        assertEquals(
                Map.of(
                        GLOVES,
                        new Parented(DAGGER, false),
                        "minecraft:item/wynn/textured",
                        new Parented(DAGGER, true),
                        "minecraft:item/wynn/shaped",
                        new Parented("minecraft:item/generated", true),
                        "minecraft:item/wynn/shown",
                        new Parented(DAGGER, false)),
                AuditModels.parents(pack));
    }

    @Test
    void aModelWithNoArtOfItsOwnDrawsTheStubThroughItsParent() {
        Map<String, Parented> parents =
                Map.of(
                        GLOVES,
                        new Parented(DAGGER, false),
                        "minecraft:item/wynn/textured",
                        new Parented(DAGGER, true),
                        "minecraft:item/wynn/grandchild",
                        new Parented(GLOVES, false),
                        "minecraft:item/wynn/under_textured",
                        new Parented("minecraft:item/wynn/textured", false),
                        "minecraft:item/wynn/plain",
                        new Parented("minecraft:item/generated", false),
                        DAGGER,
                        new Parented("minecraft:item/generated", false));

        assertEquals(
                List.of(
                        "minecraft:item/wynn/grandchild -> " + GLOVES + " -> " + DAGGER + "  ",
                        GLOVES + " -> " + DAGGER + "  "),
                AuditModels.drawnThroughParent(NO_RULES, parents, SEEN),
                "the stub itself and the models with art of their own are left out");
        assertEquals(
                List.of(
                        "minecraft:item/wynn/grandchild -> " + GLOVES + " -> " + DAGGER + "  ",
                        GLOVES + " -> " + DAGGER + "  ",
                        "minecraft:item/wynn/textured -> " + DAGGER + "  ",
                        "minecraft:item/wynn/under_textured -> minecraft:item/wynn/textured -> "
                                + DAGGER
                                + "  "),
                AuditModels.derived(NO_RULES, parents, SEEN),
                "whereas every model that reaches a stub parents it");
    }

    @Test
    void aChainThatLoopsEnds() {
        Map<String, Parented> parents =
                Map.of(
                        "minecraft:item/a", new Parented("minecraft:item/b", false),
                        "minecraft:item/b", new Parented("minecraft:item/a", false));

        assertEquals(List.of(), AuditModels.derived(NO_RULES, parents, SEEN));
    }
}
