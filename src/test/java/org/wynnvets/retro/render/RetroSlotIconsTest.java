package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.StackSections.OutlinedIcon;

/**
 * Tests for {@link RetroSlotIcons}' pure parts: where the icon sits on a slot, how big it is drawn,
 * which item the shipped {@code gear_box_icons} rows draw for a gear type, and on which screen the
 * Ability Tree's button shows its glint. Drawing needs Wynntils' item model and a running client,
 * and is not tested.
 */
class RetroSlotIconsTest {

    @Test
    void theIconIsThreeQuartersOfTheSlotCentredOnIt() {
        assertEquals(0.75f, RetroSlotIcons.ICON_SCALE);
        assertEquals(2f, RetroSlotIcons.iconOffset(RetroSlotIcons.ICON_SCALE));
        // As much slot shows on each side of the icon.
        assertEquals(
                16f,
                RetroSlotIcons.iconOffset(RetroSlotIcons.ICON_SCALE) * 2
                        + 16 * RetroSlotIcons.ICON_SCALE,
                "the offset, the icon and the offset fill the slot");
    }

    @Test
    void anOutlinedIconsTextureIsGrownSoItsArtStaysItsRowsSize() {
        // Three pixels of outline each side grow the texture from 16 to 22.
        OutlinedIcon tome = new OutlinedIcon("minecraft:a", 3, 0x191919, 0.375);
        assertEquals(0.515625f, RetroSlotIcons.drawnScale(tome));
        assertEquals(3.875f, RetroSlotIcons.iconOffset(RetroSlotIcons.drawnScale(tome)));
        assertEquals(
                0.5f, RetroSlotIcons.drawnScale(new OutlinedIcon("minecraft:a", 0, 0x191919, 0.5)));
    }

    @Test
    void aTypeWithNoOutlinedRowIsDrawnAtTheIconScale() {
        Map<String, OutlinedIcon> outlined = RetroModeManager.manifest().stacks().outlinedIcons();

        assertEquals(
                RetroSlotIcons.drawnScale(outlined.get("SPEAR")),
                RetroSlotIcons.iconScale(outlined, "SPEAR"));
        assertEquals(
                RetroSlotIcons.drawnScale(outlined.get("ARMOUR_TOME")),
                RetroSlotIcons.iconScale(outlined, "ARMOUR_TOME"));
        assertEquals(RetroSlotIcons.ICON_SCALE, RetroSlotIcons.iconScale(outlined, "HELMET"));
        assertEquals(
                RetroSlotIcons.ICON_SCALE,
                RetroSlotIcons.iconScale(outlined, "GUILD_TOME"),
                "the Allegiance tome's shield is a gear_box_icons item, not a smaller tome icon");
    }

    @Test
    void theAbilityTreesGlintIsOnlyOnAScreenRetroRelabelled() {
        assertSame(RetroSlotIcons.SlotOverlay.GLINT, RetroSlotIcons.abilityTreeOverlay(true));
        assertSame(
                RetroSlotIcons.SlotOverlay.NONE,
                RetroSlotIcons.abilityTreeOverlay(false),
                "the tree screen's Ability Points button is the same Wynntils item");
    }

    @Test
    void eachGearTypeWithARowGetsItsItemAndATypeWithNoRowNone() {
        assertEquals("minecraft:iron_boots", RetroSlotIcons.itemFor("BOOTS"));
        assertEquals("minecraft:gold_nugget", RetroSlotIcons.itemFor("RING"));
        assertEquals("minecraft:shield", RetroSlotIcons.itemFor("GUILD_TOME"));
        assertNull(RetroSlotIcons.itemFor("MASTERY_TOME"), "not a gear box");
        assertNull(RetroSlotIcons.itemFor("SPEAR"), "an outlined icon");
        assertNull(RetroSlotIcons.itemFor("spear"), "the enum's own name");
    }
}
