package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.wynnvets.retro.manifest.ManifestFixtures.parseTextures;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the parser's reading of {@link StackSections}: the stack models, the gear box
 * icons and the outlined icons, on small fixtures.
 */
class StackSectionsTest {

    @Test
    void aStackModelRowNeedsItsThreeFields() {
        StackSections.StackModel row =
                parseTextures(
                                """
                                stack_models:
                                  - {item: 'minecraft:a', name: 'A.*', model: 'minecraft:b'}
                                """)
                        .stacks()
                        .stackModels()
                        .get(0);

        assertEquals("minecraft:a", row.item());
        assertEquals("A.*", row.name().pattern());
        assertEquals("minecraft:b", row.model());
        assertEquals(RetroPart.ITEMS, row.part(), "the part of a row with none");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parseTextures(
                                "stack_models:\n  - {item: 'minecraft:a', model: 'minecraft:b'}\n"));
    }

    @Test
    void aStackModelRowCanNameItsPartAndNoOtherName() {
        String row = "stack_models:\n  - {item: 'minecraft:a', name: 'A', model: 'minecraft:b', ";

        assertEquals(
                RetroPart.CRAFTING,
                parseTextures(row + "part: crafting}\n").stacks().stackModels().get(0).part());
        assertThrows(IllegalArgumentException.class, () -> parseTextures(row + "part: nope}\n"));
    }

    @Test
    void outlinedIconsTakeTheirGroupsOutlineAndScaleAndAMalformedOneFailsTheParse() {
        String outline = "outline: {pixels: 1, colour: '#191919'}";
        Map<String, StackSections.OutlinedIcon> rows =
                parseTextures(
                                """
                                outlined_icons:
                                  - scale: 0.5
                                    outline: {pixels: 3, colour: '#191919'}
                                    art:
                                      'WEAPON_TOME': 'minecraft:item/iron_sword'
                                """)
                        .stacks()
                        .outlinedIcons();

        assertEquals(
                Map.of(
                        "WEAPON_TOME",
                        new StackSections.OutlinedIcon(
                                "minecraft:item/iron_sword", 3, 0x191919, 0.5)),
                rows);
        for (String group :
                List.of(
                        "{scale: 0.5, outline: {pixels: 9, colour: '#191919'}}",
                        "{scale: 0.5, outline: {pixels: 1}}",
                        "{scale: 0.5, outline: 1}",
                        "{scale: 0.5}",
                        "{" + outline + "}",
                        "{scale: 0, " + outline + "}",
                        "{scale: 1.5, " + outline + "}",
                        "{scale: 0.5, " + outline + ", art: {'weapon': 'minecraft:a'}}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("outlined_icons:\n  - " + group + "\n"),
                    group);
        }
    }

    @Test
    void gearBoxIconsReadAGearTypeAndAnItemInFileOrder() {
        RetroManifest manifest =
                parseTextures(
                        """
                        gear_box_icons:
                          'SPEAR': 'minecraft:iron_shovel'
                          'MASTERY_TOME': 'minecraft:book'
                        """);
        assertEquals(
                List.of(
                        Map.entry("SPEAR", "minecraft:iron_shovel"),
                        Map.entry("MASTERY_TOME", "minecraft:book")),
                List.copyOf(manifest.stacks().gearBoxIcons().entrySet()));

        for (String row :
                List.of(
                        "'Spear': 'minecraft:iron_shovel'",
                        "'SPEAR TYPE': 'minecraft:iron_shovel'",
                        "'SPEAR': 'iron_shovel'",
                        "'SPEAR': 'minecraft:iron_.*'",
                        "'SPEAR': ''")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parseTextures("gear_box_icons:\n  " + row + "\n"),
                    row);
        }
    }

    @Test
    void accessoryArtNeedsTheColourOfItsEmptySlotsOutline() {
        String art = "  art: {'RING': 'minecraft:wynn/ring'}\n";

        assertEquals(
                0x555555,
                parseTextures("accessory_tiers:\n  empty_outline: '#555555'\n" + art)
                        .stacks()
                        .accessoryTiers()
                        .emptyOutline());
        assertThrows(
                IllegalArgumentException.class, () -> parseTextures("accessory_tiers:\n" + art));
    }

    @Test
    void anEmptySlotsInnerLinesAreABrightnessAndOptional() {
        String outline = "accessory_tiers:\n  empty_outline: '#555555'\n";
        String art = "  art: {'RING': 'minecraft:wynn/ring'}\n";

        assertEquals(
                0, parseTextures(outline + art).stacks().accessoryTiers().emptyLinesBelowLuma());
        assertEquals(
                30,
                parseTextures(outline + "  empty_lines_below_luma: 30\n" + art)
                        .stacks()
                        .accessoryTiers()
                        .emptyLinesBelowLuma());
        assertThrows(
                IllegalArgumentException.class,
                () -> parseTextures(outline + "  empty_lines_below_luma: 256\n" + art));
    }
}
