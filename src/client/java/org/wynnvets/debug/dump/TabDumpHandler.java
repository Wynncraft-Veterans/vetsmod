package org.wynnvets.debug.dump;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.TabListGuildParser;
import org.wynnvets.logging.VetsLogger;

/**
 * {@code /wv debug trigger tabDump} — prints every tab list entry to chat,
 * grouped into columns of 20, so the raw layout can be inspected.
 */
public final class TabDumpHandler {

    private static final int COLUMN_SIZE = 20;

    private TabDumpHandler() {}

    /**
     * Dumps the full sorted tab list to chat, split into labelled columns.
     */
    public static void execute() {
        List<String> entries = TabListGuildParser.dumpAllEntries();

        if (entries.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Tab list is empty or unavailable.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        ChatUtils.sendLocalMessage(
                Component.literal("——— Tab List Dump (" + entries.size() + " entries) ———")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        String[] columnLabels = {"Friends", "Island Members", "Island Info", "Guild"};

        for (int col = 0; col < 4; col++) {
            int start = col * COLUMN_SIZE;
            int end = Math.min(start + COLUMN_SIZE, entries.size());
            if (start >= entries.size()) break;

            String label = col < columnLabels.length ? columnLabels[col] : "Col " + col;
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "\n▸ Column "
                                            + col
                                            + " — "
                                            + label
                                            + " (indices "
                                            + start
                                            + "–"
                                            + (end - 1)
                                            + ")")
                            .withStyle(ChatFormatting.YELLOW));

            for (int i = start; i < end; i++) {
                ChatFormatting color = (i == start) ? ChatFormatting.AQUA : ChatFormatting.GRAY;
                ChatUtils.sendLocalMessage(
                        Component.literal("  " + entries.get(i)).withStyle(color));
            }
        }

        // If there are entries beyond 80, show them too
        if (entries.size() > 80) {
            ChatUtils.sendLocalMessage(
                    Component.literal("\n▸ Extra entries (80+)").withStyle(ChatFormatting.RED));
            for (int i = 80; i < entries.size(); i++) {
                ChatUtils.sendLocalMessage(
                        Component.literal("  " + entries.get(i)).withStyle(ChatFormatting.GRAY));
            }
        }

        // Also show parsed guild members as a summary
        List<TabListGuildParser.GuildEntry> guild = TabListGuildParser.parseOnlineGuildMembers();
        ChatUtils.sendLocalMessage(
                Component.literal("\n——— Parsed Guild Members (" + guild.size() + ") ———")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

        if (guild.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("  (none parsed)").withStyle(ChatFormatting.GRAY));
        } else {
            for (TabListGuildParser.GuildEntry entry : guild) {
                ChatUtils.sendLocalMessage(
                        Component.literal("  [" + entry.server() + "] " + entry.username())
                                .withStyle(ChatFormatting.AQUA));
            }
        }

        VetsLogger.debug(
                "Tab dump complete: {} total entries, {} guild members parsed",
                entries.size(),
                guild.size());
    }
}
