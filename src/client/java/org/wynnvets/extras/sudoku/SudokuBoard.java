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
import org.lwjgl.glfw.GLFW;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.util.Json;

/**
 * One player's board for one day: the digits, when each was set, the active time so far and how
 * far its result got. It touches no Minecraft or Wynntils class, so the rules, the geometry and
 * the stored form can all be tested; {@link SudokuScreen} draws it and {@link SudokuClient} sends
 * it. See {@code vetsmod_extras.md}. Client thread only.
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

    /** The cells on a side of the board. */
    static final int SIZE = 9;

    /** The cells on the board. */
    static final int CELLS = SIZE * SIZE;

    /** The side of a cell's item square. */
    static final int CELL_SIZE = 16;

    /** The cells on a side of a box. */
    static final int BOX = 3;

    /** From one cell to the next, with the one-pixel line between them. */
    private static final int PITCH = CELL_SIZE + 1;

    /** The side of the drawn board, outer lines included. */
    static final int BOARD_SIZE = cellOrigin(SIZE - 1) + CELL_SIZE + 1;

    /** Bits 1 to 9: what a row, column or box holds when it is complete. */
    private static final int ALL_DIGITS = 0b11_1111_1110;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{" + CELLS + "}");

    private final String day;
    private final int[] cells;
    private final int[] fills;
    private long activeMs;
    private boolean solved;
    private boolean submitted;
    private int rank;
    private int streak;

    /** The stored form, as {@code sudokuProgress} holds it. */
    private record Stored(
            String day,
            String cells,
            int[] fills,
            long activeMs,
            boolean solved,
            boolean submitted,
            int rank,
            int streak) {}

    private SudokuBoard(String day, int[] cells, int[] fills) {
        this.day = day;
        this.cells = cells;
        this.fills = fills;
    }

    /**
     * A new board for a puzzle.
     *
     * @param day the puzzle's day
     * @param givens the puzzle's {@value #CELLS} digits, {@code 0} for a blank
     */
    static SudokuBoard fresh(String day, String givens) {
        return new SudokuBoard(day, digitsOf(givens), new int[CELLS]);
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
                new SudokuBoard(stored.day(), digitsOf(stored.cells()), stored.fills().clone());
        for (int i = 0; i < CELLS; i++) {
            if (board.fills[i] < 0 || (board.cells[i] == 0 && board.fills[i] != 0)) return null;
        }
        if (stored.solved() && !board.obeysTheRules()) return null;
        board.activeMs = stored.activeMs();
        board.solved = stored.solved();
        board.submitted = stored.submitted();
        board.rank = Math.max(0, stored.rank());
        board.streak = Math.max(0, stored.streak());
        return board;
    }

    private static int[] digitsOf(String digits) {
        int[] parsed = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            parsed[i] = digits.charAt(i) - '0';
        }
        return parsed;
    }

    /**
     * The board a player opens, by the first row that applies: a board with no stored one is new;
     * today's stored board carries on, finished or not; an older solved board that can still be
     * sent is kept, since a new one would take its place in the one stored slot before it went
     * out; anything else older is dropped, and a new board carries on the streak of the day before
     * its own.
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
        if (stored.isUnsent() && canStillSend) return stored;
        SudokuBoard next = fresh(day, givens);
        if (next.number() == stored.number() + 1) next.streak = stored.streak;
        return next;
    }

    /** The stored form. */
    String toJson() {
        return Json.GSON.toJson(
                new Stored(day, grid(), fills, activeMs, solved, submitted, rank, streak));
    }

    // ── Reading ──────────────────────────────────────────────────────────

    String day() {
        return day;
    }

    /** The digit in a cell, or 0 for a blank. */
    int cell(int index) {
        return cells[index];
    }

    /** The active second a cell was last set at, or 0 on a given or a blank. */
    int fill(int index) {
        return fills[index];
    }

    /** Whether a cell came with the puzzle. */
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

    /** The cells as {@value #CELLS} digits, which on a solved board is the {@code grid} sent. */
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
            return selected >= 0 && sharesAUnit(index, selected) ? CellRole.PEER : CellRole.EMPTY;
        }
        if (isRepeated(index)) return CellRole.CONFLICT;
        if (selected >= 0 && cells[selected] == digit) return CellRole.SAME_DIGIT;
        return isGiven(index) ? CellRole.GIVEN : CellRole.ENTRY;
    }

    private boolean isRepeated(int index) {
        for (int other = 0; other < CELLS; other++) {
            if (other != index && cells[other] == cells[index] && sharesAUnit(index, other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesAUnit(int a, int b) {
        int rowA = a / SIZE;
        int columnA = a % SIZE;
        int rowB = b / SIZE;
        int columnB = b % SIZE;
        return rowA == rowB
                || columnA == columnB
                || (rowA / BOX == rowB / BOX && columnA / BOX == columnB / BOX);
    }

    private boolean isFull() {
        for (int digit : cells) {
            if (digit == 0) return false;
        }
        return true;
    }

    /** Whether every row, column and box holds each digit once. A blank fails it. */
    private boolean obeysTheRules() {
        for (int unit = 0; unit < SIZE; unit++) {
            int row = 0;
            int column = 0;
            int box = 0;
            int boxOrigin = unit / BOX * BOX * SIZE + unit % BOX * BOX;
            for (int k = 0; k < SIZE; k++) {
                row |= 1 << cells[unit * SIZE + k];
                column |= 1 << cells[k * SIZE + unit];
                box |= 1 << cells[boxOrigin + k / BOX * SIZE + k % BOX];
            }
            if (row != ALL_DIGITS || column != ALL_DIGITS || box != ALL_DIGITS) return false;
        }
        return true;
    }

    // ── Changing ─────────────────────────────────────────────────────────

    /** Banks the active time so far; {@link #set} stamps a cell with it. */
    void setActiveMs(long activeMs) {
        this.activeMs = activeMs;
    }

    /**
     * Sets or clears a cell at the banked time. A given and a solved board ignore it.
     *
     * @param index the cell
     * @param digit the digit, or 0 to clear the cell
     * @return whether the board changed
     */
    boolean set(int index, int digit) {
        if (solved || isGiven(index) || cells[index] == digit) return false;
        cells[index] = digit;
        fills[index] = digit == 0 ? 0 : fillSeconds(activeMs);
        return true;
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
        if (solved || !obeysTheRules()) return false;
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

    // ── Input and geometry ───────────────────────────────────────────────

    /**
     * What a key does to the selected cell.
     *
     * @param key a GLFW key code
     * @return the digit a number key sets, 0 for a key that clears the cell, or -1 for any other
     */
    static int digitForKey(int key) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) return key - GLFW.GLFW_KEY_0;
        if (key >= GLFW.GLFW_KEY_KP_1 && key <= GLFW.GLFW_KEY_KP_9) return key - GLFW.GLFW_KEY_KP_0;
        return key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE ? 0 : -1;
    }

    /** Where a row's or column's item square starts, from the board's edge. */
    static int cellOrigin(int line) {
        return 1 + line * PITCH + line / BOX;
    }

    /**
     * Where the grid line before a row or column starts, from the board's edge.
     *
     * @param line 0 to {@value #SIZE}, the last being the board's far edge
     */
    static int lineStart(int line) {
        return line == SIZE ? BOARD_SIZE - 1 : cellOrigin(line) - lineThickness(line);
    }

    /** The thickness of the grid line before a row or column: 2 between boxes, otherwise 1. */
    static int lineThickness(int line) {
        return line % BOX == 0 && line % SIZE != 0 ? 2 : 1;
    }

    /**
     * The cell at a point, from the board's corner.
     *
     * @return the cell's index, or -1 off the board or on a grid line
     */
    static int cellAt(int x, int y) {
        int column = lineAt(x);
        int row = lineAt(y);
        return column < 0 || row < 0 ? -1 : row * SIZE + column;
    }

    private static int lineAt(int position) {
        for (int line = 0; line < SIZE; line++) {
            int offset = position - cellOrigin(line);
            if (offset >= 0 && offset < CELL_SIZE) return line;
        }
        return -1;
    }
}
