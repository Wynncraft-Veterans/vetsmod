package org.wynnvets.commands;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

/**
 * The client command tree builds: the "does not crash for every user on join" bar. Fabric runs
 * {@link CommandRegistry#register} on every login, for every user, whatever their tier or debug
 * setting, so a throw anywhere in it, debug subtree included, breaks every command. The test
 * builds the whole tree into a fresh dispatcher, as Fabric does, and checks that {@code /wv}
 * holds the subtrees other classes build and that {@code /wv debug tree} holds its two harnesses.
 *
 * <p>No command is run. Building the tree reads only the suggestion providers it attaches; a
 * method reference or lambda does not initialise the class it names until it runs. The
 * {@code registryAccess} argument is not read, so {@code null} stands in for it.</p>
 */
class CommandRegistryTest {

    @Test
    void theWholeTreeBuildsWithEachDelegatedSubtree() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        CommandRegistry.register(dispatcher, null);

        assertNotNull(dispatcher.getRoot().getChild("motd"), "/motd");
        CommandNode<FabricClientCommandSource> wv =
                dispatcher.getRoot().getChild(CommandRegistry.WV);
        assertNotNull(wv, "/wv");
        for (String subtree : List.of("debug", "distribute", "laurels", "retro", "extras")) {
            assertNotNull(wv.getChild(subtree), subtree);
        }
        CommandNode<FabricClientCommandSource> tree = wv.getChild("debug").getChild("tree");
        assertNotNull(tree, "/wv debug tree");
        assertNotNull(tree.getChild("anni"), "/wv debug tree anni");
        assertNotNull(tree.getChild("retro"), "/wv debug tree retro");
    }
}
