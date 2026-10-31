package org.wynnvets.debug.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

/**
 * The {@code /wv debug tree retro} tree builds, which is what runs when the client joins a
 * server, and has the shape {@link RetroDebugCommands#buildCommandTree()}'s Javadoc gives it: a
 * usage line on {@code retro}, and the leaves {@code report} and {@code report ingredients}. The
 * commands themselves are not run; building the tree touches neither Minecraft nor Wynntils.
 */
class RetroDebugCommandsTest {

    @Test
    void treeBuildsWithItsDocumentedShape() {
        LiteralCommandNode<FabricClientCommandSource> retro =
                RetroDebugCommands.buildCommandTree().build();

        assertEquals("retro", retro.getName());
        assertNotNull(retro.getCommand(), "a bare retro prints its usage line");
        assertEquals(List.of("report"), names(retro));
        CommandNode<FabricClientCommandSource> report = retro.getChild("report");
        assertNotNull(report.getCommand());
        assertEquals(List.of("ingredients"), names(report));
        CommandNode<FabricClientCommandSource> ingredients = report.getChild("ingredients");
        assertNotNull(ingredients.getCommand());
        assertEquals(List.of(), names(ingredients));
    }

    private static List<String> names(CommandNode<FabricClientCommandSource> node) {
        return node.getChildren().stream().map(CommandNode::getName).toList();
    }
}
