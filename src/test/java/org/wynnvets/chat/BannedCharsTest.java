package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link BannedChars}: which characters each strip removes, that neighbours survive, and that
 * a clean string comes back as the same instance. Every fixture is built from escapes, because a
 * rendered private-use literal can be lost silently.
 */
class BannedCharsTest {

    private static final String HARD = "\u00C1\uE07E\uE080\uE086";

    // One of each soft-set range or point: section sign, rank icon, banner piece, negative-width
    // spaces, pill letter.
    private static final String SOFT_SAMPLE =
            "\u00A7\uE014\uE025\uE00E\uE011\uE070\uE07A\uE085"
                    + "\uE000\uE012\uE071\uE07F\uE087\uE088\u2064\uE040\uE06F";

    @Test
    void stripHard_removesEachHardCharAndKeepsNeighbours() {
        for (char c : HARD.toCharArray()) {
            assertEquals("okok", BannedChars.stripHard("ok" + c + "ok"));
        }
        assertEquals("", BannedChars.stripHard(HARD));
    }

    @Test
    void stripHard_keepsTheSoftSet() {
        assertEquals(SOFT_SAMPLE, BannedChars.stripHard(SOFT_SAMPLE));
    }

    @Test
    void stripAll_removesBothSets() {
        assertEquals("ab", BannedChars.stripAll("a" + HARD + SOFT_SAMPLE + "b"));
        for (char c : SOFT_SAMPLE.toCharArray()) {
            assertEquals("okok", BannedChars.stripAll("ok" + c + "ok"));
        }
    }

    @Test
    void stripAll_keepsTheGapsBetweenSoftRanges() {
        // Neighbours of the soft ranges that are not in them: wrap markers, a separator, the code
        // point below the banner pieces, an unlisted rank-row code point, and the spoiler codec's
        // own range.
        String kept = "\uE001\uE002\uE003\uE006\uE00D\uE013\uE026\uE03F\uE072\uF600\uF601\uF6C3";
        assertEquals(kept, BannedChars.stripAll(kept));
    }

    @Test
    void cleanText_returnsTheSameInstance() {
        String clean = "hello, world";
        assertSame(clean, BannedChars.stripHard(clean));
        assertSame(clean, BannedChars.stripAll(clean));
    }

    @Test
    void nullAndEmpty_passThrough() {
        assertNull(BannedChars.stripHard(null));
        assertNull(BannedChars.stripAll(null));
        assertEquals("", BannedChars.stripAll(""));
    }
}
