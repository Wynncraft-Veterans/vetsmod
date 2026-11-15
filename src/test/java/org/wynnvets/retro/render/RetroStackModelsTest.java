package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.RetroManifest;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.StackSections.StackModel;

/**
 * Tests for {@link RetroStackModels}' row lookup against the shipped {@code stack_models} rows.
 * Swapping a stack's model needs a running client and is not tested.
 */
class RetroStackModelsTest {

    private static List<StackModel> rowsFor(String item) throws IOException {
        return RetroManifest.load().stacks().stackModels().stream()
                .filter(row -> row.item().equals(item))
                .toList();
    }

    /** The lookup with every part on. */
    private static String modelFor(List<StackModel> rows, String name) {
        return RetroStackModels.modelFor(rows, name, RetroPart.all());
    }

    @Test
    void activeBlessesIsTheNetherStarAndAnyOtherSplashPotionItself() throws IOException {
        List<StackModel> rows = rowsFor("minecraft:splash_potion");

        assertEquals("minecraft:nether_star", modelFor(rows, "Active Blesses"));
        assertNull(modelFor(rows, "Splash Potion"));
    }

    @Test
    void theGuildItemIsTheTotemAndAKeyOnTheSameItemIsNot() throws IOException {
        List<StackModel> rows = rowsFor("minecraft:tripwire_hook");

        assertEquals("minecraft:totem_of_undying", modelFor(rows, "Returners [Lv. 94]"));
        assertNull(modelFor(rows, "Animal Key"));
    }

    @Test
    void theTwoShieldIngredientsAreTheRealShieldWhateverWrapsTheirNames() throws IOException {
        List<StackModel> rows = rowsFor("minecraft:potion");

        assertEquals(
                "minecraft:shield", modelFor(rows, "\uDAFC\uDC00Butterfly Buckler\uDAFC\uDC00"));
        assertEquals("minecraft:shield", modelFor(rows, "Router Shield"));
        assertNull(modelFor(rows, "Mega Fern"));
    }

    @Test
    void aRowMatchesTheWholeName() throws IOException {
        assertNull(modelFor(rowsFor("minecraft:splash_potion"), "Active Blesses Extra"));
    }

    @Test
    void aRowOfAPartThatIsOffIsPassedOver() throws IOException {
        List<StackModel> shields = rowsFor("minecraft:potion");
        List<StackModel> blesses = rowsFor("minecraft:splash_potion");
        Set<RetroPart> noCrafting = RetroPart.allBut(Set.of(RetroPart.CRAFTING));

        assertNull(RetroStackModels.modelFor(shields, "Router Shield", noCrafting));
        assertEquals(
                "minecraft:nether_star",
                RetroStackModels.modelFor(blesses, "Active Blesses", noCrafting),
                "a row with no part of its own is items'");
        assertNull(
                RetroStackModels.modelFor(
                        blesses, "Active Blesses", RetroPart.allBut(Set.of(RetroPart.ITEMS))));
    }
}
