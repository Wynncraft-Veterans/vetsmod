package org.wynnvets.extras.sudoku;

import com.wynntils.core.text.StyledText;
import com.wynntils.screens.base.WynntilsMenuScreenBase;
import com.wynntils.utils.colors.CommonColors;
import com.wynntils.utils.colors.CustomColor;
import com.wynntils.utils.render.FontRenderer;
import com.wynntils.utils.render.RenderUtils;
import com.wynntils.utils.render.type.HorizontalAlignment;
import com.wynntils.utils.render.type.TextShadow;
import com.wynntils.utils.render.type.VerticalAlignment;
import java.time.Instant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.wynnvets.util.Durations;

/**
 * The daily sudoku in a Wynntils book: the board on the right page, one glass pane per cell with
 * its colour from {@link SudokuBoard.CellRole} and its digit or pencil marks on top, the puzzle's
 * details and the instructions on the left, and the puzzle's number on the bottom ribbon. It sends
 * no packets, so the server cannot tell it is open. See {@code vetsmod_extras.md}.
 *
 * <p>The timer runs between {@link #added()} and {@link #removed()}. Not {@code init}, which runs
 * again on a resize, nor {@code ScreenClosedEvent}, which does not fire when a server menu replaces
 * this screen.</p>
 */
final class SudokuScreen extends WynntilsMenuScreenBase {

    /**
     * The board's corner on the book, which leaves its cells on the right page's plain part; the
     * far line lands on the first stripe past it.
     */
    private static final int BOARD_X = 174;

    private static final int BOARD_Y = 32;

    /** The left page's text, below the title ribbon. */
    private static final int TEXT_X = 20;

    private static final int TEXT_Y = 66;
    private static final int TEXT_WIDTH = 130;

    /** Generous because Wynntils measures a wrapped line 1 px shorter than it draws one. */
    private static final int PARAGRAPH_GAP = 8;

    private static final String RULES =
            ChatFormatting.DARK_GRAY
                    + "Fill every row, column, and 3x3 block with 1 to 9, once each.";
    private static final String CONTROLS =
            "Click a cell, then press a number. Hold Ctrl to pencil. Backspace clears.";
    private static final String NEW_DAILY = ChatFormatting.DARK_GRAY + "New puzzle daily:";
    private static final String NEXT_IN = ChatFormatting.DARK_GRAY + "Next in ";

    /** A pencil mark's size, and the spacing of its three-by-three grid inside a cell. */
    private static final float NOTE_SCALE = 0.5f;

    private static final int NOTE_FIRST = 3;
    private static final int NOTE_PITCH = 5;

    /** The bottom ribbon's text box, where Wynntils' own books print their version. */
    private static final int RIBBON_LEFT = 54;

    private static final int RIBBON_RIGHT = 127;
    private static final int RIBBON_TOP = 196;
    private static final int RIBBON_BOTTOM = 202;
    private static final float RIBBON_SCALE = 0.7f;

    private static final CustomColor BOX_LINE = CommonColors.BLACK;
    private static final CustomColor CELL_LINE = new CustomColor(60, 50, 40);

    /** Behind a digit's pane: Wynncraft's glass is half see-through, and the digits are white. */
    private static final CustomColor DIGIT_BACKING = new CustomColor(0x2B, 0x2B, 0x2B);

    private static final ItemStack EMPTY_GLASS = new ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE);
    private static final ItemStack PEER_GLASS = new ItemStack(Items.WHITE_STAINED_GLASS_PANE);
    private static final ItemStack GIVEN_GLASS = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
    private static final ItemStack ENTRY_GLASS = new ItemStack(Items.BLUE_STAINED_GLASS_PANE);
    private static final ItemStack SAME_DIGIT_GLASS = new ItemStack(Items.CYAN_STAINED_GLASS_PANE);
    private static final ItemStack CONFLICT_GLASS = new ItemStack(Items.RED_STAINED_GLASS_PANE);
    private static final ItemStack SELECTED_GLASS = new ItemStack(Items.YELLOW_STAINED_GLASS_PANE);

    private final SudokuBoard board;
    private final String difficulty;

    /** The selected cell, or -1. */
    private int selected = -1;

    private boolean timing;
    private long timingSinceNanos;

    private SudokuScreen(SudokuBoard board, String difficulty) {
        super(Component.literal("Sudoku"));
        this.board = board;
        this.difficulty = difficulty;
    }

    /**
     * Opens a board in the book.
     *
     * @param board the board
     * @param difficulty the difficulty to show, or {@code null} for a board of an earlier day
     */
    static void open(SudokuBoard board, String difficulty) {
        openBook(new SudokuScreen(board, difficulty));
    }

    // ── Timer ────────────────────────────────────────────────────────────

    @Override
    public void added() {
        super.added();
        if (board.isSolved()) return;
        timing = true;
        timingSinceNanos = System.nanoTime();
    }

    @Override
    public void removed() {
        stopTiming();
        SudokuClient.saveProgress(board);
        super.removed();
    }

    private long activeMs() {
        if (!timing) return board.activeMs();
        return board.activeMs() + (System.nanoTime() - timingSinceNanos) / 1_000_000L;
    }

    /** Banks the time so far, and carries on timing from here. */
    private void bankTime() {
        board.setActiveMs(activeMs());
        timingSinceNanos = System.nanoTime();
    }

    private void stopTiming() {
        bankTime();
        timing = false;
    }

    // ── Input ────────────────────────────────────────────────────────────

    @Override
    public boolean doMouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        int cell =
                SudokuBoard.cellAt(
                        (int) event.x() - offsetX - BOARD_X, (int) event.y() - offsetY - BOARD_Y);
        if (cell >= 0 && !board.isSolved()) {
            selected = cell;
            return true;
        }
        return super.doMouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int digit = SudokuBoard.digitForKey(event.key());
        if (digit < 0 || selected < 0) return super.keyPressed(event);
        // Not Shift, which Windows hides from the number pad while Num Lock is on.
        if (digit > 0 && event.hasControlDown()) {
            if (board.toggleNote(selected, digit)) SudokuClient.saveProgress(board);
            return true;
        }
        bankTime();
        if (board.set(selected, digit)) {
            if (board.trySolve()) {
                stopTiming();
                selected = -1;
                SudokuClient.saveProgress(board);
                SudokuClient.submit(board, () -> {});
            } else {
                SudokuClient.saveProgress(board);
            }
        }
        return true;
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    @Override
    public void doRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackgroundTexture(graphics);
        renderTitle(graphics, "Sudoku");
        renderLeftPage(graphics);
        renderGrid(graphics);
        renderCells(graphics);
        renderRibbon(graphics);
    }

    private void renderLeftPage(GuiGraphics graphics) {
        float y = TEXT_Y + offsetY;
        if (difficulty != null)
            y = renderParagraph(graphics, "Difficulty: " + value(difficulty), y);
        // A solved board's time is the one the server works out, not the banked milliseconds.
        long seconds = board.isSolved() ? board.seconds() : activeMs() / 1000L;
        y = renderParagraph(graphics, "Time: " + value(Durations.minutesSeconds(seconds)), y);
        String status = board.status();
        if (!status.isEmpty()) y = renderParagraph(graphics, status, y);
        y = renderParagraph(graphics, RULES, y + PARAGRAPH_GAP) + PARAGRAPH_GAP;
        y = renderParagraph(graphics, CONTROLS, y) + PARAGRAPH_GAP;
        y = renderParagraph(graphics, NEW_DAILY, y);
        long wait = board.secondsToNextPuzzle(Instant.now());
        renderParagraph(graphics, NEXT_IN + value(Durations.hoursMinutes(wait)), y);
    }

    /** A reading on the left page, in the colour that picks it out from the text around it. */
    private static String value(String text) {
        return ChatFormatting.DARK_RED + text;
    }

    /**
     * Draws wrapped text on the left page.
     *
     * @return where the next paragraph starts
     */
    private float renderParagraph(GuiGraphics graphics, String text, float y) {
        StyledText styled = StyledText.fromString(text);
        FontRenderer fonts = FontRenderer.getInstance();
        fonts.renderAlignedTextInBox(
                graphics,
                styled,
                TEXT_X + offsetX,
                TEXT_X + TEXT_WIDTH + offsetX,
                y,
                TEXT_WIDTH,
                CommonColors.BLACK,
                HorizontalAlignment.LEFT,
                TextShadow.NONE);
        return y + fonts.calculateRenderHeight(styled, TEXT_WIDTH);
    }

    private void renderRibbon(GuiGraphics graphics) {
        boolean canSend = SudokuClient.sendState() != SudokuClient.SendState.NEVER;
        FontRenderer.getInstance()
                .renderAlignedTextInBox(
                        graphics,
                        StyledText.fromString(board.ribbon(canSend)),
                        RIBBON_LEFT + offsetX,
                        RIBBON_RIGHT + offsetX,
                        RIBBON_TOP + offsetY,
                        RIBBON_BOTTOM + offsetY,
                        0,
                        CommonColors.YELLOW,
                        HorizontalAlignment.CENTER,
                        VerticalAlignment.MIDDLE,
                        TextShadow.NORMAL,
                        RIBBON_SCALE);
    }

    private void renderGrid(GuiGraphics graphics) {
        int size = SudokuBoard.BOARD_SIZE;
        for (int line = 0; line <= SudokuBoard.SIZE; line++) {
            int start = SudokuBoard.lineStart(line);
            int thickness = SudokuBoard.lineThickness(line);
            CustomColor colour = line % SudokuBoard.BOX == 0 ? BOX_LINE : CELL_LINE;
            RenderUtils.drawRect(graphics, colour, boardX() + start, boardY(), thickness, size);
            RenderUtils.drawRect(graphics, colour, boardX(), boardY() + start, size, thickness);
        }
    }

    private void renderCells(GuiGraphics graphics) {
        for (int i = 0; i < SudokuBoard.CELLS; i++) {
            int x = cellX(i);
            int y = cellY(i);
            ItemStack glass = glass(board.role(i, selected));
            int digit = board.cell(i);
            if (digit == 0) {
                graphics.renderFakeItem(glass, x, y);
                renderNotes(graphics, i, x, y);
                continue;
            }
            RenderUtils.drawRect(
                    graphics, DIGIT_BACKING, x, y, SudokuBoard.CELL_SIZE, SudokuBoard.CELL_SIZE);
            // Twice, so the colour holds up over the dark backing.
            graphics.renderFakeItem(glass, x, y);
            graphics.renderFakeItem(glass, x, y);
            renderDigit(graphics, digit, x, y);
        }
    }

    /** Each mark keeps its digit's place on a number pad's three rows, read from the top. */
    private void renderNotes(GuiGraphics graphics, int index, int x, int y) {
        for (int digit = 1; digit <= SudokuBoard.SIZE; digit++) {
            if (!board.hasNote(index, digit)) continue;
            int column = (digit - 1) % SudokuBoard.BOX;
            int row = (digit - 1) / SudokuBoard.BOX;
            FontRenderer.getInstance()
                    .renderText(
                            graphics,
                            StyledText.fromString(Integer.toString(digit)),
                            x + NOTE_FIRST + column * NOTE_PITCH,
                            y + NOTE_FIRST + row * NOTE_PITCH,
                            CommonColors.BLACK,
                            HorizontalAlignment.CENTER,
                            VerticalAlignment.MIDDLE,
                            TextShadow.NONE,
                            NOTE_SCALE);
        }
    }

    private static ItemStack glass(SudokuBoard.CellRole role) {
        return switch (role) {
            case EMPTY -> EMPTY_GLASS;
            case PEER -> PEER_GLASS;
            case GIVEN -> GIVEN_GLASS;
            case ENTRY -> ENTRY_GLASS;
            case SAME_DIGIT -> SAME_DIGIT_GLASS;
            case CONFLICT -> CONFLICT_GLASS;
            case SELECTED -> SELECTED_GLASS;
        };
    }

    private static void renderDigit(GuiGraphics graphics, int digit, int x, int y) {
        FontRenderer.getInstance()
                .renderText(
                        graphics,
                        StyledText.fromString(Integer.toString(digit)),
                        x + SudokuBoard.CELL_SIZE / 2f,
                        y + SudokuBoard.CELL_SIZE / 2f,
                        CommonColors.WHITE,
                        HorizontalAlignment.CENTER,
                        VerticalAlignment.MIDDLE,
                        TextShadow.NORMAL);
    }

    private int boardX() {
        return offsetX + BOARD_X;
    }

    private int boardY() {
        return offsetY + BOARD_Y;
    }

    private int cellX(int index) {
        return boardX() + SudokuBoard.cellOrigin(index % SudokuBoard.SIZE);
    }

    private int cellY(int index) {
        return boardY() + SudokuBoard.cellOrigin(index / SudokuBoard.SIZE);
    }
}
