package org.wynnvets.extras.sudoku;

/**
 * Sudoku's rules over a grid of {@value #CELLS} digits, row by row with {@code 0} for a blank:
 * which cells share a row, column or box, the digits a blank has open, whether a grid is solved,
 * and its solution. A set of digits is an {@code int} with bits 1 to 9 for the digits in it.
 */
final class SudokuGrid {

    /** The cells on a side of the board. */
    static final int SIZE = 9;

    /** The cells on a side of a box. */
    static final int BOX = 3;

    /** The cells on the board. */
    static final int CELLS = SIZE * SIZE;

    /** Bits 1 to 9: what a row, column or box holds when it is complete. */
    static final int ALL_DIGITS = 0b11_1111_1110;

    private SudokuGrid() {}

    /** A grid from its {@value #CELLS} digits. */
    static int[] parse(String digits) {
        int[] grid = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            grid[i] = digits.charAt(i) - '0';
        }
        return grid;
    }

    /** Whether two cells share a row, a column or a box. */
    static boolean sharesAUnit(int a, int b) {
        return sameUnit(0, a, b) || sameUnit(1, a, b) || sameUnit(2, a, b);
    }

    /** The digits a blank's row, column and box leave open. */
    static int openDigits(int[] grid, int index) {
        int used = 0;
        for (int other = 0; other < CELLS; other++) {
            if (other != index && sharesAUnit(index, other)) used |= 1 << grid[other];
        }
        return ALL_DIGITS & ~used;
    }

    /** Each blank's {@link #openDigits}, and 0 on a filled cell. */
    static int[] openDigits(int[] grid) {
        int[] open = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            if (grid[i] == 0) open[i] = openDigits(grid, i);
        }
        return open;
    }

    /**
     * Whether only one digit can fill a blank: it has one open digit, or one of its open digits
     * is open nowhere else in its row, its column or its box.
     *
     * @param open the grid's {@link #openDigits(int[])}
     */
    static boolean isForced(int[] open, int index) {
        if (Integer.bitCount(open[index]) == 1) return true;
        for (int digit = 1; digit <= SIZE; digit++) {
            if ((open[index] & 1 << digit) == 0) continue;
            for (int unit = 0; unit < 3; unit++) {
                if (isOnlyPlaceFor(open, index, digit, unit)) return true;
            }
        }
        return false;
    }

    private static boolean isOnlyPlaceFor(int[] open, int index, int digit, int unit) {
        for (int other = 0; other < CELLS; other++) {
            if (other != index && sameUnit(unit, index, other) && (open[other] & 1 << digit) != 0) {
                return false;
            }
        }
        return true;
    }

    /** Row, column or box: unit 0, 1 or 2. */
    private static boolean sameUnit(int unit, int a, int b) {
        return switch (unit) {
            case 0 -> a / SIZE == b / SIZE;
            case 1 -> a % SIZE == b % SIZE;
            default -> a / SIZE / BOX == b / SIZE / BOX && a % SIZE / BOX == b % SIZE / BOX;
        };
    }

    /** Whether every row, column and box holds each digit once. A blank fails it. */
    static boolean obeysTheRules(int[] grid) {
        for (int unit = 0; unit < SIZE; unit++) {
            int row = 0;
            int column = 0;
            int box = 0;
            int boxOrigin = unit / BOX * BOX * SIZE + unit % BOX * BOX;
            for (int k = 0; k < SIZE; k++) {
                row |= 1 << grid[unit * SIZE + k];
                column |= 1 << grid[k * SIZE + unit];
                box |= 1 << grid[boxOrigin + k / BOX * SIZE + k % BOX];
            }
            if (row != ALL_DIGITS || column != ALL_DIGITS || box != ALL_DIGITS) return false;
        }
        return true;
    }

    /**
     * A grid's solution.
     *
     * @return the solved grid, or {@code null} if it has none
     */
    static int[] solve(int[] grid) {
        int[] solved = grid.clone();
        return fillIn(solved) ? solved : null;
    }

    /** Fills a grid in place, the blank with fewest digits open first; false if it has none. */
    private static boolean fillIn(int[] grid) {
        int best = -1;
        int bestOpen = 0;
        for (int i = 0; i < CELLS; i++) {
            if (grid[i] != 0) continue;
            int open = openDigits(grid, i);
            if (best < 0 || Integer.bitCount(open) < Integer.bitCount(bestOpen)) {
                best = i;
                bestOpen = open;
                if (Integer.bitCount(open) <= 1) break;
            }
        }
        if (best < 0) return true;
        for (int digit = 1; digit <= SIZE; digit++) {
            if ((bestOpen & 1 << digit) == 0) continue;
            grid[best] = digit;
            if (fillIn(grid)) return true;
        }
        grid[best] = 0;
        return false;
    }
}
