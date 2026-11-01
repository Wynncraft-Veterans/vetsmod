package org.wynnvets.extras;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

/**
 * The {@code /wv extras} tree builds with the shape {@link ExtrasCommands#buildCommandTree()}'s
 * Javadoc gives it. The commands themselves are not run: an answer goes through Wynntils' chat.
 */
class ExtrasCommandsTest {

    @Test
    void treeBuildsWithItsDocumentedShape() {
        LiteralCommandNode<FabricClientCommandSource> extras =
                ExtrasCommands.buildCommandTree().build();

        assertEquals("extras", extras.getName());
        assertNotNull(extras.getCommand(), "a bare extras prints its usage line");
        assertEquals(
                List.of("sudoku"),
                extras.getChildren().stream().map(CommandNode::getName).toList());
        assertNotNull(extras.getChild("sudoku").getCommand(), "sudoku opens the board");
        assertTrue(extras.getChild("sudoku").getChildren().isEmpty());
    }

    @Test
    void everyoneCanPlay_soNothingGatesTheTree() {
        LiteralCommandNode<FabricClientCommandSource> extras =
                ExtrasCommands.buildCommandTree().build();

        assertTrue(extras.getRequirement().test(null), "extras");
        assertTrue(extras.getChild("sudoku").getRequirement().test(null), "sudoku");
    }
}
