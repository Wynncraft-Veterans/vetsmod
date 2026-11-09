package org.wynnvets.extras.sudoku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * One player's sudoku board: its cells and fills, the rule check, pencil marks and hints, the
 * stored form and the board a reopen picks.
 */
class SudokuBoardTest {

    private static final String DAY = "2026-11-01";

    private static final String SOLUTION =
            "534678912672195348198342567859761423426853791713924856961537284287419635345286179";

    /** {@link #SOLUTION} with the top-left cell blanked and every other cell given. */
    private static final String ONE_BLANK = "0" + SOLUTION.substring(1);

    private static final String GIVENS =
            "530070000600195000098000060800060003400803001700020006060000280000419005000080079";

    /** A board with one entry left to make: the top-left cell, whose digit is 5. */
    private static SudokuBoard oneBlank() {
        return SudokuBoard.fresh(DAY, ONE_BLANK);
    }

    /** A board solved by its last entry. */
    private static SudokuBoard solved() {
        SudokuBoard board = oneBlank();
        board.set(0, 5);
        assertTrue(board.trySolve());
        return board;
    }

    /** Toggles one pencil mark, as a number key does with one cell selected. */
    private static boolean mark(SudokuBoard board, int cell, int digit) {
        return board.toggleNotes(new int[] {cell}, digit);
    }

    // ── Cells and fills ──────────────────────────────────────────────────

    @Test
    void aFreshBoard_holdsItsGivensAndNoTimes() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);

        assertEquals(DAY, board.day());
        assertEquals(GIVENS, board.grid());
        assertEquals(5, board.cell(0));
        assertTrue(board.isGiven(0));
        assertEquals(0, board.cell(2));
        assertFalse(board.isGiven(2));
        assertEquals(0, board.seconds());
        assertEquals(0, board.activeMs());
        assertFalse(board.isSolved());
    }

    @Test
    void fillSeconds_roundsUpAndIsNeverZero() {
        assertEquals(1, SudokuBoard.fillSeconds(0));
        assertEquals(1, SudokuBoard.fillSeconds(1));
        assertEquals(1, SudokuBoard.fillSeconds(1000));
        assertEquals(2, SudokuBoard.fillSeconds(1001));
        assertEquals(205, SudokuBoard.fillSeconds(204_500));
    }

    @Test
    void anEntryIsStampedWithTheBankedTime_andIsNotAGiven() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.setActiveMs(4200);

        assertTrue(board.set(2, 4));

        assertEquals(4, board.cell(2));
        assertEquals(5, board.fill(2));
        assertFalse(board.isGiven(2));
        assertEquals(5, board.seconds());
    }

    @Test
    void anEntryInTheFirstSecond_stillIsNotAGiven() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);

        assertTrue(board.set(2, 4));

        assertEquals(1, board.fill(2));
        assertFalse(board.isGiven(2));
    }

    @Test
    void settingACellAgain_takesTheLaterTime() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.set(2, 4);
        board.setActiveMs(9000);

        assertTrue(board.set(2, 7));

        assertEquals(7, board.cell(2));
        assertEquals(9, board.fill(2));
    }

    @Test
    void theSameDigitAgain_changesNothing() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.set(2, 4);
        board.setActiveMs(9000);

        assertFalse(board.set(2, 4));

        assertEquals(1, board.fill(2));
    }

    @Test
    void clearingACell_blanksItAndItsTime() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.set(2, 4);

        assertTrue(board.set(2, 0));

        assertEquals(0, board.cell(2));
        assertEquals(0, board.fill(2));
        assertEquals(GIVENS, board.grid());
        assertFalse(board.set(2, 0), "clearing a blank");
    }

    @Test
    void aGiven_ignoresInput() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);

        assertFalse(board.set(0, 9));
        assertFalse(board.set(0, 0));

        assertEquals(5, board.cell(0));
        assertEquals(GIVENS, board.grid());
    }

    // ── The rule check ───────────────────────────────────────────────────

    @Test
    void aCompleteValidBoard_solves() {
        SudokuBoard board = oneBlank();
        board.set(0, 5);

        assertTrue(board.trySolve());

        assertTrue(board.isSolved());
        assertEquals(SOLUTION, board.grid());
    }

    @Test
    void aBoardWithABlank_doesNotSolve() {
        assertFalse(oneBlank().trySolve());
    }

    /** {@code grid} with its top-left cell blanked and then entered, so the board is full. */
    private static SudokuBoard entered(String grid) {
        SudokuBoard board = SudokuBoard.fresh(DAY, "0" + grid.substring(1));
        board.set(0, grid.charAt(0) - '0');
        return board;
    }

    @Test
    void aFullBoardThatBreaksARow_doesNotSolve() {
        // A 4 in place of the 5 breaks its column and its box as well as its row.
        SudokuBoard board = oneBlank();
        board.set(0, 4);

        assertFalse(board.trySolve());
        assertFalse(board.isSolved());
        assertEquals("Not solved yet", board.status());

        // Each row is its solution row's first three digits three times over: every column is
        // one of the solution's first three and every box one of its left-hand boxes, so only
        // the rows repeat a digit.
        StringBuilder rowsBroken = new StringBuilder();
        for (int row = 0; row < 9; row++) {
            rowsBroken.append(SOLUTION.substring(row * 9, row * 9 + 3).repeat(3));
        }
        assertFalse(entered(rowsBroken.toString()).trySolve());
    }

    @Test
    void aFullBoardWithOnlyItsBoxesBroken_doesNotSolve() {
        // Every row holds each digit once and so does every column, but no box does: each row is
        // the one above shifted along by one.
        StringBuilder latin = new StringBuilder();
        for (int row = 0; row < 9; row++) {
            for (int column = 0; column < 9; column++) {
                latin.append((row + column) % 9 + 1);
            }
        }

        assertFalse(entered(latin.toString()).trySolve());
    }

    @Test
    void aFullBoardWithOnlyItsColumnsBroken_doesNotSolve() {
        // The solution's top three rows three times over: every row and every box holds each
        // digit once, and every column repeats its first three.
        assertFalse(entered(SOLUTION.substring(0, 27).repeat(3)).trySolve());
    }

    @Test
    void aSolvedBoard_ignoresInputAndDoesNotSolveTwice() {
        SudokuBoard board = solved();

        assertFalse(board.set(0, 4));
        assertFalse(board.set(0, 0));
        assertFalse(board.trySolve());

        assertEquals(SOLUTION, board.grid());
    }

    // ── Pencil marks ─────────────────────────────────────────────────────

    @Test
    void aMark_togglesOnABlank() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        assertTrue(mark(board, 2, 4));
        assertTrue(mark(board, 2, 9));
        assertTrue(board.hasNote(2, 4));
        assertTrue(board.hasNote(2, 9));
        assertFalse(board.hasNote(2, 5));

        assertTrue(mark(board, 2, 4));
        assertFalse(board.hasNote(2, 4));
        assertTrue(board.hasNote(2, 9));
    }

    @Test
    void aMark_stampsNoTimeAndLeavesTheCellBlank() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.setActiveMs(9000);
        mark(board, 2, 4);
        assertEquals(0, board.cell(2));
        assertEquals(0, board.fill(2));
        assertEquals(0, board.seconds());
    }

    @Test
    void aMark_ignoresAGivenAnEntryAndASolvedBoard() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.set(2, 4);
        assertFalse(mark(board, 0, 1));
        assertFalse(mark(board, 2, 1));
        assertFalse(board.hasNote(0, 1));
        assertFalse(board.hasNote(2, 1));
        assertFalse(mark(solved(), 0, 1));
    }

    @Test
    void settingADigit_clearsTheCellsNotes() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        mark(board, 2, 4);
        mark(board, 2, 9);
        assertTrue(board.set(2, 1));
        assertFalse(board.hasNote(2, 4));
        assertFalse(board.hasNote(2, 9));
        // And clearing that digit does not bring them back.
        assertTrue(board.set(2, 0));
        assertFalse(board.hasNote(2, 4));
    }

    @Test
    void settingADigit_takesItOutOfTheMarksAroundIt() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        // Around blank cell 2: 3 shares its row, 11 its box and 29 its column; 30 shares none.
        for (int cell : new int[] {3, 11, 29, 30}) mark(board, cell, 4);
        mark(board, 3, 9);
        assertTrue(board.set(2, 4));
        assertFalse(board.hasNote(3, 4));
        assertFalse(board.hasNote(11, 4));
        assertFalse(board.hasNote(29, 4));
        assertTrue(board.hasNote(30, 4));
        assertTrue(board.hasNote(3, 9));
    }

    @Test
    void toggleNotes_addsToEveryBlankUnlessAllHaveIt() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        // Blanks 3 and 30 with given 0: the given is skipped.
        int[] cells = {3, 30, 0};
        assertTrue(board.toggleNotes(cells, 6));
        assertTrue(board.hasNote(3, 6));
        assertTrue(board.hasNote(30, 6));
        assertFalse(board.hasNote(0, 6));

        assertTrue(board.toggleNotes(cells, 6));
        assertFalse(board.hasNote(3, 6));
        assertFalse(board.hasNote(30, 6));

        // With only one marked, they all get it.
        mark(board, 3, 6);
        assertTrue(board.toggleNotes(cells, 6));
        assertTrue(board.hasNote(3, 6));
        assertTrue(board.hasNote(30, 6));
    }

    @Test
    void toggleNotes_withNoBlankOrOnASolvedBoard_changesNothing() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.set(2, 4);
        assertFalse(board.toggleNotes(new int[] {0, 1, 2}, 6));
        assertFalse(board.hasNote(2, 6));
        assertFalse(solved().toggleNotes(new int[] {0, 1}, 6));
    }

    @Test
    void fillNotes_pencilsEachBlankWithItsOpenDigits() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        // Cell 2's row holds 5, 3 and 7, its column 8, and its box 5, 3, 6, 9 and 8.
        mark(board, 2, 9);
        assertTrue(board.fillNotes());
        for (int digit = 1; digit <= 9; digit++) {
            assertEquals(digit == 1 || digit == 2 || digit == 4, board.hasNote(2, digit));
        }
        assertFalse(board.hasNote(0, 5));
        assertFalse(board.fillNotes());
        assertFalse(solved().fillNotes());
    }

    @Test
    void hintCell_pointsAtAWrongEntryFirst() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        // The answer at 2 is 4 and at 3 is 6: 3 right, 2 wrong.
        board.set(3, 6);
        board.set(2, 1);
        assertEquals(2, board.hintCell());
        assertTrue(board.isWrong(2));
        assertFalse(board.isWrong(3));
        assertFalse(board.isWrong(0));
    }

    @Test
    void hintCell_pointsAtABlankThatOneDigitFills() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        int cell = board.hintCell();
        assertEquals(0, board.cell(cell));
        // Pencilled with their open digits, the blanks show what can go where.
        board.fillNotes();
        int open = 0;
        for (int digit = 1; digit <= 9; digit++) {
            if (board.hasNote(cell, digit)) open++;
        }
        assertTrue(
                open == 1 || hasADigitNoOtherCellOfAUnitCanTake(board, cell),
                "cell " + cell + " has " + open + " digits open and none only it can take");
        assertTrue(board.isForced(cell));
        assertTrue(board.set(cell, SOLUTION.charAt(cell) - '0'));
        assertFalse(board.isWrong(cell));
    }

    /** Whether a digit noted in {@code cell} is noted in no other cell of its row, of its column
     *  or of its box. */
    private static boolean hasADigitNoOtherCellOfAUnitCanTake(SudokuBoard board, int cell) {
        for (int digit = 1; digit <= 9; digit++) {
            if (!board.hasNote(cell, digit)) continue;
            boolean row = true;
            boolean column = true;
            boolean box = true;
            for (int other = 0; other < 81; other++) {
                if (other == cell || !board.hasNote(other, digit)) continue;
                if (other / 9 == cell / 9) row = false;
                if (other % 9 == cell % 9) column = false;
                if (other / 27 == cell / 27 && other % 9 / 3 == cell % 9 / 3) box = false;
            }
            if (row || column || box) return true;
        }
        return false;
    }

    @Test
    void hintCell_withNoForcedBlank_pointsAtTheFirstWithFewestDigitsOpen() {
        // Arto Inkala's puzzle: one solution, and no blank that one digit fills at the start. Cell
        // 69 is the first of the blanks with two digits open, and none has fewer.
        SudokuBoard board =
                SudokuBoard.fresh(
                        DAY,
                        "800000000003600000070090200050007000000045700"
                                + "000100030001000068008500010090000400");
        assertEquals(69, board.hintCell());
        assertFalse(board.isForced(69));
    }

    @Test
    void hintCell_isMinusOneOnASolvedBoard() {
        assertEquals(-1, solved().hintCell());
    }

    @Test
    void clearingABlank_clearsItsNotes_andOnlyThenChangesTheBoard() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        assertFalse(board.set(2, 0));
        mark(board, 2, 4);
        assertTrue(board.set(2, 0));
        assertFalse(board.hasNote(2, 4));
        assertFalse(board.set(2, 0));
    }

    @Test
    void notes_surviveTheStoredForm() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        mark(board, 2, 4);
        mark(board, 3, 9);
        SudokuBoard read = SudokuBoard.fromJson(board.toJson());
        assertTrue(read.hasNote(2, 4));
        assertTrue(read.hasNote(3, 9));
        assertFalse(read.hasNote(2, 9));
    }

    @Test
    void aBoardStoredBeforeNotes_readsWithNone() {
        String stored =
                SudokuBoard.fresh(DAY, GIVENS).toJson().replaceAll(",\"notes\":\\[[0-9,]*\\]", "");
        assertFalse(stored.contains("notes"));
        SudokuBoard read = SudokuBoard.fromJson(stored);
        assertEquals(GIVENS, read.grid());
        assertFalse(read.hasNote(2, 4));
    }

    @Test
    void badNotes_readAsNoBoard() {
        String good = SudokuBoard.fresh(DAY, GIVENS).toJson();
        String zeros = "0,".repeat(80) + "0";
        // The wrong length, a bit that is no digit, and a mark on the given in cell 0.
        assertNull(SudokuBoard.fromJson(good.replace("\"notes\":[" + zeros, "\"notes\":[0")));
        assertNull(
                SudokuBoard.fromJson(
                        good.replace(
                                "\"notes\":[" + zeros,
                                "\"notes\":[" + "0,0," + "1024," + "0,".repeat(77) + "0")));
        assertNull(
                SudokuBoard.fromJson(
                        good.replace(
                                "\"notes\":[" + zeros,
                                "\"notes\":[" + "2," + "0,".repeat(79) + "0")));
    }

    @Test
    void aReplacedPuzzle_startsFreshAndTakesItsResultOffTheStreak() {
        String replaced = "0" + GIVENS.substring(1);
        SudokuBoard played = SudokuBoard.fresh("2026-11-05", GIVENS);
        mark(played, 2, 4);
        played.recordResult(0, 5);
        SudokuBoard next = SudokuBoard.forOpening(played, "2026-11-05", replaced, true);
        assertEquals(replaced, next.grid());
        assertFalse(next.hasNote(2, 4));
        assertTrue(next.ribbon(true).endsWith("Streak 5"));

        played.recordResult(3, 6);
        assertTrue(
                SudokuBoard.forOpening(played, "2026-11-05", replaced, true)
                        .ribbon(true)
                        .endsWith("Streak 5"));
    }

    @Test
    void anUnsentBoardOfAReplacedPuzzle_doesNotHoldUpTheNewOne() {
        SudokuBoard unsent = solved();
        assertTrue(unsent.isUnsent());
        SudokuBoard next = SudokuBoard.forOpening(unsent, DAY, GIVENS, true);
        assertEquals(GIVENS, next.grid());
        assertFalse(next.isSolved());
        assertNull(next.aside(), "the server would refuse a replaced puzzle's result");
    }

    @Test
    void aReplacedPuzzle_keepsWhatItHeldAside() {
        SudokuBoard replaced = SudokuBoard.forOpening(unsentSolve("2026-10-31"), DAY, GIVENS, true);

        SudokuBoard next = SudokuBoard.forOpening(replaced, DAY, ONE_BLANK, true);

        assertEquals("2026-10-31", next.aside().day());
    }

    // ── Number, ribbon and next puzzle ───────────────────────────────────

    @Test
    void number_countsDaysFromTheFirstPuzzle() {
        assertEquals(1, SudokuBoard.fresh("2026-11-02", GIVENS).number());
        assertEquals(30, SudokuBoard.fresh("2026-12-01", GIVENS).number());
        assertEquals(0, SudokuBoard.fresh("not a date", GIVENS).number());
    }

    @Test
    void ribbon_showsTheStreakOrThatThePlayerIsUnlinked() {
        SudokuBoard board = SudokuBoard.fresh("2026-11-13", GIVENS);
        board.recordResult(1, 3);
        assertEquals("No. 12 - Streak 3", board.ribbon(true));
        assertEquals("No. 12 - Unlinked", board.ribbon(false));
    }

    @Test
    void secondsToNextPuzzle_countsToFourInHalifaxAfterItsDay() {
        // Halifax is UTC-3 in October, so a day's own 04:00 is 07:00Z and its last minute 06:59Z.
        SudokuBoard october = SudokuBoard.fresh("2026-10-20", GIVENS);
        assertEquals(24 * 3600, october.secondsToNextPuzzle(Instant.parse("2026-10-20T07:00:00Z")));
        assertEquals(60, october.secondsToNextPuzzle(Instant.parse("2026-10-21T06:59:00Z")));
        // The day the clocks go back runs 25 hours.
        SudokuBoard fallBack = SudokuBoard.fresh("2026-10-31", GIVENS);
        assertEquals(
                25 * 3600, fallBack.secondsToNextPuzzle(Instant.parse("2026-10-31T07:00:00Z")));
    }

    @Test
    void secondsToNextPuzzle_runsLongOnTheFirstPuzzleAndStopsAtZero() {
        // Launched at noon on the 1st, dated the 2nd, so up until 04:00 on the 3rd (UTC-4).
        SudokuBoard first = SudokuBoard.fresh("2026-11-02", GIVENS);
        assertEquals(40 * 3600, first.secondsToNextPuzzle(Instant.parse("2026-11-01T16:00:00Z")));
        assertEquals(0, first.secondsToNextPuzzle(Instant.parse("2026-11-04T00:00:00Z")));
        assertEquals(0, SudokuBoard.fresh("not a date", GIVENS).secondsToNextPuzzle(Instant.EPOCH));
    }

    // ── Cell roles ───────────────────────────────────────────────────────

    @Test
    void role_withNoSelection_isGivenEntryOrEmpty() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.set(2, 4);
        assertEquals(SudokuBoard.CellRole.GIVEN, board.role(0, -1));
        assertEquals(SudokuBoard.CellRole.ENTRY, board.role(2, -1));
        assertEquals(SudokuBoard.CellRole.EMPTY, board.role(3, -1));
    }

    @Test
    void role_marksTheSelectionItsPeersAndMatchingDigits() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        // Cell 2 is blank: 3 shares its row, 11 its box, 29 its column, and 30 none of them.
        assertEquals(SudokuBoard.CellRole.SELECTED, board.role(2, 2));
        assertEquals(SudokuBoard.CellRole.PEER, board.role(3, 2));
        assertEquals(SudokuBoard.CellRole.PEER, board.role(11, 2));
        assertEquals(SudokuBoard.CellRole.PEER, board.role(29, 2));
        assertEquals(SudokuBoard.CellRole.EMPTY, board.role(30, 2));
        // Cell 0 holds a 5, as does cell 14; cell 1 holds a 3.
        assertEquals(SudokuBoard.CellRole.SAME_DIGIT, board.role(14, 0));
        assertEquals(SudokuBoard.CellRole.GIVEN, board.role(1, 0));
    }

    @Test
    void role_marksARepeatedDigitOnBothCells() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        // Cell 0, in the same row, already holds a 5.
        board.set(2, 5);
        assertEquals(SudokuBoard.CellRole.CONFLICT, board.role(2, -1));
        assertEquals(SudokuBoard.CellRole.CONFLICT, board.role(0, -1));
        assertEquals(SudokuBoard.CellRole.SELECTED, board.role(2, 2));
    }

    // ── Streak across days ───────────────────────────────────────────────

    @Test
    void aStoredResult_survivesTheStoredForm() {
        SudokuBoard board = solved();
        board.markSubmitted();
        board.recordResult(4, 9);
        SudokuBoard read = SudokuBoard.fromJson(board.toJson());
        assertEquals("Solved, rank 4", read.status());
        assertEquals("No. 0 - Streak 9", read.ribbon(true));
    }

    @Test
    void aNewBoard_carriesOnlyYesterdaysStreak() {
        SudokuBoard yesterday = SudokuBoard.fresh("2026-11-02", GIVENS);
        yesterday.recordResult(1, 5);
        assertEquals(
                "No. 2 - Streak 5",
                SudokuBoard.forOpening(yesterday, "2026-11-03", GIVENS, true).ribbon(true));
        assertEquals(
                "No. 3 - Streak 0",
                SudokuBoard.forOpening(yesterday, "2026-11-04", GIVENS, true).ribbon(true));
    }

    @Test
    void aDayWithNoRankedResult_breaksTheStreak() {
        SudokuBoard ranked = SudokuBoard.fresh("2026-11-02", GIVENS);
        ranked.recordResult(1, 5);
        // The next day's board shows the streak it could extend, and is left unsolved.
        SudokuBoard unsolved = SudokuBoard.forOpening(ranked, "2026-11-03", GIVENS, true);
        assertEquals("No. 2 - Streak 5", unsolved.ribbon(true));

        assertEquals(
                "No. 3 - Streak 0",
                SudokuBoard.forOpening(unsolved, "2026-11-04", GIVENS, true).ribbon(true));
    }

    // ── Status ───────────────────────────────────────────────────────────

    @Test
    void status_followsTheBoard() {
        assertEquals("", oneBlank().status());

        SudokuBoard board = solved();
        assertTrue(board.isUnsent());
        assertEquals("Solved, not sent", board.status());

        board.markSubmitted();
        assertFalse(board.isUnsent());
        assertEquals("Solved", board.status());
    }

    @Test
    void status_namesTheRankOnceTheServerGaveOne() {
        SudokuBoard board = solved();
        board.markSubmitted();
        board.recordResult(2, 7);
        assertEquals("Solved, rank 2", board.status());
    }

    @Test
    void anUnsolvedBoard_isNotUnsent() {
        assertFalse(oneBlank().isUnsent());
    }

    // ── The stored form ──────────────────────────────────────────────────

    @Test
    void aBoardRoundTrips() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.setActiveMs(4200);
        board.set(2, 4);

        SudokuBoard read = SudokuBoard.fromJson(board.toJson());

        assertNotNull(read);
        assertEquals(DAY, read.day());
        assertEquals(board.grid(), read.grid());
        assertEquals(5, read.fill(2));
        assertEquals(4200, read.activeMs());
        assertFalse(read.isGiven(2), "an entry stays an entry");
        assertTrue(read.isGiven(0), "a given stays a given");
        assertFalse(read.isSolved());
    }

    @Test
    void aSolvedBoardRoundTrips_withItsResultState() {
        SudokuBoard read = SudokuBoard.fromJson(solved().toJson());
        assertNotNull(read);
        assertTrue(read.isUnsent());

        read.markSubmitted();
        SudokuBoard again = SudokuBoard.fromJson(read.toJson());
        assertNotNull(again);
        assertTrue(again.isSolved());
        assertFalse(again.isUnsent());
    }

    @Test
    void theStoredForm_holdsTheDocumentedKeys() {
        assertEquals(
                "{\"day\":\"2026-11-01\",\"cells\":\""
                        + ONE_BLANK
                        + "\",\"fills\":["
                        + "0,".repeat(80)
                        + "0],\"activeMs\":0,\"solved\":false,\"submitted\":false,"
                        + "\"rank\":0,\"streak\":0,\"notes\":["
                        + "0,".repeat(80)
                        + "0]}",
                oneBlank().toJson());
    }

    @Test
    void nothingStored_readsAsNull() {
        assertNull(SudokuBoard.fromJson(null));
        assertNull(SudokuBoard.fromJson(""));
    }

    @Test
    void textThatIsNoBoard_readsAsNull() {
        String fills = Arrays.toString(new int[81]);
        for (String bad :
                new String[] {
                    "not json {",
                    "[]",
                    "{}",
                    "{\"cells\":\"" + GIVENS + "\",\"fills\":" + fills + "}",
                    "{\"day\":\"d\",\"fills\":" + fills + "}",
                    "{\"day\":\"d\",\"cells\":\"" + GIVENS + "\"}",
                    "{\"day\":\"d\",\"cells\":\"123\",\"fills\":" + fills + "}",
                    "{\"day\":\"d\",\"cells\":\"" + GIVENS + "\",\"fills\":[1,2]}",
                    "{\"day\":\"d\",\"cells\":\"" + GIVENS + "\",\"fills\":[null]}",
                    "{\"day\":\"d\",\"cells\":\""
                            + GIVENS
                            + "\",\"fills\":"
                            + fills
                            + ",\"activeMs\":-1}"
                }) {
            assertNull(SudokuBoard.fromJson(bad), bad);
        }
    }

    @Test
    void aTimeOnABlankOrBelowZero_readsAsNull() {
        int[] onBlank = new int[81];
        onBlank[2] = 3;
        int[] belowZero = new int[81];
        belowZero[2] = -1;
        for (int[] fills : new int[][] {onBlank, belowZero}) {
            assertNull(
                    SudokuBoard.fromJson(
                            "{\"day\":\"d\",\"cells\":\""
                                    + GIVENS
                                    + "\",\"fills\":"
                                    + Arrays.toString(fills)
                                    + "}"));
        }
    }

    @Test
    void aStoredSolvedBoardThatBreaksTheRules_readsAsNull() {
        assertNull(
                SudokuBoard.fromJson(
                        "{\"day\":\"d\",\"cells\":\""
                                + ONE_BLANK
                                + "\",\"fills\":"
                                + Arrays.toString(new int[81])
                                + ",\"solved\":true}"));
    }

    // ── The board a reopen picks ─────────────────────────────────────────

    @Test
    void nothingStored_opensANewBoard() {
        SudokuBoard opened = SudokuBoard.forOpening(null, DAY, GIVENS, true);

        assertEquals(DAY, opened.day());
        assertEquals(GIVENS, opened.grid());
    }

    @Test
    void todaysBoard_carriesOn_finishedOrNot() {
        SudokuBoard inPlay = SudokuBoard.fresh(DAY, GIVENS);
        inPlay.set(2, 4);
        assertSame(inPlay, SudokuBoard.forOpening(inPlay, DAY, GIVENS, false));

        SudokuBoard finished = solved();
        finished.markSubmitted();
        assertSame(finished, SudokuBoard.forOpening(finished, DAY, ONE_BLANK, false));
    }

    @Test
    void anOlderBoardWithNoUnsentSolve_isDropped() {
        SudokuBoard inPlay = SudokuBoard.fresh("2026-10-31", GIVENS);
        SudokuBoard opened = SudokuBoard.forOpening(inPlay, DAY, GIVENS, true);
        assertNotSame(inPlay, opened);
        assertEquals(DAY, opened.day());

        SudokuBoard sent = SudokuBoard.fresh("2026-10-31", ONE_BLANK);
        sent.set(0, 5);
        sent.trySolve();
        sent.markSubmitted();
        assertEquals(DAY, SudokuBoard.forOpening(sent, DAY, GIVENS, true).day());
    }

    @Test
    void anOlderUnsentSolve_isKeptAsideOnlyWhileItCanStillGoOut() {
        SudokuBoard unsent = unsentSolve("2026-10-31");

        SudokuBoard opened = SudokuBoard.forOpening(unsent, DAY, GIVENS, true);
        assertEquals(DAY, opened.day());
        assertEquals(GIVENS, opened.grid());
        assertSame(unsent, opened.aside());

        assertNull(SudokuBoard.forOpening(unsentSolve("2026-10-31"), DAY, GIVENS, false).aside());
    }

    @Test
    void aBoardKeptAside_dropsWhatItHadKeptAside() {
        SudokuBoard yesterday =
                SudokuBoard.forOpening(unsentSolve("2026-10-30"), "2026-10-31", ONE_BLANK, true);
        yesterday.set(0, 5);
        yesterday.trySolve();

        SudokuBoard today = SudokuBoard.forOpening(yesterday, DAY, GIVENS, true);

        assertSame(yesterday, today.aside());
        assertNull(yesterday.aside());
    }

    @Test
    void aBoardKeptAside_survivesTheStoredForm() {
        SudokuBoard opened = SudokuBoard.forOpening(unsentSolve("2026-10-31"), DAY, GIVENS, true);

        SudokuBoard read = SudokuBoard.fromJson(opened.toJson());

        assertEquals(DAY, read.day());
        assertEquals("2026-10-31", read.aside().day());
        assertTrue(read.aside().isUnsent());
        assertEquals(SOLUTION, read.aside().grid());
    }

    @Test
    void aStoredAsideThatIsNotAnUnsentSolve_readsAsNoBoard() {
        String board = oneBlank().toJson();
        String open = board.substring(0, board.length() - 1) + ",\"aside\":";
        // The same splice of an unsent solve reads, so the two below fail on what they hold.
        assertNotNull(SudokuBoard.fromJson(open + unsentSolve("2026-10-31").toJson() + "}"));

        String unsolved = SudokuBoard.fresh("2026-10-31", GIVENS).toJson();
        String sent = solved().toJson().replace("\"submitted\":false", "\"submitted\":true");
        for (String aside : new String[] {unsolved, sent}) {
            assertNull(SudokuBoard.fromJson(open + aside + "}"), aside);
        }
    }

    @Test
    void settlingAnAside_carriesOnItsStreakAndLetsItGo() {
        SudokuBoard yesterday = unsentSolve("2026-11-02");
        SudokuBoard today = SudokuBoard.forOpening(yesterday, "2026-11-03", GIVENS, true);
        assertEquals("No. 2 - Streak 0", today.ribbon(true), "an unsent day carries nothing yet");

        today.settleAside();
        assertSame(yesterday, today.aside(), "an aside still unsent stays");

        yesterday.markSubmitted();
        yesterday.recordResult(2, 6);
        today.settleAside();
        assertNull(today.aside());
        assertEquals("No. 2 - Streak 6", today.ribbon(true));
    }

    @Test
    void settlingAnAside_takesNoStreakOverTheBoardsOwnResult() {
        SudokuBoard yesterday = unsentSolve("2026-11-02");
        SudokuBoard today = SudokuBoard.forOpening(yesterday, "2026-11-03", GIVENS, true);
        today.recordResult(1, 9);
        yesterday.markSubmitted();
        yesterday.recordResult(2, 6);

        today.settleAside();

        assertNull(today.aside());
        assertEquals("No. 2 - Streak 9", today.ribbon(true));
    }

    /** A board of {@code day} solved by its last entry, its result not yet sent. */
    private static SudokuBoard unsentSolve(String day) {
        SudokuBoard board = SudokuBoard.fresh(day, ONE_BLANK);
        board.set(0, 5);
        assertTrue(board.trySolve());
        return board;
    }

    @Test
    void aStoredBoardOfTheSameDayWithOtherGivens_isDropped() {
        SudokuBoard other = SudokuBoard.fresh(DAY, ONE_BLANK);

        SudokuBoard opened = SudokuBoard.forOpening(other, DAY, GIVENS, false);

        assertNotSame(other, opened);
        assertEquals(GIVENS, opened.grid());
    }

    @Test
    void theGivensOfAStoredBoardWithEntries_stillMatchTheirPuzzle() {
        SudokuBoard board = SudokuBoard.fresh(DAY, GIVENS);
        board.set(2, 4);
        SudokuBoard read = SudokuBoard.fromJson(board.toJson());

        assertSame(read, SudokuBoard.forOpening(read, DAY, GIVENS, false));
    }
}
