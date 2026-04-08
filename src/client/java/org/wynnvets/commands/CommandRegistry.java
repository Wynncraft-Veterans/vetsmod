package org.wynnvets.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.debug.DebugCommands;
import org.wynnvets.fetcher.ondemand.ListFetcher;
import org.wynnvets.fetcher.ondemand.MotdFetcher;
import org.wynnvets.fetcher.ondemand.ReturnFetcher;
import org.wynnvets.fetcher.ondemand.StaffFetcher;
import org.wynnvets.fetcher.ondemand.StampFetcher;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.fetcher.ondemand.WorldListFetcher;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.rendering.territory.TerritoryLineManager;

/**
 * Builds and registers the {@code /wv} client command tree and its handlers.
 *
 * <p>All command handlers are static — they read guild/config state through
 * the existing singletons ({@link GuildStateManager}, {@link VetsConfig})
 * and delegate API calls to the appropriate fetcher classes.</p>
 *
 * <p>Called from {@link org.wynnvets.VetsmodClient#onInitializeClient()} via
 * {@code ClientCommandRegistrationCallback}.</p>
 */
public final class CommandRegistry {

  private CommandRegistry() {}

  // ── Registration ────────────────────────────────────────────────────

  /**
   * Builds and registers all client commands with the Brigadier dispatcher.
   *
   * @param dispatcher     the Brigadier command dispatcher
   * @param registryAccess the registry access context
   */
  public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
                              CommandBuildContext registryAccess) {
    dispatcher.register(ClientCommandManager.literal("motd").executes(CommandRegistry::motd));

    dispatcher.register(
        ClientCommandManager.literal("wv")
            // /wv help
            .then(ClientCommandManager.literal("help")
                .executes(CommandRegistry::help)
                .then(ClientCommandManager.literal("config")
                    .executes(CommandRegistry::helpConfig))
                .then(ClientCommandManager.literal("check")
                    .executes(CommandRegistry::helpCheck))
                .then(ClientCommandManager.literal("return")
                    .executes(CommandRegistry::helpReturn))
                .then(ClientCommandManager.literal("staff")
                    .executes(CommandRegistry::helpStaff))
                .then(ClientCommandManager.literal("list")
                    .executes(CommandRegistry::helpList))
                .then(ClientCommandManager.literal("motd")
                    .executes(CommandRegistry::helpMotd))
                .then(ClientCommandManager.literal("anni")
                    .executes(CommandRegistry::helpAnni))
                .then(ClientCommandManager.literal("line")
                    .executes(CommandRegistry::helpLine))
                .then(ClientCommandManager.literal("debug")
                    .executes(CommandRegistry::helpDebug)))

            // /wv check <playerName>
            .then(ClientCommandManager.literal("check")
                .requires(CommandRegistry::userIsCaptain)
                .then(ClientCommandManager.argument("playerName", StringArgumentType.string())
                    .executes(CommandRegistry::check)
                )
            )

            // /wv return
            .then(ClientCommandManager.literal("return")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::returnInfo)
            )

            // /wv list
            .then(ClientCommandManager.literal("list")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::list)
                .then(ClientCommandManager.literal("world")
                    .requires(CommandRegistry::userIsCaptain)
                    .executes(CommandRegistry::listWorld)
                )
            )

            // /wv staff
            .then(ClientCommandManager.literal("staff")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::staff)
            )

            // /wv motd
            .then(ClientCommandManager.literal("motd")
                .requires(CommandRegistry::userIsVet)
                .executes(CommandRegistry::motd))

            // /wv anni
            .then(ClientCommandManager.literal("anni")
                .executes(CommandRegistry::anni))

            // /wv config
            .then(ClientCommandManager.literal("config")
                .executes(CommandRegistry::configList)
                .then(ClientCommandManager.argument("key", StringArgumentType.word())
                    .suggests(SUGGEST_CONFIG_KEYS)
                    .executes(CommandRegistry::configGet)
                    .then(ClientCommandManager.argument("value", StringArgumentType.word())
                        .suggests(SUGGEST_CONFIG_VALUES)
                        .executes(CommandRegistry::configSet)
                    )
                )
            )

            // /wv line church|scrap
            .then(ClientCommandManager.literal("line")
                .then(ClientCommandManager.literal("church")
                    .executes(ctx -> lineToggle(ctx, "church"))
                )
                .then(ClientCommandManager.literal("scrap")
                    .executes(ctx -> lineToggle(ctx, "scrap"))
                )
            )

            // /wv debug — full sub-tree built by DebugCommands
            .then(DebugCommands.buildCommandTree())
    );
  }

  // ── Permission predicates ───────────────────────────────────────────

  // TODO: Replace with correct permission checks once available.
  private static boolean userIsCaptain(FabricClientCommandSource src) {
    return true;
  }

  // TODO: Replace with correct permission checks once available.
  private static boolean userIsVet(FabricClientCommandSource src) {
    return true;
  }

  // ── Command handlers ────────────────────────────────────────────────

  private static int check(CommandContext<FabricClientCommandSource> ctx) {
    boolean isCurrentlyStaff = GuildStateManager.isStaff();
    boolean refreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

    if (refreshStarted || GuildStateManager.isCheckingStaffStatus()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Checking staff permissions, please retry in a moment.")
              .withStyle(ChatFormatting.YELLOW)
      );
      return 0;
    }

    if (!GuildStateManager.isStaff()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be staff to use /wv check.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    UserInfoFetcher.checkUser(StringArgumentType.getString(ctx, "playerName"))
        .thenAccept(userInfo -> ChatUtils.sendLocalMessage(userInfo));
    return 1;
  }

  private static int motd(CommandContext<FabricClientCommandSource> ctx) {
    boolean useGuildMotd = GuildStateManager.isReturners()
        || (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked())
        || GuildStateManager.isHonouraryUnlocked();

    if (useGuildMotd) {
      MotdFetcher.fetchGuildMotd().thenAccept(guildMotd -> {
        String text = guildMotd.getString();
        if (text != null && !text.isEmpty()) {
          ChatUtils.sendLocalMessage(guildMotd);
        } else {
          MotdFetcher.fetchMotd().thenAccept(motd -> ChatUtils.sendLocalMessage(motd));
        }
      });
    } else {
      MotdFetcher.fetchMotd().thenAccept(motd -> ChatUtils.sendLocalMessage(motd));
    }
    return 1;
  }

  private static int returnInfo(CommandContext<FabricClientCommandSource> ctx) {
    ReturnFetcher.fetchReturn().thenAccept(returnInfo -> ChatUtils.sendLocalMessage(returnInfo));
    return 1;
  }

  private static int staff(CommandContext<FabricClientCommandSource> ctx) {
    if (!GuildStateManager.isUnlocked()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be unlocked to use /wv staff.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    StaffFetcher.fetchOnlineStaff().thenAccept(staffInfo -> ChatUtils.sendLocalMessage(staffInfo));
    return 1;
  }

  private static int list(CommandContext<FabricClientCommandSource> ctx) {
    ChatUtils.sendLocalMessage(
        Component.literal("Looking up online members...")
            .withStyle(ChatFormatting.GREEN)
    );

    ListFetcher.fetchList().thenAccept(listInfo -> ChatUtils.sendLocalMessage(listInfo));
    return 1;
  }

  private static int listWorld(CommandContext<FabricClientCommandSource> ctx) {
    boolean isCurrentlyStaff = GuildStateManager.isStaff();
    boolean refreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

    if (refreshStarted || GuildStateManager.isCheckingStaffStatus()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Checking staff permissions, please retry in a moment.")
              .withStyle(ChatFormatting.YELLOW)
      );
      return 0;
    }

    if (!GuildStateManager.isStaff()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be staff to use /wv list world.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    WorldListFetcher.fetchWorldList();
    return 1;
  }

  private static int anni(CommandContext<FabricClientCommandSource> ctx) {
    StampFetcher.fetchStampAndCreateAnniCommandMessage().thenAccept(stampMessage -> {
      if (stampMessage != null) {
        ChatUtils.sendLocalMessage(stampMessage);
      } else {
        ChatUtils.sendLocalMessage(
            Component.literal("Annihilation timer is currently unavailable.")
                .withStyle(ChatFormatting.YELLOW)
        );
      }
    });
    return 1;
  }

  private static int lineToggle(CommandContext<FabricClientCommandSource> ctx, String alias) {
    if (!GuildStateManager.areFeaturesEnabled()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be a Returners guild member to use /wv line.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }
    TerritoryLineManager.toggle(alias);
    return 1;
  }

  // ── Help handlers ───────────────────────────────────────────────────

  private static int help(CommandContext<FabricClientCommandSource> ctx) {
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

  private static int helpConfig(CommandContext<FabricClientCommandSource> ctx) {
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
    msg.append(Component.literal("  Render ||spoiler|| markers as hoverable spoiler labels")
        .withStyle(ChatFormatting.GRAY));

    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private static int helpCheck(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv check <player>\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Look up a player's guild membership and unlock status.\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Staff").withStyle(ChatFormatting.RED));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private static int helpReturn(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv return\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Display information about this week's scheduled event, "
        + "as fetched from the guild-announcements channel.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Returners guild member").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private static int helpStaff(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv staff\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show a list of currently online staff members.\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Unlocked").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private static int helpList(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv list\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show online Returners members, grouped by VetsMod usage, "
        + "honourary, and waitlist status.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Veteran (Returners, waitlist, or honourary)").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private static int helpMotd(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv motd\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show the guild message of the day. Also available as "
        + "a standalone /motd command.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Veteran (Returners, waitlist, or honourary)").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private static int helpAnni(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv anni\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show how long until the next annihilation event, "
        + "if one has been announced.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("None (public)").withStyle(ChatFormatting.WHITE));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private static int helpLine(CommandContext<FabricClientCommandSource> ctx) {
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

  private static int helpDebug(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv debug\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Run diagnostics and dump mod state to chat and log.\n\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Subcommands:\n").withStyle(ChatFormatting.GOLD));
    msg.append(Component.literal("/wv debug true|false").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Toggle debug logging\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("/wv debug set [<key> [<value>]]").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Manage debug config keys\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("/wv debug trigger charDump").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Render PUA icon characters\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("/wv debug trigger forceChecks").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Force guild state refresh\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("None (public)").withStyle(ChatFormatting.WHITE));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  // ── Config commands ─────────────────────────────────────────────────

  private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_CONFIG_KEYS =
      (ctx, builder) -> {
        String partial = builder.getRemaining().toLowerCase();
        for (String key : VetsConfig.USER_CONFIG_KEYS) {
          if (key.toLowerCase().startsWith(partial)) {
            builder.suggest(key);
          }
        }
        return builder.buildFuture();
      };

  private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_CONFIG_VALUES =
      (ctx, builder) -> {
        String partial = builder.getRemaining().toLowerCase();
        if ("true".startsWith(partial)) builder.suggest("true");
        if ("false".startsWith(partial)) builder.suggest("false");
        try {
          String key = StringArgumentType.getString(ctx, "key");
          if (VetsConfig.isTriStateKey(key) && "default".startsWith(partial)) {
            builder.suggest("default");
          }
        } catch (IllegalArgumentException ignored) {
          // Key argument not yet entered
        }
        return builder.buildFuture();
      };

  private static int configList(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent header = Component.literal("VetsMod Configuration:")
        .withStyle(ChatFormatting.GOLD);
    ChatUtils.sendLocalMessage(header);

    for (String key : VetsConfig.USER_CONFIG_KEYS) {
      if (VetsConfig.isTriStateKey(key)) {
        Boolean triValue = VetsConfig.getTriState(key);
        String display = triValue == null ? "default" : String.valueOf(triValue);
        ChatFormatting color = triValue == null ? ChatFormatting.YELLOW
            : (triValue ? ChatFormatting.GREEN : ChatFormatting.RED);
        ChatUtils.sendLocalMessage(
            Component.literal("  " + key + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(display).withStyle(color))
        );
      } else {
        boolean value = VetsConfig.get(key);
        ChatUtils.sendLocalMessage(
            Component.literal("  " + key + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(value))
                    .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
        );
      }
    }
    return 1;
  }

  private static int configGet(CommandContext<FabricClientCommandSource> ctx) {
    String key = StringArgumentType.getString(ctx, "key");

    if (!VetsConfig.isUserConfigKey(key)) {
      ChatUtils.sendLocalMessage(
          Component.literal("Unknown config key: " + key)
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    if (VetsConfig.isTriStateKey(key)) {
      Boolean triValue = VetsConfig.getTriState(key);
      String display = triValue == null ? "default" : String.valueOf(triValue);
      ChatFormatting color = triValue == null ? ChatFormatting.YELLOW
          : (triValue ? ChatFormatting.GREEN : ChatFormatting.RED);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " = ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(display).withStyle(color))
      );
    } else {
      boolean value = VetsConfig.get(key);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " = ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(String.valueOf(value))
                  .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
      );
    }
    return 1;
  }

  private static int configSet(CommandContext<FabricClientCommandSource> ctx) {
    String key = StringArgumentType.getString(ctx, "key");
    String rawValue = StringArgumentType.getString(ctx, "value");

    if (!VetsConfig.isUserConfigKey(key)) {
      ChatUtils.sendLocalMessage(
          Component.literal("Unknown config key: " + key)
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    if (VetsConfig.isTriStateKey(key)) {
      Boolean triValue;
      if ("default".equalsIgnoreCase(rawValue)) {
        triValue = null;
      } else if ("true".equalsIgnoreCase(rawValue)) {
        triValue = Boolean.TRUE;
      } else if ("false".equalsIgnoreCase(rawValue)) {
        triValue = Boolean.FALSE;
      } else {
        ChatUtils.sendLocalMessage(
            Component.literal("Value must be 'true', 'false', or 'default'.")
                .withStyle(ChatFormatting.RED)
        );
        return 0;
      }
      VetsConfig.setTriState(key, triValue);
      String display = triValue == null ? "default" : String.valueOf(triValue);
      ChatFormatting color = triValue == null ? ChatFormatting.YELLOW
          : (triValue ? ChatFormatting.GREEN : ChatFormatting.RED);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " set to ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(display).withStyle(color))
      );
      return 1;
    }

    if (!"true".equalsIgnoreCase(rawValue) && !"false".equalsIgnoreCase(rawValue)) {
      ChatUtils.sendLocalMessage(
          Component.literal("Value must be 'true' or 'false'.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    boolean value = Boolean.parseBoolean(rawValue);
    VetsConfig.set(key, value);

    ChatUtils.sendLocalMessage(
        Component.literal(key + " set to ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(value))
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
    );
    return 1;
  }
}
