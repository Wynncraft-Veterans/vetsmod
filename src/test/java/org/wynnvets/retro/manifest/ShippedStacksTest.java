package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ShippedFiles.onClasspath;
import static org.wynnvets.retro.manifest.ShippedFiles.textureInClientJar;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.rules.ModelRules;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * Tests for the shipped stack rows ({@link StackSections}): that every outlined icon has art,
 * and every gear box type its icon. See {@link ShippedFiles ShippedFiles}.
 */
class ShippedStacksTest {

    @Test
    void everyOutlinedIconHasArtThatExists() throws IOException {
        Map<String, StackSections.OutlinedIcon> rows =
                RetroManifest.load().stacks().outlinedIcons();

        assertEquals(
                Set.of(
                        "SPEAR",
                        "WAND",
                        "DAGGER",
                        "BOW",
                        "RELIK",
                        "ARMOUR_TOME",
                        "WEAPON_TOME",
                        "MARATHON_TOME",
                        "EXPERTISE_TOME",
                        "MYSTICISM_TOME",
                        "LOOTRUN_TOME"),
                rows.keySet());
        for (StackSections.OutlinedIcon icon : rows.values()) {
            assertTrue(textureInClientJar(icon.art()), icon.art());
        }
    }

    @Test
    void everyGearTypeOfABoxShowsTheItemRetroShowsItsClassOrPieceAs() throws IOException {
        // The gear types a box can be, as Wynntils' UnidentifiedItemIconFeature draws them: its
        // icons cover these and the tome and charm, which are not boxes.
        RetroManifest manifest = RetroManifest.load();
        ModelRules rules = new ModelRules(manifest.models());
        Map<String, String> icons = manifest.stacks().gearBoxIcons();

        assertEquals(
                Set.of(
                        "HELMET",
                        "CHESTPLATE",
                        "LEGGINGS",
                        "BOOTS",
                        "RING",
                        "NECKLACE",
                        "BRACELET",
                        "GUILD_TOME"),
                icons.keySet());
        // A weapon's icon is the art its class's stubs show.
        Map<String, String> weapons =
                Map.of(
                        "SPEAR", "weapon/warrior/spear_air_a",
                        "WAND", "weapon/mage/wand_basic_wood",
                        "DAGGER", "weapon/assassin/dagger_fire_c",
                        "BOW", "weapon/archer/bow_thunder_b",
                        "RELIK", "weapon/shaman/relik_basic_wooden");
        for (Map.Entry<String, String> weapon : weapons.entrySet()) {
            assertEquals(
                    rules.itemStub("minecraft:item/wynn/" + weapon.getValue()).orElseThrow().art(),
                    manifest.stacks().outlinedIcons().get(weapon.getKey()).art(),
                    weapon.getKey());
        }
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            assertEquals("minecraft:iron_" + piece, icons.get(piece.toUpperCase(Locale.ROOT)));
        }
        assertEquals("minecraft:gold_nugget", icons.get("RING"));
        assertEquals("minecraft:lead", icons.get("NECKLACE"));
        assertEquals("minecraft:clock", icons.get("BRACELET"));
        // The pack copies each item's vanilla definition, so the jar must have it.
        for (String item : icons.values()) {
            assertTrue(onClasspath(item, "items", ".json"), item);
            assertEquals(
                    "vetsmod:retro/" + item.replace(":", "/"),
                    RetroAssetIds.itemDefinitionCopy(item),
                    item);
        }
    }
}
