package org.wynnvets.retro.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.StackSections;
import org.wynnvets.retro.manifest.StackSections.OutlinedIcon;

/**
 * Tests for {@link RetroSlotIcons}' pure parts: where the icon sits on a slot, how big it is drawn,
 * which item the shipped {@code gear_box_icons} rows draw for a gear type, and on which screen the
 * Ability Tree's button shows its glint. Drawing needs Wynntils' item model and a running client,
 * and is not tested.
 */
class RetroSlotIconsTest {

    @Test
    void anIconIsCentredOnTheSlot() {
        // A 12-pixel icon leaves 2 pixels of the 16-pixel slot on each side.
        assertEquals(2f, RetroSlotIcons.iconOffset(0.75f));
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
    void aTypeIsDrawnAtItsOutlinedRowsScaleOrElseItsGearBoxRowsScale() {
        StackSections stacks = RetroModeManager.manifest().stacks();

        // The shipped rows: 0.625 and 0.6875, each grown by 1 pixel each side.
        assertEquals(0.703125f, RetroSlotIcons.iconScale(stacks, "SPEAR"), "0.625 * 18 / 16");
        assertEquals(
                0.7734375f,
                RetroSlotIcons.iconScale(stacks, "ARMOUR_TOME"),
                "0.6875 * 18 / 16: the tomes' art the Allegiance shield's size");
        assertEquals(0.75f, RetroSlotIcons.iconScale(stacks, "HELMET"));
        assertEquals(
                0.6875f,
                RetroSlotIcons.iconScale(stacks, "GUILD_TOME"),
                "the Allegiance tome's shield is a gear_box_icons item a little under an armour"
                        + " piece's size");
        assertEquals(0f, RetroSlotIcons.iconScale(stacks, "MASTERY_TOME"), "no row");
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
