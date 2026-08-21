package org.wynnvets.distribute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MembersGui} — the tile-bounds predicate, and the literals that claim to
 * mirror Wynntils' {@code GuildMemberListContainer}.
 *
 * <p>The table is a boundary sweep over {@link MembersGui#isTileSlot(int)}: one either side of
 * every edge of the rows 0–4 × cols 2–8 area, plus the two page-button slots, which are the
 * discriminating inputs. Sampling the middle of the area would pin nothing — it cannot tell
 * {@code <=} from {@code <}, and it cannot tell the three separate copies this predicate
 * replaced apart from each other.</p>
 *
 * <p>Slots 10 and 28 earn their rows twice over. They are the two page buttons, and they land
 * in column 1 — one column outside the tile area. That is what makes
 * {@code MembersListSearcher.onSetSlot}'s three-way {@code NEXT || PREVIOUS || isTileSlot} a
 * union of disjoint things rather than a predicate with a redundant arm.</p>
 *
 * <p>The pattern assertions are deliberate change-detectors. Nothing in this repo can verify
 * that a literal still matches what Wynncraft renders; what they protect is the "Mirrors
 * {@code GuildMemberListContainer.X}" claim on each field, by making a silent edit to one of
 * them fail rather than pass. {@code StyledText.matches} itself is Wynntils' behaviour and is
 * not tested here.</p>
 *
 * <p>NOTE: {@link MembersGui} imports Wynntils and Minecraft types, but its static state is
 * three {@link java.util.regex.Pattern}s and six {@code int}s, so nothing of theirs loads
 * during class-init — the same rule {@code SplitDistributorTest} records for its own
 * subject.</p>
 */
class MembersGuiTest {

    /** The 9-wide grid the slot arithmetic assumes. */
    private static final int COLUMNS = 9;

    private static String at(int slot) {
        return "slot " + slot + " (row " + slot / COLUMNS + ", col " + slot % COLUMNS + ")";
    }

    private static void assertTile(int slot) {
        assertTrue(MembersGui.isTileSlot(slot), at(slot) + " should be a player-head tile");
    }

    private static void assertNotTile(int slot) {
        assertFalse(MembersGui.isTileSlot(slot), at(slot) + " should not be a player-head tile");
    }

    // ── isTileSlot, at every edge of the tile area ────────────────────

    @Test
    void slotsLeftOfTheTileAreaAreNotTiles() {
        assertNotTile(0); // row 0, col 0
        assertNotTile(1); // row 0, col 1 — one column short of the first tile
    }

    @Test
    void theFirstTileOfTheTopRowIsATile() {
        assertTile(2); // row 0, col 2 — the first tile
    }

    @Test
    void theLastColumnOfTheTopRowIsATile() {
        assertTile(8); // row 0, col 8
    }

    @Test
    void theLastTileOfTheBottomRowIsATile() {
        assertTile(44); // row 4, col 8 — the last tile
    }

    /**
     * Slot 53 is the one that pins the bottom edge. 45 and 54 are both in column 0 and fail
     * the column check whatever the row bound says, so widening the area by a row would leave
     * them passing; 53 sits directly under the last tile and is the only one of the three that
     * notices.
     */
    @Test
    void slotsBelowTheTileAreaAreNotTiles() {
        assertNotTile(45); // row 5, col 0
        assertNotTile(54); // row 6, col 0 — into the player's own inventory
    }

    /** Counterpart to {@link #slotsBelowTheTileAreaAreNotTiles()}; see its note. */
    @Test
    void theSlotDirectlyBelowTheLastTileIsNotATile() {
        assertNotTile(53); // row 5, col 8
    }

    /**
     * The page buttons are not tiles. If either of these ever returned {@code true}, the
     * searcher's {@code onSetSlot} filter would have a redundant arm and the walker would
     * collect a page button as if it were a member.
     *
     * <p>One test each rather than two assertions in one, so a change that breaks both is
     * reported as breaking both — a single method stops at its first failure and would hide
     * half of what it caught.</p>
     */
    @Test
    void thePreviousPageButtonIsNotATile() {
        assertNotTile(MembersGui.PREVIOUS_PAGE_SLOT); // 10 — row 1, col 1
    }

    /** Counterpart to {@link #thePreviousPageButtonIsNotATile()}. */
    @Test
    void theNextPageButtonIsNotATile() {
        assertNotTile(MembersGui.NEXT_PAGE_SLOT); // 28 — row 3, col 1
    }

    /**
     * Pinned as <em>undocumented</em>, not as contract. Java's {@code /} and {@code %}
     * truncate toward zero, so −1 is row 0 column −1 and falls out of the column check without
     * throwing.
     *
     * <p>Not obviously unreachable, either: the two scan loops iterate a real item list, but
     * {@code MembersListSearcher.onSetSlot} hands this method whatever slot the event carries.
     * What is pinned is that the answer matches the three copies this predicate replaced,
     * which is the only claim this chunk is entitled to make.</p>
     */
    @Test
    void aNegativeSlotIsNotATileAndDoesNotThrow() {
        assertNotTile(-1);
    }

    // ── The literals that mirror GuildMemberListContainer ─────────────

    @Test
    void titlePatternMirrorsUpstream() {
        assertEquals(".+: Members", MembersGui.TITLE_PATTERN.pattern());
    }

    @Test
    void pageButtonPatternsMirrorUpstream() {
        assertEquals("§a§lNext Page", MembersGui.NEXT_PAGE_PATTERN.pattern());
        assertEquals("§a§lPrevious Page", MembersGui.PREVIOUS_PAGE_PATTERN.pattern());
    }

    @Test
    void pageButtonSlotsMirrorUpstream() {
        assertEquals(28, MembersGui.NEXT_PAGE_SLOT, "GuildMemberListContainer.getNextItemSlot()");
        assertEquals(
                10,
                MembersGui.PREVIOUS_PAGE_SLOT,
                "GuildMemberListContainer.getPreviousItemSlot()");
    }
}
