package org.wynnvets.retro.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.wynnvets.retro.manifest.ManifestFixtures;
import org.wynnvets.retro.manifest.PartSections;
import org.wynnvets.retro.manifest.RetroPart;

/** Tests for {@link PartFilter}, over a small {@code parts} fixture. */
class PartFilterTest {

    private static final PartSections ROWS =
            ManifestFixtures.parseTextures(
                            """
                            parts:
                              blocks:
                                about: 'a'
                                textures: ['minecraft:block/.*']
                              weapons:
                                about: 'b'
                                models: ['minecraft:item/wynn/weapon/.*']
                                wynntils: ['WeaponFeature']
                              items:
                                about: 'c'
                                models: ['minecraft:item/wynn/.*']
                                textures: ['minecraft:item/.*']
                            """)
                    .parts();

    private static final String STICK = "minecraft:item/stick";

    private static PartFilter off(RetroPart... parts) {
        return new PartFilter(ROWS, RetroPart.allBut(Set.of(parts)));
    }

    @Test
    void itKnowsWhichPartsAreOnAndWhetherAllAre() {
        assertTrue(off().allOn());
        assertTrue(off().on(RetroPart.HUD));
        assertFalse(off(RetroPart.HUD).allOn());
        assertFalse(off(RetroPart.HUD).on(RetroPart.HUD));
        assertTrue(off(RetroPart.HUD).on(RetroPart.ITEMS));
    }

    @Test
    void theFirstRowInFileOrderOwnsAModel() {
        String weapon = "minecraft:item/wynn/weapon/warrior/spear_air_a";
        String key = "minecraft:item/wynn/dungeon/key";

        assertFalse(off(RetroPart.WEAPONS).modelOn(weapon));
        assertTrue(off(RetroPart.WEAPONS).modelOn(key));
        assertTrue(off(RetroPart.ITEMS).modelOn(weapon), "the row above the catch-all owns it");
        assertFalse(off(RetroPart.ITEMS).modelOn(key));
    }

    @Test
    void anIdNoRowOwnsCannotBeTurnedOff() {
        PartFilter allOff = new PartFilter(ROWS, Set.of());

        assertTrue(allOff.modelOn("minecraft:block/stone"), "no row's models match");
        assertTrue(allOff.textureOn("minecraft:entity/pig", Set.of(), Set.of()));
        assertTrue(allOff.settingOn("OtherFeature"));
    }

    @Test
    void aSettingFollowsThePartThatNamesItsFeature() {
        assertFalse(off(RetroPart.WEAPONS).settingOn("WeaponFeature"));
        assertTrue(off(RetroPart.ITEMS).settingOn("WeaponFeature"));
    }

    @Test
    void aTextureFollowsItsOwnerUnlessAnOnPartDrawsIt() {
        String stone = "minecraft:block/stone";

        assertFalse(off(RetroPart.BLOCKS).textureOn(stone, Set.of(), Set.of()));
        assertTrue(off(RetroPart.ITEMS).textureOn(stone, Set.of(), Set.of()));
        assertFalse(
                off(RetroPart.BLOCKS).textureOn(stone, Set.of(stone), Set.of()),
                "an ingredient's icon of stone does not repaint the world");
        assertFalse(off(RetroPart.ITEMS).textureOn(STICK, Set.of(), Set.of()));
        assertTrue(
                off(RetroPart.ITEMS).textureOn(STICK, Set.of(STICK), Set.of()),
                "a wand's stick, with only weapons on");
    }

    @Test
    void aTextureNoModelSharesFollowsItsOwner() {
        assertFalse(off(RetroPart.BLOCKS).textureOn("minecraft:block/stone"));
        assertTrue(off(RetroPart.ITEMS).textureOn("minecraft:block/stone"));
    }

    @Test
    void anItemsTextureAnOffPartDrawsStaysOffUnlessAnOnPartDrawsItToo() {
        PartFilter noCrafting = off(RetroPart.CRAFTING);

        assertFalse(noCrafting.textureOn(STICK, Set.of(), Set.of(STICK)), "items is on");
        assertTrue(noCrafting.textureOn(STICK, Set.of(STICK), Set.of(STICK)));
        assertTrue(
                noCrafting.textureOn("minecraft:item/bow", Set.of(), Set.of(STICK)),
                "what no off part draws follows items");
        assertTrue(
                noCrafting.textureOn(
                        "minecraft:block/stone", Set.of(), Set.of("minecraft:block/stone")),
                "the exception is items' alone");
    }

    @Test
    void withEveryPartOnNothingIsLeftOut() {
        PartFilter on = off();

        assertTrue(on.modelOn("minecraft:item/wynn/weapon/warrior/spear_air_a"));
        assertTrue(on.settingOn("WeaponFeature"));
        assertTrue(on.textureOn(STICK, Set.of(), Set.of(STICK)), "drawnOff is not even asked");
    }
}
