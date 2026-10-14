package org.wynnvets.debug.anni;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

/**
 * The {@code /wv debug tree anni} harness builds its tree, which is what runs when the client
 * joins a server, and the tree has the shape that {@link AnniDebugCommands#buildCommandTree()}'s
 * Javadoc states.
 *
 * <p>The commands themselves are not run here. Building the tree initialises
 * {@link AnniDebugSuggestions}, whose providers it reads, but none of the handler classes: a method
 * reference does not initialise its target class until it is invoked. That matters for
 * {@link AnniSnapshotDebugCommands}, whose initialiser resolves the game directory and would throw
 * outside a launch.</p>
 */
class AnniDebugCommandsTest {

    @Test
    void treeBuildsWithItsDocumentedShape() {
        LiteralCommandNode<FabricClientCommandSource> anni =
                AnniDebugCommands.buildCommandTree().build();

        assertEquals("anni", anni.getName());
        assertEquals(11, anni.getChildren().size());
        assertEquals(24, executableNodes(anni));
    }

    private static int executableNodes(CommandNode<FabricClientCommandSource> node) {
        int n = node.getCommand() != null ? 1 : 0;
        for (CommandNode<FabricClientCommandSource> child : node.getChildren()) {
            n += executableNodes(child);
        }
        return n;
    }
}
