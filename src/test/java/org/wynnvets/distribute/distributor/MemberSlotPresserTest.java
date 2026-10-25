package org.wynnvets.distribute.distributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins which {@link MemberSlotPresser.Resource} the NoAspects opt-out list gates: aspects
 * only. Every {@code /wv distribute} path passes {@code gatedByNoAspects()} to
 * {@code NoAspectsFilter.fetchExcludedLegacyNames}, so a wrong answer either withholds tomes
 * and emeralds from opted-out members or sends them aspects. The value count is pinned too, so
 * a new resource has to be given an answer here.
 *
 * <p>NOTE: {@link MemberSlotPresser} itself reaches Wynntils and Minecraft, but the nested
 * {@code Resource} enum is its own class, and loading it does not run the outer class's
 * initializer. If {@code Resource} ever gains a field that does, this test starts failing at
 * class-init time.</p>
 */
class MemberSlotPresserTest {

    @Test
    void gatedByNoAspects_isTrueForAspectsOnly() {
        assertEquals(3, MemberSlotPresser.Resource.values().length);
        assertTrue(MemberSlotPresser.Resource.ASPECTS.gatedByNoAspects());
        assertFalse(MemberSlotPresser.Resource.TOMES.gatedByNoAspects());
        assertFalse(MemberSlotPresser.Resource.EMERALDS.gatedByNoAspects());
    }
}
