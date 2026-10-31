package org.wynnvets.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * Tests for {@link LivePack}: whether the pack's copy of Wynncraft's unique tooltip border, its
 * other copies of Wynncraft's textures, its item definitions and its image edits count, by whether
 * the pack is drawn and what its generation wrote.
 */
class LivePackTest {

    @AfterEach
    void reset() {
        RetroModeManager.clearForTesting();
    }

    /** A report whose generation wrote {@code copies}. */
    private static RetroReport generatedWith(Set<String> copies) {
        return generatedWith(copies, Map.of());
    }

    /** A report whose generation wrote {@code copies} and painted {@code imageEdits}. */
    private static RetroReport generatedWith(
            Set<String> copies, Map<String, List<Rect>> imageEdits) {
        return generatedWith(copies, Set.of(), Set.of(), imageEdits);
    }

    /**
     * A report whose generation wrote {@code copies}, copied the item definitions of
     * {@code copiedItems}, wrote the items {@code generatedItems} and painted {@code imageEdits}.
     */
    private static RetroReport generatedWith(
            Set<String> copies,
            Set<String> copiedItems,
            Set<String> generatedItems,
            Map<String, List<Rect>> imageEdits) {
        RetroReport.Seen seen =
                new RetroReport.Seen(Map.of(), Set.of(), List.of(), Set.of(), List.of(), Map.of());
        return new RetroReport(
                "wynntils_preloaded/test",
                List.of(),
                Map.of(),
                copies,
                copiedItems,
                generatedItems,
                imageEdits,
                List.of(),
                List.of(),
                0,
                seen);
    }

    @Test
    void theLegacyStyleIsDrawnOnlyWhileTheLivePackHasBothItsSprites() {
        RetroModeManager.generated(generatedWith(RetroAssetIds.LEGACY_TOOLTIP_SPRITES));
        assertFalse(LivePack.isLegacyTooltipStyle(), "the pack is not live yet");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());
        assertTrue(LivePack.isLegacyTooltipStyle());

        RetroModeManager.generated(
                generatedWith(Set.of("vetsmod:gui/sprites/tooltip/legacy_frame")));
        assertFalse(
                LivePack.isLegacyTooltipStyle(),
                "a frame without its background would draw half a border");

        RetroModeManager.generated(generatedWith(RetroAssetIds.LEGACY_TOOLTIP_SPRITES));
        RetroModeManager.reloadStarted();
        assertFalse(LivePack.isLegacyTooltipStyle(), "a reload clears it");
    }

    @Test
    void anImageEditCountsOnlyWhileTheLivePackPaintedIt() {
        String inventory = "minecraft:gui/container/inventory";
        List<Rect> grid = List.of(new Rect(97, 17, 74, 36));
        RetroModeManager.generated(generatedWith(Set.of(), Map.of(inventory, grid)));
        assertEquals(List.of(), LivePack.imageEdits(inventory), "not live yet");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());
        assertEquals(grid, LivePack.imageEdits(inventory));
        assertEquals(
                List.of(),
                LivePack.imageEdits("minecraft:gui/container/generic_54"),
                "nothing was painted there");

        RetroModeManager.generated(generatedWith(Set.of()));
        assertEquals(
                List.of(),
                LivePack.imageEdits(inventory),
                "a generation whose guard refused every fill painted nothing");

        RetroModeManager.generated(generatedWith(Set.of(), Map.of(inventory, grid)));
        RetroModeManager.reloadStarted();
        assertEquals(List.of(), LivePack.imageEdits(inventory), "a reload clears it");
    }

    @Test
    void aWynncraftTexturesCopyCountsOnlyWhileTheLivePackWroteIt() {
        // The real rule files copy Wynncraft's chest texture to this vetsmod id.
        String chest = "minecraft:gui/container/generic_54";
        String copy = "vetsmod:gui/container/wynncraft/generic_54";
        RetroModeManager.generated(generatedWith(Set.of(copy)));
        assertEquals(Optional.empty(), LivePack.wynncraftCopy(chest), "not live yet");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());
        assertEquals(Optional.of(copy), LivePack.wynncraftCopy(chest));
        assertEquals(
                Optional.empty(),
                LivePack.wynncraftCopy("minecraft:gui/container/hopper"),
                "its copy was not written");
        assertEquals(
                Optional.empty(),
                LivePack.wynncraftCopy(copy),
                "a copy is found by the texture it copies");

        RetroModeManager.reloadStarted();
        assertEquals(Optional.empty(), LivePack.wynncraftCopy(chest), "a reload");
    }

    @Test
    void anItemDefinitionsCopyCountsOnlyWhileTheLivePackWroteIt() {
        RetroModeManager.generated(
                generatedWith(Set.of(), Set.of("minecraft:bow"), Set.of(), Map.of()));
        assertEquals(Optional.empty(), LivePack.itemDefinition("minecraft:bow"), "not live");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());
        assertEquals(
                Optional.of("vetsmod:retro/minecraft/bow"),
                LivePack.itemDefinition("minecraft:bow"));
        assertEquals(
                Optional.empty(),
                LivePack.itemDefinition("minecraft:stick"),
                "its definition was not copied");

        RetroModeManager.reloadStarted();
        assertEquals(Optional.empty(), LivePack.itemDefinition("minecraft:bow"), "a reload");
    }

    @Test
    void aGeneratedItemCountsOnlyWhileTheLivePackWroteIt() {
        String ring = "vetsmod:retro/accessory/ring_gold";
        RetroModeManager.generated(generatedWith(Set.of(), Set.of(), Set.of(ring), Map.of()));
        assertFalse(LivePack.isGeneratedItem(ring), "not live yet");

        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());
        assertTrue(LivePack.isGeneratedItem(ring));
        assertFalse(LivePack.isGeneratedItem("vetsmod:retro/accessory/ring_iron"));

        RetroModeManager.reloadStarted();
        assertFalse(LivePack.isGeneratedItem(ring), "a reload");
    }

    @Test
    void aCopiedItemAndAGeneratedItemAreDifferentQuestions() {
        String bow = "minecraft:bow";
        String ring = "vetsmod:retro/accessory/ring_gold";
        RetroModeManager.generated(generatedWith(Set.of(), Set.of(bow), Set.of(ring), Map.of()));
        RetroModeManager.packWentLive(RetroModeManager.reloadStarted());

        assertFalse(LivePack.isGeneratedItem(bow), "a copied item is not a generated one");
        assertEquals(Optional.empty(), LivePack.itemDefinition(ring), "nor the other way round");
    }
}
