package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wynnvets.retro.manifest.ManifestFixtures.parse;

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
                parse(
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
                () -> parse("stack_models:\n  - {item: 'minecraft:a', model: 'minecraft:b'}\n"));
    }

    @Test
    void aStackModelRowCanNameItsPartAndNoOtherName() {
        String row = "stack_models:\n  - {item: 'minecraft:a', name: 'A', model: 'minecraft:b', ";

        assertEquals(
                RetroPart.CRAFTING,
                parse(row + "part: crafting}\n").stacks().stackModels().get(0).part());
        assertThrows(IllegalArgumentException.class, () -> parse(row + "part: nope}\n"));
    }

    @Test
    void outlinedIconsTakeTheirGroupsOutlineAndScaleAndAMalformedOneFailsTheParse() {
        String outline = "outline: {pixels: 1, colour: '#191919'}";
        Map<String, StackSections.OutlinedIcon> rows =
                parse(
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
                    () -> parse("outlined_icons:\n  - " + group + "\n"),
                    group);
        }
    }

    @Test
    void gearBoxIconsReadATypeAndAnItemAtTheirGroupsScaleInFileOrder() {
        RetroManifest manifest =
                parse(
                        """
                        gear_box_icons:
                          - scale: 0.75
                            items:
                              'SPEAR': 'minecraft:iron_shovel'
                          - scale: 0.5
                            items:
                              'MASTERY_TOME': 'minecraft:book'
                        """);
        assertEquals(
                List.of(
                        Map.entry(
                                "SPEAR",
                                new StackSections.GearBoxIcon("minecraft:iron_shovel", 0.75)),
                        Map.entry(
                                "MASTERY_TOME",
                                new StackSections.GearBoxIcon("minecraft:book", 0.5))),
                List.copyOf(manifest.stacks().gearBoxIcons().entrySet()));

        for (String group :
                List.of(
                        "{scale: 0.5, items: {'Spear': 'minecraft:iron_shovel'}}",
                        "{scale: 0.5, items: {'SPEAR TYPE': 'minecraft:iron_shovel'}}",
                        "{scale: 0.5, items: {'SPEAR': 'iron_shovel'}}",
                        "{scale: 0.5, items: {'SPEAR': 'minecraft:iron_.*'}}",
                        "{scale: 0.5, items: {'SPEAR': ''}}",
                        "{items: {'SPEAR': 'minecraft:iron_shovel'}}",
                        "{scale: 0, items: {'SPEAR': 'minecraft:iron_shovel'}}",
                        "{scale: 1.5, items: {'SPEAR': 'minecraft:iron_shovel'}}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> parse("gear_box_icons:\n  - " + group + "\n"),
                    group);
        }
    }

    @Test
    void aTypeInTwoGroupsFailsTheParse() {
        // Each group is its own mapping, so YAML's duplicate-key check cannot see the repeat.
        IllegalArgumentException refused =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                parse(
                                        """
                                        gear_box_icons:
                                          - {scale: 0.75, items: {'HELMET': 'minecraft:a'}}
                                          - {scale: 0.5, items: {'HELMET': 'minecraft:b'}}
                                        """));

        assertTrue(refused.getMessage().contains("HELMET is in two groups"), refused.getMessage());
    }

    @Test
    void accessoryArtNeedsTheColourOfItsEmptySlotsOutline() {
        String art = "  art: {'RING': 'minecraft:wynn/ring'}\n";

        assertEquals(
                0x555555,
                parse("accessory_tiers:\n  empty_outline: '#555555'\n" + art)
                        .stacks()
                        .accessoryTiers()
                        .emptyOutline());
        assertThrows(IllegalArgumentException.class, () -> parse("accessory_tiers:\n" + art));
    }

    @Test
    void anEmptySlotsInnerLinesAreABrightnessAndOptional() {
        String outline = "accessory_tiers:\n  empty_outline: '#555555'\n";
        String art = "  art: {'RING': 'minecraft:wynn/ring'}\n";

        assertEquals(0, parse(outline + art).stacks().accessoryTiers().emptyLinesBelowLuma());
        assertEquals(
                30,
                parse(outline + "  empty_lines_below_luma: 30\n" + art)
                        .stacks()
                        .accessoryTiers()
                        .emptyLinesBelowLuma());
        assertThrows(
                IllegalArgumentException.class,
                () -> parse(outline + "  empty_lines_below_luma: 256\n" + art));
    }
}
