package org.wynnvets.commands;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;

/**
 * Handlers for the {@code /wv help} subcommand tree.
 *
 * <p>Each method builds a formatted help message and sends it to the local
 * player.  All methods are package-private so they can be referenced from
 * {@link CommandRegistry} without being part of the public API.</p>
 */
final class HelpCommands {

  private HelpCommands() {}

  static int help(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();

    msg.append(Component.literal("——— VetsMod Commands ———\n")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    msg.append(Component.literal("Use /wv help <command> for details on a specific command.\n\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("/wv help")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Show this help message\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("/wv anni")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Show annihilation timer\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("/wv list")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Show online members and VetsMod status\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("/wv config [<key> [<value>]]")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — View or change mod settings\n")
        .withStyle(ChatFormatting.GRAY));

    boolean isVet = GuildStateManager.isReturners()
        || (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked())
        || GuildStateManager.isHonouraryUnlocked();

    if (isVet) {
      msg.append(Component.literal("/wv motd")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Show the message of the day\n")
          .withStyle(ChatFormatting.GRAY));

      msg.append(Component.literal("/wv list")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Show online members and VetsMod status\n")
          .withStyle(ChatFormatting.GRAY));
    }

    if (GuildStateManager.isUnlocked()) {
      msg.append(Component.literal("/wv staff")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Show online staff members\n")
          .withStyle(ChatFormatting.GRAY));
    }

    if (GuildStateManager.areFeaturesEnabled()) {
      msg.append(Component.literal("/wv return")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Show info about this week's event\n")
          .withStyle(ChatFormatting.GRAY));

      msg.append(Component.literal("/wv line <church|scrap>")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Toggle gxp boundary lines\n")
          .withStyle(ChatFormatting.GRAY));
    }

    if (GuildStateManager.isStaff()) {
      msg.append(Component.literal("/wv check <player>")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Look up a player's eligibility\n")
          .withStyle(ChatFormatting.GRAY));
    }

    msg.append(Component.literal("/wv debug")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Diagnostics & debug tools")
        .withStyle(ChatFormatting.GRAY));

    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpConfig(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();

    msg.append(Component.literal("——— VetsMod Config Options ———\n")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    msg.append(Component.literal("Toggle with: /wv config <key> <true|false>\n\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("legacyItemHighlighting\n")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Show legacy item highlighting in tooltips and inventory\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("printMOTD\n")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Auto-print the message of the day on world join\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("printANNI\n")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Auto-print the annihilation timer on world join\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("printBridgeMessages\n")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Show bridge (guild chat relay) messages in chat\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("showSupporterGlints\n")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Show supporter animated gradient glints on nametags\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("handleSpoilers\n")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Render ||spoiler|| markers as hoverable spoiler labels\n")
        .withStyle(ChatFormatting.GRAY));

    msg.append(Component.literal("moreReliableGuildCheck\n")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Run /gu stats on world join to detect guild membership,")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal(" instead of relying solely on Wynntils (which can stay null)")
        .withStyle(ChatFormatting.GRAY));

    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpCheck(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv check <player>\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Look up a player's guild membership and unlock status.\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Staff").withStyle(ChatFormatting.RED));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpReturn(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv return\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Display information about this week's scheduled event, "
        + "as fetched from the guild-announcements channel.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Returners guild member").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpStaff(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv staff\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show a list of currently online staff members.\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Unlocked").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpList(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv list\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show online Returners members, grouped by VetsMod usage, "
        + "honourary, and waitlist status.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Veteran (Returners, waitlist, or honourary)").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpMotd(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv motd\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show the guild message of the day. Also available as "
        + "a standalone /motd command.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Veteran (Returners, waitlist, or honourary)").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpAnni(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv anni\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show how long until the next annihilation event, "
        + "if one has been announced.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("None (public)").withStyle(ChatFormatting.WHITE));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpLine(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv line <church|scrap>\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Toggle the rendering of territory boundary lines for the "
        + "specified territory. Use \"church\" or \"scrap\" to pick which boundaries to show.\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Returners guild member").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpDebug(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv debug\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Run diagnostics and dump mod state to chat and log.\n\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Subcommands:\n").withStyle(ChatFormatting.GOLD));
    msg.append(Component.literal("/wv debug <boolean>").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Toggle debug logging\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("/wv debug set <key> <value>").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Manage debug config keys\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("/wv debug trigger <action>").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Run a one-shot debug action\n\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Use /wv help debug <set|trigger> for details.\n")
        .withStyle(ChatFormatting.DARK_GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("None (public)").withStyle(ChatFormatting.WHITE));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpDebugSet(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv debug set [<key> [<value>]]\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Manage debug-only configuration keys.\n\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Usage:\n").withStyle(ChatFormatting.GOLD));
    msg.append(Component.literal("/wv debug set").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — List all debug config keys and their values\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("/wv debug set <key>").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Show the current value of a key\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("/wv debug set <key> <true|false>").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Set a debug key to true or false\n\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Available keys:\n").withStyle(ChatFormatting.GOLD));
    msg.append(Component.literal("itemDump").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — When true, pressing numpad + while hovering\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("  an item dumps its full component tree\n")
        .withStyle(ChatFormatting.DARK_GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("None (public)").withStyle(ChatFormatting.WHITE));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  static int helpDebugTrigger(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv debug trigger <action>\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Run a one-shot debug action.\n\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Actions:\n").withStyle(ChatFormatting.GOLD));
    msg.append(Component.literal("/wv debug trigger charDump\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Render PUA characters U+E001–U+E040 in the resource\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("  pack's chat/prefix font as badge-style sequences.\n")
        .withStyle(ChatFormatting.DARK_GRAY));
    msg.append(Component.literal("/wv debug trigger forceChecks\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("  Force re-check of guild membership, rank, and staff\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("  status. Runs /gu stats and /gu rank via Wynntils'\n")
        .withStyle(ChatFormatting.DARK_GRAY));
    msg.append(Component.literal("  command queue and reports state to chat.\n")
        .withStyle(ChatFormatting.DARK_GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("None (public)").withStyle(ChatFormatting.WHITE));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }
}
