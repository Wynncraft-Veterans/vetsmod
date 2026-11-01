package org.wynnvets.extras;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.wynnvets.commands.CommandUsage;
import org.wynnvets.extras.sudoku.SudokuCommand;

/**
 * Builds {@code /wv extras}, the home of the easter eggs. It is unlisted in {@code /wv help}, like
 * {@code /wv invite-force}. See {@code vetsmod_extras.md}.
 */
public final class ExtrasCommands {

    private ExtrasCommands() {}

    /**
     * Builds the {@code extras} literal. Typed bare it prints a red usage line
     * ({@link CommandUsage}), so it stays on the client instead of going to the server.
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("extras")
                .executes(CommandUsage.usage("/wv extras sudoku"))
                .then(SudokuCommand.buildCommandTree());
    }
}
