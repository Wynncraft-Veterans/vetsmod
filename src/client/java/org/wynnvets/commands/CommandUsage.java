package org.wynnvets.commands;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;

/**
 * The command for a node typed without the argument it needs.
 *
 * <p>Fabric runs a typed client command against the client tree, and when Brigadier reports it
 * unknown or incomplete, Fabric sends it on to the server instead, which answers "Invalid
 * command". A node that has children but no command of its own is incomplete when typed bare, so
 * without one the line leaves the client. A node given {@link #usage} keeps it local and prints a
 * red usage line in its place.</p>
 */
public final class CommandUsage {

    private CommandUsage() {}

    /** A command that prints {@code "Usage: " + command} in red and returns 0. */
    public static Command<FabricClientCommandSource> usage(String command) {
        return ctx -> {
            ChatUtils.sendLocalMessage(
                    Component.literal("Usage: " + command).withStyle(ChatFormatting.RED));
            return 0;
        };
    }
}
