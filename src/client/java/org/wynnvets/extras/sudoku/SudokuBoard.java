package org.wynnvets.extras.sudoku;

import com.google.gson.JsonParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * One player's board for one day: the digits, when each was set, the pencil marks, the active time
 * so far and how far its result got. {@link SudokuGrid} holds the rules over its digits,
 * {@link SudokuScreen} draws it and {@link SudokuClient} sends it. See {@code vetsmod_extras.md}.
 * Client thread only.
 *
 * <p>A cell is a given when it holds a digit and has no fill time, because an entry's time is
 * always at least 1. That is the shape of the {@code fills} the server takes, and it lets a stored
 * board rebuild itself without a separate copy of the puzzle.</p>
 */
final class SudokuBoard {

    /** What a cell is showing, which picks its glass. */
    enum CellRole {
        /** A blank away from the selection. */
        EMPTY,
        /** A blank in the selected cell's row, column or box. */
        PEER,
        /** A digit that came with the puzzle. */
        GIVEN,
        /** A digit the player set. */
        ENTRY,
        /** A digit matching the selected cell's. */
        SAME_DIGIT,
        /** A digit repeated in its row, column or box. */
        CONFLICT,
        /** The selected cell. */
        SELECTED
    }

    /** Puzzle number 1's day, which the server dates a day ahead of its launch. */
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 2);

    /** Where and when the server rolls over to a new puzzle. */
    private static final ZoneId PUZZLE_ZONE = ZoneId.of("America/Halifax");

    private static final LocalTime ROLLOVER = LocalTime.of(4, 0);

    private static final int CELLS = SudokuGrid.CELLS;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{" + CELLS + "}");

    private final String day;
    private final int[] cells;
    private final int[] fills;

    /** Each blank's pencil marks: bit 1 to bit 9 for the digits marked. */
    private final int[] notes = new int[CELLS];

    /** The puzzle's solution, worked out from its givens on first need; never stored. */
    private int[] solution;

    private long activeMs;
    private boolean solved;
    private boolean submitted;
    private int rank;
    private int streak;

    /**
     * An earlier day's solved board whose result is still to go out, kept aside so that it does
     * not hold up this one, or {@code null}. Never more than one deep.
     */
    private SudokuBoard aside;

    /** The stored form, as {@code sudokuProgress} holds it; {@code aside} is in the same form. */
    private record Stored(
            String day,
            String cells,
            int[] fills,
            long activeMs,
            boolean solved,
            boolean submitted,
            int rank,
            int streak,
            int[] notes,
            Stored aside) {}

    private SudokuBoard(String day, int[] cells, int[] fills) {
        this.day = day;
        this.cells = cells;
        this.fills = fills;
    }

    /**
     * A new board for a puzzle.
     *
     * @param day the puzzle's day
     * @param givens the puzzle's {@value SudokuGrid#CELLS} digits, {@code 0} for a blank
     */
    // Package-private for unit tests. See SudokuBoardTest.
    static SudokuBoard fresh(String day, String givens) {
        return new SudokuBoard(day, SudokuGrid.parse(givens), new int[CELLS]);
    }

    /**
     * Reads a stored board.
     *
     * @param json the stored form, or empty when nothing is stored
     * @return the board, or {@code null} when nothing is stored or the text is not a board this
     *     class could have written
     */
    static SudokuBoard fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        Stored stored;
        try {
            stored = Json.GSON.fromJson(json, Stored.class);
        } catch (JsonParseException | IllegalArgumentException e) {
            // Gson throws the second for a null among the fills.
            stored = null;
        }
        SudokuBoard board = stored == null ? null : fromStored(stored);
        if (board == null) VetsLogger.warn("Stored sudoku progress is unreadable; dropping it");
        return board;
    }

    private static SudokuBoard fromStored(Stored stored) {
        if (stored.day() == null
                || stored.cells() == null
                || !DIGITS.matcher(stored.cells()).matches()
                || stored.fills() == null
                || stored.fills().length != CELLS
                || stored.activeMs() < 0) {
            return null;
        }
        SudokuBoard board =
                new SudokuBoard(
                        stored.day(), SudokuGrid.parse(stored.cells()), stored.fills().clone());
        for (int i = 0; i < CELLS; i++) {
            if (board.fills[i] < 0 || (board.cells[i] == 0 && board.fills[i] != 0)) return null;
        }
        // A board stored before pencil marks has none.
        if (stored.notes() != null && !board.restoreNotes(stored.notes())) return null;
        if (stored.solved() && !SudokuGrid.obeysTheRules(board.cells)) return null;
        board.activeMs = stored.activeMs();
        board.solved = stored.solved();
        board.submitted = stored.submitted();
        board.rank = Math.max(0, stored.rank());
        board.streak = Math.max(0, stored.streak());
        if (stored.aside() != null) {
            board.aside = fromStored(stored.aside());
            if (board.aside == null || !board.aside.isUnsent() || board.aside.aside != null) {
                return null;
            }
        }
        return board;
    }

    /** Takes stored marks, unless they name a non-digit or sit on a filled cell. */
    private boolean restoreNotes(int[] stored) {
        if (stored.length != CELLS) return false;
        for (int i = 0; i < CELLS; i++) {
            if ((stored[i] & ~SudokuGrid.ALL_DIGITS) != 0 || (cells[i] != 0 && stored[i] != 0)) {
                return false;
            }
            notes[i] = stored[i];
        }
        return true;
    }

    /**
     * The board a player opens, always one of the current puzzle: today's stored board carries on,
     * finished or not, and otherwise a new one starts.
     *
     * <p>An older stored board that is solved and can still be sent is kept aside in the new one,
     * so its result can go out first; what it held aside is dropped, its day long closed. Anything
     * else older is dropped, and the new board carries on its streak only if it is the day before
     * and the server ranked its result, since a day with none breaks it. A stored board of the same
     * day with other digits means the server replaced the puzzle: it is dropped too, its result
     * with it, so the streak goes back to before it, and what it held aside stays aside.</p>
     *
     * @param stored the stored board, or {@code null}
     * @param day the current puzzle's day
     * @param givens the current puzzle's digits
     * @param canStillSend whether this player could send a solved board, now or later
     * @return the board to open
     */
    static SudokuBoard forOpening(
            SudokuBoard stored, String day, String givens, boolean canStillSend) {
        if (stored == null) return fresh(day, givens);
        if (stored.day.equals(day) && stored.givens().equals(givens)) return stored;
        SudokuBoard next = fresh(day, givens);
        if (stored.day.equals(day)) {
            next.streak = Math.max(0, stored.streak - (stored.rank > 0 ? 1 : 0));
            next.aside = stored.aside;
            return next;
        }
        if (next.number() == stored.number() + 1 && stored.rank > 0) next.streak = stored.streak;
        if (stored.isUnsent() && canStillSend) {
            stored.aside = null;
            next.aside = stored;
        }
        return next;
    }

    /** The stored form. */
    String toJson() {
        return Json.GSON.toJson(stored());
    }

    private Stored stored() {
        return new Stored(
                day,
                grid(),
                fills,
                activeMs,
                solved,
                submitted,
                rank,
                streak,
                notes,
                aside == null ? null : aside.stored());
    }

    // ── Reading ──────────────────────────────────────────────────────────

    String day() {
        return day;
    }

    /** Whether another board is of the same puzzle: the same day, and the same givens. */
    boolean samePuzzle(SudokuBoard other) {
        return day.equals(other.day) && givens().equals(other.givens());
    }

    /** The digit in a cell, or 0 for a blank. */
    int cell(int index) {
        return cells[index];
    }

    /** The active second a cell was last set at, or 0 on a given or a blank. */
    int fill(int index) {
        return fills[index];
    }

    /** Whether a blank has a pencil mark for a digit. */
    boolean hasNote(int index, int digit) {
        return (notes[index] & 1 << digit) != 0;
    }

    /** Whether a cell came with the puzzle. */
    // Package-private for unit tests. See SudokuBoardTest.
    boolean isGiven(int index) {
        return cells[index] != 0 && fills[index] == 0;
    }

    /** The active time banked so far, in milliseconds. */
    long activeMs() {
        return activeMs;
    }

    boolean isSolved() {
        return solved;
    }

    /** Whether the board is solved and its result is still to go out. */
    boolean isUnsent() {
        return solved && !submitted;
    }

    /** The earlier day's board kept aside until its result goes out, or {@code null}. */
    SudokuBoard aside() {
        return aside;
    }

    /**
     * The cells as {@value SudokuGrid#CELLS} digits, which on a solved board is the {@code grid}
     * sent.
     */
    String grid() {
        StringBuilder grid = new StringBuilder(CELLS);
        for (int digit : cells) {
            grid.append(digit);
        }
        return grid.toString();
    }

    /** The puzzle's own digits, with every entry blanked. */
    private String givens() {
        StringBuilder givens = new StringBuilder(CELLS);
        for (int i = 0; i < CELLS; i++) {
            givens.append(isGiven(i) ? cells[i] : 0);
        }
        return givens.toString();
    }

    /** The time this board took: the second its last cell was set, as the server works it out. */
    int seconds() {
        int seconds = 0;
        for (int fill : fills) {
            seconds = Math.max(seconds, fill);
        }
        return seconds;
    }

    /** The line for the book's left page: how far this board has got, or empty while in play. */
    String status() {
        if (solved && !submitted) return "Solved, not sent";
        if (solved) return rank > 0 ? "Solved, rank " + rank : "Solved";
        return isFull() ? "Not solved yet" : "";
    }

    /** The puzzle's number, counting days from the first; 0 for a day that is not a date. */
    // Package-private for unit tests. See SudokuBoardTest.
    int number() {
        try {
            return (int) ChronoUnit.DAYS.between(FIRST_DAY, LocalDate.parse(day)) + 1;
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /**
     * The line for the book's bottom ribbon.
     *
     * @param canSend whether this player's results reach the server, now or later
     */
    String ribbon(boolean canSend) {
        return "No. " + number() + " - " + (canSend ? "Streak " + streak : "Unlinked");
    }

    /**
     * The seconds from {@code now} until the puzzle after this one, at the rollover after its day.
     *
     * @return the wait, or 0 once that has passed or for a day that is not a date
     */
    long secondsToNextPuzzle(Instant now) {
        try {
            Instant next =
                    LocalDate.parse(day)
                            .plusDays(1)
                            .atTime(ROLLOVER)
                            .atZone(PUZZLE_ZONE)
                            .toInstant();
            return Math.max(0L, Duration.between(now, next).toSeconds());
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    /**
     * What a cell is showing, the first that applies: the selection, a repeated digit, a digit
     * matching the selection's, a given or entry, then a blank near the selection or not.
     *
     * @param index the cell
     * @param selected the selected cell, or -1
     */
    CellRole role(int index, int selected) {
        if (index == selected) return CellRole.SELECTED;
        int digit = cells[index];
        if (digit == 0) {
            return selected >= 0 && SudokuGrid.sharesAUnit(index, selected)
                    ? CellRole.PEER
                    : CellRole.EMPTY;
        }
        if (isRepeated(index)) return CellRole.CONFLICT;
        if (selected >= 0 && cells[selected] == digit) return CellRole.SAME_DIGIT;
        return isGiven(index) ? CellRole.GIVEN : CellRole.ENTRY;
    }

    private boolean isRepeated(int index) {
        for (int other = 0; other < CELLS; other++) {
            if (other != index
                    && cells[other] == cells[index]
                    && SudokuGrid.sharesAUnit(index, other)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFull() {
        for (int digit : cells) {
            if (digit == 0) return false;
        }
        return true;
    }

    // ── Changing ─────────────────────────────────────────────────────────

    /** Banks the active time so far; {@link #set} stamps a cell with it. */
    void setActiveMs(long activeMs) {
        this.activeMs = activeMs;
    }

    /**
     * Sets or clears a cell at the banked time. A digit replaces the cell's pencil marks and takes
     * itself out of the marks in its row, column and box, and clearing a blank clears its marks. A
     * given and a solved board ignore it.
     *
     * @param index the cell
     * @param digit the digit, or 0 to clear the cell
     * @return whether the board changed
     */
    boolean set(int index, int digit) {
        if (solved || isGiven(index)) return false;
        if (cells[index] == digit) {
            if (digit != 0 || notes[index] == 0) return false;
            notes[index] = 0;
            return true;
        }
        cells[index] = digit;
        fills[index] = digit == 0 ? 0 : fillSeconds(activeMs);
        notes[index] = 0;
        if (digit != 0) {
            for (int other = 0; other < CELLS; other++) {
                if (SudokuGrid.sharesAUnit(index, other)) notes[other] &= ~(1 << digit);
            }
        }
        return true;
    }

    /**
     * Pencils a digit into cells: takes it out of every blank among them that has it if they all
     * do, and otherwise adds it to each. Filled cells are skipped, and it stamps no time.
     *
     * @param indexes the cells
     * @param digit the digit, 1 to 9
     * @return whether the board changed, which it does unless no blank is among them
     */
    boolean toggleNotes(int[] indexes, int digit) {
        if (solved) return false;
        boolean anyBlank = false;
        boolean allMarked = true;
        for (int index : indexes) {
            if (cells[index] != 0) continue;
            anyBlank = true;
            allMarked &= hasNote(index, digit);
        }
        if (!anyBlank) return false;
        for (int index : indexes) {
            if (cells[index] != 0) continue;
            notes[index] = allMarked ? notes[index] & ~(1 << digit) : notes[index] | 1 << digit;
        }
        return true;
    }

    /**
     * Pencils every blank with the digits its row, column and box leave open, replacing its marks.
     *
     * @return whether the board changed
     */
    boolean fillNotes() {
        if (solved) return false;
        int[] open = SudokuGrid.openDigits(cells);
        boolean changed = false;
        for (int i = 0; i < CELLS; i++) {
            if (cells[i] != 0) continue;
            changed |= notes[i] != open[i];
            notes[i] = open[i];
        }
        return changed;
    }

    /**
     * A cell worth looking at next: the first entry that is wrong, else the first blank only one
     * digit can fill ({@link #isForced}), else the first blank with fewest digits open.
     *
     * @return the cell, or -1 on a solved board
     */
    int hintCell() {
        if (solved || solution() == null) return -1;
        for (int i = 0; i < CELLS; i++) {
            if (isWrong(i)) return i;
        }
        int[] open = SudokuGrid.openDigits(cells);
        int fewest = -1;
        for (int i = 0; i < CELLS; i++) {
            if (cells[i] != 0) continue;
            if (SudokuGrid.isForced(open, i)) return i;
            if (fewest < 0 || Integer.bitCount(open[i]) < Integer.bitCount(open[fewest])) {
                fewest = i;
            }
        }
        return fewest;
    }

    /** Whether a cell holds a digit other than the solution's. */
    boolean isWrong(int index) {
        return cells[index] != 0 && solution() != null && cells[index] != solution()[index];
    }

    /**
     * Whether only one digit can fill a blank, by the digits its row, column and box leave open:
     * it has one, or one of them fits nowhere else in one of those.
     */
    boolean isForced(int index) {
        return cells[index] == 0 && SudokuGrid.isForced(SudokuGrid.openDigits(cells), index);
    }

    /** The solution, or null if the givens have none, which a server puzzle never lacks. */
    private int[] solution() {
        if (solution == null) solution = SudokuGrid.solve(SudokuGrid.parse(givens()));
        return solution;
    }

    /**
     * A cell's fill for the time it was set: never 0, which marks a given, so a first entry inside
     * the first second cannot make the board unsubmittable.
     */
    // Package-private for unit tests. See SudokuBoardTest.
    static int fillSeconds(long activeMs) {
        return (int) Math.max(1, Math.ceilDiv(activeMs, 1000L));
    }

    /**
     * Marks the board solved if it is.
     *
     * @return whether this call solved it
     */
    boolean trySolve() {
        if (solved || !SudokuGrid.obeysTheRules(cells)) return false;
        solved = true;
        return true;
    }

    /** Marks the result as finished with, whether the server took it or refused it for good. */
    void markSubmitted() {
        submitted = true;
    }

    /** Keeps what the server said of this result, for the book to show. */
    void recordResult(int rank, int streak) {
        this.rank = rank;
        this.streak = streak;
    }

    /**
     * Lets go of the board kept aside once its result is finished with, and carries on its streak
     * if it was the day before and this board has no result of its own yet. An aside still unsent
     * stays.
     */
    void settleAside() {
        if (aside == null || aside.isUnsent()) return;
        if (rank == 0 && aside.rank > 0 && number() == aside.number() + 1) streak = aside.streak;
        aside = null;
    }
}
