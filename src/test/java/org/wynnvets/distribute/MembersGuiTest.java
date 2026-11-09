package org.wynnvets.distribute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MembersGui} — the tile-bounds predicate and the page-turn check the walker
 * waits on.
 *
 * <p>The table is a boundary sweep over {@link MembersGui#isTileSlot(int)}: one either side of
 * every edge of the rows 0–4 × cols 2–8 area, plus the two page-button slots, which are the
 * discriminating inputs. Sampling the middle of the area would pin nothing — it cannot tell
 * {@code <=} from {@code <}.</p>
 *
 * <p>Slots 10 and 28 earn their rows twice over. They are the two page buttons, and they land
 * in column 1 — one column outside the tile area. That is what makes
 * {@code MembersListSearcher.onSetSlot}'s three-way {@code NEXT || PREVIOUS || isTileSlot} a
 * union of disjoint things rather than a predicate with a redundant arm.</p>
 *
 * <p>The literals that mirror Wynntils' {@code GuildMemberListContainer} are not pinned: with
 * Wynntils absent at test runtime, nothing here can compare them with the originals.</p>
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
     * Both slots sit in row 5 inside the tile columns, so only the row bound keeps them out; a
     * slot in column 0 or 1 would fail the column check whatever the row bound says.
     */
    @Test
    void slotsBelowTheTileAreaAreNotTiles() {
        assertNotTile(47); // row 5, col 2 — directly below the first tile of the bottom row
        assertNotTile(53); // row 5, col 8 — directly below the last tile
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
     * Java's {@code /} and {@code %} truncate toward zero, so −1 is row 0 column −1 and falls
     * out of the column check without throwing.
     *
     * <p>Not obviously unreachable: the two scan loops iterate a real item list, but
     * {@code MembersListSearcher.onSetSlot} hands this method whatever slot the event
     * carries.</p>
     */
    @Test
    void aNegativeSlotIsNotATileAndDoesNotThrow() {
        assertNotTile(-1);
    }

    // ----- pageTurned -----

    /** The page a click was made from: slots 2 and 3, the first two tiles of row 0. */
    private static final Map<Integer, String> OLD_PAGE = Map.of(2, "Metrafish", 3, "Holidaze");

    @Test
    void pageTurned_falseWhileTheOldPageIsStillShowing() {
        assertFalse(MembersGui.pageTurned(OLD_PAGE, OLD_PAGE));
    }

    @Test
    void pageTurned_trueOnceEveryTileIsNew() {
        assertTrue(MembersGui.pageTurned(OLD_PAGE, Map.of(2, "Wenweia", 3, "Nazzae")));
    }

    @Test
    void pageTurned_falseOnAPartlyUpdatedPage() {
        assertFalse(
                MembersGui.pageTurned(OLD_PAGE, Map.of(2, "Wenweia", 3, "Holidaze")),
                "a slot still holding its old name means the page has not finished turning");
    }

    @Test
    void pageTurned_trueWhenATileNameRecursInAnotherSlot() {
        // Tile names are not unique: one member's legacy name can be another's current
        // name. On the turned page the shared name sits in a different slot.
        assertTrue(MembersGui.pageTurned(OLD_PAGE, Map.of(2, "Wenweia", 3, "Metrafish")));
    }

    @Test
    void pageTurned_falseOnAnEmptyPage() {
        assertFalse(
                MembersGui.pageTurned(OLD_PAGE, Map.of()),
                "an empty scan proves nothing: the tiles may be mid-update");
    }
}
