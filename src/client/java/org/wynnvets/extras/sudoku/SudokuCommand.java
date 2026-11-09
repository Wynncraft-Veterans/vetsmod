package org.wynnvets.extras.sudoku;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wynntils.utils.mc.McUtils;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.util.TickDelays;

/**
 * Builds {@code /wv extras sudoku} and runs its open: any result still unsent goes first, then the
 * day's puzzle is fetched and its board opens. See {@code vetsmod_extras.md}.
 */
public final class SudokuCommand {

    /**
     * How long after the fetch the board opens. Wynntils waits the same two ticks for its own
     * command screens, so the chat screen closing behind the command cannot overwrite it.
     */
    private static final long OPEN_DELAY_MS = 100;

    private SudokuCommand() {}

    /** Builds the {@code sudoku} literal. Everyone can play, so nothing gates it. */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("sudoku").executes(SudokuCommand::run);
    }

    private static int run(CommandContext<FabricClientCommandSource> ctx) {
        SudokuBoard stored = SudokuClient.loadProgress();
        if (stored != null && SudokuClient.sendState() == SudokuClient.SendState.READY) {
            SudokuClient.submitUnsent(stored, SudokuCommand::fetchAndOpen);
        } else {
            fetchAndOpen();
        }
        return 1;
    }

    private static void fetchAndOpen() {
        SudokuClient.fetchPuzzle()
                .thenAccept(
                        puzzle ->
                                TickDelays.runAfter(
                                        OPEN_DELAY_MS, "sudoku open", () -> open(puzzle)));
    }

    /**
     * Opens the board, unless something else is on screen by now. Replacing a server's container
     * screen would send no close packet and leave the server and the client out of step.
     */
    private static void open(SudokuClient.Puzzle puzzle) {
        if (puzzle == null) {
            ChatUtils.sendLocalMessage(SudokuClient.unavailableLine());
            return;
        }
        if (!canOpenOver(McUtils.screen())) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Close this menu and run it again.")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        SudokuBoard board =
                SudokuBoard.forOpening(
                        SudokuClient.loadProgress(),
                        puzzle.day(),
                        puzzle.givens(),
                        SudokuClient.sendState() != SudokuClient.SendState.NEVER);
        SudokuScreen.open(board, puzzle.difficultyLabel());
    }

    /** Whether the board may open over {@code screen}. */
    private static boolean canOpenOver(Screen screen) {
        return screen == null || screen instanceof ChatScreen;
    }
}
