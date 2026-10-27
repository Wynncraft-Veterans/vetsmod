package org.wynnvets.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.wynntils.utils.mc.McUtils;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.wynnvets.logging.VetsLogger;

/**
 * Keeps tab completion for the {@code /wv} tree in step with the tree's {@code .requires} gates.
 *
 * <p>The chat box completes and highlights a command from the vanilla dispatcher, not from the
 * client tree. Fabric copies the client tree into it when a {@code minecraft:commands} packet
 * arrives, leaving out each node whose {@code .requires} fails at that moment, while a typed
 * command is parsed against the client tree itself, with every gate tested live. A gate that opens
 * after the packet therefore leaves its node runnable but missing from completion, and the chat
 * box marks it red as an incorrect argument. {@code /wv distribute} and {@code /wv check} hit this
 * after joining Wynncraft: the join's packet arrives before the auth ack can confirm staff.</p>
 *
 * <p>Each client tick, while connected, this walks the {@code /wv} tree beside its copy. A node
 * whose gate passes but which has no copy, or whose gate fails but which has one, makes the copy
 * stale, and {@code ClientCommandManager.refreshCommandCompletions()} rebuilds it from the last
 * packet with every gate tested again. Comparing against the copy itself, rather than against the
 * gates' last values, needs no list of the signals the gates read, and leaves no window in which
 * a packet can land between a gate's change and the check.</p>
 *
 * <p>Only {@code /wv} is walked: the root {@code /motd} has no gate. A rebuild that leaves the copy
 * stale gives up until the next login rather than rebuilding every tick. That needs a server node
 * with a gated-out node's name in the same place, which the walk cannot tell from vetsmod's own;
 * Wynncraft has no {@code /wv} command, so it is not expected.</p>
 */
public final class CommandCompletionSync {

    private static volatile boolean registered = false;

    /** The client tree whose copy a rebuild could not fix, or {@code null}. Fabric builds a new
     *  tree on every login, which retries. Touched only on the client thread. */
    private static CommandDispatcher<FabricClientCommandSource> givenUpOn;

    private CommandCompletionSync() {}

    /** Idempotent registration. */
    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        VetsLogger.debug("CommandCompletionSync registered");
    }

    private static void tick() {
        try {
            syncIfStale();
        } catch (RuntimeException e) {
            VetsLogger.debug("CommandCompletionSync.tick failed: {}", e.getMessage());
        }
    }

    private static void syncIfStale() {
        ClientPacketListener connection = McUtils.mc().getConnection();
        CommandDispatcher<FabricClientCommandSource> dispatcher =
                ClientCommandManager.getActiveDispatcher();
        if (connection == null || dispatcher == null || dispatcher == givenUpOn) return;
        // Fabric's own source for a client command, as its copy and its executor use.
        FabricClientCommandSource source =
                (FabricClientCommandSource) connection.getSuggestionsProvider();
        if (!wvIsStale(connection, dispatcher, source)) return;

        ClientCommandManager.refreshCommandCompletions();
        if (wvIsStale(connection, dispatcher, source)) {
            givenUpOn = dispatcher;
            VetsLogger.warn(
                    "Tab completion for /wv still disagrees with its gates after a rebuild; not"
                            + " retrying until the next login");
        } else {
            VetsLogger.debug("Rebuilt tab completion: a /wv gate changed since the last packet");
        }
    }

    /** {@link #isStale} for the {@code /wv} subtree. {@code false} while the copy holds no
     *  {@code /wv}: no command-tree packet has arrived yet, and Fabric tests every gate when one
     *  does. */
    private static boolean wvIsStale(
            ClientPacketListener connection,
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            FabricClientCommandSource source) {
        CommandNode<FabricClientCommandSource> wv =
                dispatcher.getRoot().getChild(CommandRegistry.WV);
        CommandNode<?> copy = connection.getCommands().getRoot().getChild(CommandRegistry.WV);
        return wv != null && copy != null && isStale(wv, copy, source);
    }

    /**
     * Whether {@code copy}'s children disagree with {@code original}'s gates for {@code source}:
     * some child of {@code original} that {@code source} may use has no same-named child in
     * {@code copy}, or some child it may not use has one. Recurses into every child both hold.
     */
    // Package-private for unit tests. See CommandCompletionSyncTest.
    static <S> boolean isStale(CommandNode<S> original, CommandNode<?> copy, S source) {
        for (CommandNode<S> child : original.getChildren()) {
            CommandNode<?> copied = copy.getChild(child.getName());
            if (child.canUse(source) != (copied != null)) return true;
            if (copied != null && isStale(child, copied, source)) return true;
        }
        return false;
    }
}
