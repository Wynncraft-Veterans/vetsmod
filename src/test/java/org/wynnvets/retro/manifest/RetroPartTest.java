package org.wynnvets.retro.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link RetroPart}: its names, and the stored form of the parts turned off. */
class RetroPartTest {

    @Test
    void aPartIsFoundByItsExactName() {
        assertEquals(RetroPart.GEAR_BOXES, RetroPart.fromYaml("gear_boxes"));
        assertEquals(RetroPart.PLAYER_LIST, RetroPart.fromYaml("player_list"));
        assertNull(RetroPart.fromYaml("GEAR_BOXES"));
        assertNull(RetroPart.fromYaml("nope"));
        assertNull(RetroPart.fromYaml(null));
    }

    @Test
    void aPartIsFoundByWhatAPlayerSpells() {
        assertEquals(RetroPart.GEAR_BOXES, RetroPart.fromName("GEAR_Boxes"));
        assertEquals(RetroPart.HUD, RetroPart.fromName(" hud "));
        assertNull(RetroPart.fromName("nope"));
    }

    @Test
    void namesAreJoinedInDeclarationOrder() {
        assertEquals(
                "blocks, gear_boxes",
                RetroPart.names(List.of(RetroPart.GEAR_BOXES, RetroPart.BLOCKS), ", "));
        assertEquals("", RetroPart.names(Set.of(), ", "));
    }

    @Test
    void theStoredPartsReadBackAsWritten() {
        Set<RetroPart> off = Set.of(RetroPart.HUD, RetroPart.BLOCKS, RetroPart.GEAR_BOXES);

        String stored = RetroPart.formatOff(off);

        assertEquals("blocks,gear_boxes,hud", stored, "in declaration order");
        assertEquals(off, RetroPart.parseOff(stored));
    }

    @Test
    void noneStoredIsEveryPartOn() {
        assertEquals("", RetroPart.formatOff(Set.of()));
        assertTrue(RetroPart.parseOff("").isEmpty());
        assertTrue(RetroPart.parseOff(null).isEmpty());
        assertEquals(RetroPart.all(), RetroPart.allBut(RetroPart.parseOff("")));
    }

    @Test
    void aNameThisClientDoesNotKnowIsIgnoredSoADowngradeSurvives() {
        assertEquals(
                Set.of(RetroPart.HUD, RetroPart.MOBS),
                RetroPart.parseOff("from_a_newer_client, hud ,,MOBS"));
    }

    @Test
    void takingPartsLeavesTheRest() {
        Set<RetroPart> rest = RetroPart.allBut(Set.of(RetroPart.WEAPONS));

        assertEquals(RetroPart.values().length - 1, rest.size());
        assertTrue(!rest.contains(RetroPart.WEAPONS) && rest.contains(RetroPart.ITEMS));
    }
}
