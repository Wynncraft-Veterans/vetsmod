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
 * The {@code /wv extras} tree from {@link ExtrasCommands#buildCommandTree()}: that every node,
 * {@code extras}, {@code sudoku}, {@code panorama} and its {@code on} and {@code off}, runs when
 * typed bare, and that no {@code requires} gates any of them.
 *
 * <p>A node with no command of its own makes brigadier report an unknown command, and Fabric
 * forwards such a client command to the server, so a bare {@code /wv extras} would leave the
 * client instead of printing its usage line. Several {@code /wv} subtrees are gated on the
 * player's guild standing; this one is open to everyone. The commands themselves are not run:
 * an answer goes through Wynntils' chat.</p>
 */
class ExtrasCommandsTest {

    private static LiteralCommandNode<FabricClientCommandSource> extras() {
        return ExtrasCommands.buildCommandTree().build();
    }

    private static List<String> names(CommandNode<FabricClientCommandSource> node) {
        return node.getChildren().stream().map(CommandNode::getName).toList();
    }

    @Test
    void bothNodesRunWhenTypedBare() {
        LiteralCommandNode<FabricClientCommandSource> extras = extras();
        CommandNode<FabricClientCommandSource> sudoku = extras.getChild("sudoku");

        assertEquals("extras", extras.getName());
        assertNotNull(extras.getCommand(), "a bare extras prints its usage line");
        assertNotNull(sudoku, "extras has a sudoku child");
        assertNotNull(sudoku.getCommand(), "sudoku opens the board");
    }

    @Test
    void thePanoramaAndItsOnAndOffRunWhenTypedBare() {
        CommandNode<FabricClientCommandSource> panorama = extras().getChild("panorama");

        assertNotNull(panorama, "extras has a panorama child");
        assertNotNull(panorama.getCommand(), "a bare panorama says whether it is on");
        assertEquals(List.of("on", "off"), names(panorama));
        for (String leaf : List.of("on", "off")) {
            assertNotNull(panorama.getChild(leaf).getCommand(), leaf);
            assertEquals(List.of(), names(panorama.getChild(leaf)), leaf);
        }
    }

    @Test
    void everyoneCanPlay_soNothingGatesTheTree() {
        LiteralCommandNode<FabricClientCommandSource> extras = extras();
        CommandNode<FabricClientCommandSource> panorama = extras.getChild("panorama");

        assertTrue(extras.getRequirement().test(null), "extras");
        assertTrue(extras.getChild("sudoku").getRequirement().test(null), "sudoku");
        assertTrue(panorama.getRequirement().test(null), "panorama");
        for (String leaf : List.of("on", "off")) {
            assertTrue(panorama.getChild(leaf).getRequirement().test(null), leaf);
        }
    }
}
