package org.wynnvets;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.config.VetsConfig;
// New debug subsystem imports — replaces the old single-class DebugCommand
import org.wynnvets.debug.DebugCommands;        // Builds the /wv debug command tree
import org.wynnvets.debug.DebugConfigManager;   // Registers debug-specific config keys
import org.wynnvets.debug.dump.DebugKeyHandler; // Keybind-triggered item/state dump
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.listeners.WynntilsEventListener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.fetcher.ondemand.MotdFetcher;
import org.wynnvets.fetcher.ondemand.ReturnFetcher;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.fetcher.polling.StaffRanksFetcher;
import org.wynnvets.fetcher.ondemand.StaffFetcher;
import org.wynnvets.fetcher.polling.SupportersFetcher;
import org.wynnvets.fetcher.ondemand.UserInfoFetcher;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.fetcher.ondemand.ListFetcher;
import org.wynnvets.fetcher.ondemand.WorldListFetcher;
import org.wynnvets.fetcher.ondemand.StampFetcher;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.rendering.territory.TerritoryLineManager;
import org.wynnvets.rendering.territory.TerritoryLineRenderer;

/**
 * Client-side entry point for the VetsMod Fabric mod.
 *
 * <p>Bootstraps all mod subsystems on client initialisation: configuration,
 * guild state, item definitions, message fetchers, server connection hooks,
 * and the {@code /wv} client command tree. Each subsystem is started in
 * dependency order so that later components can rely on earlier ones.</p>
 */
public class VetsmodClient implements ClientModInitializer {

  @Override
  public void onInitializeClient() {
    VetsLogger.info("Client initializing");

    // Initialise debug config keys *before* VetsConfig.load() so that any
    // persisted debug settings are picked up when config values are read.
    DebugConfigManager.init();
    VetsConfig.load();
    GuildStateManager.loadPersistedState();
    ClientLifecycleEvents.CLIENT_STARTED.register(client -> WynntilsEventListener.register());
    ItemDefinitions.load();

    V1ApiManager.connect();
    OutboundDisplayHandler.register();
    SupportersFetcher.start();
    StaffRanksFetcher.start();
    ServerConnectionListener.register();
    TerritoryLineRenderer.register();
    // Register the debug keybind so users can dump item/state info on demand
    DebugKeyHandler.register();

    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
      dispatcher.register(ClientCommandManager.literal("motd").executes(this::motd));

      // The main gu commands
      dispatcher.register(
          ClientCommandManager.literal("wv")
              // /gu help
              .then(ClientCommandManager.literal("help")
                  .executes(this::help)
                  .then(ClientCommandManager.literal("config")
                      .executes(this::helpConfig))
                  .then(ClientCommandManager.literal("check")
                      .executes(this::helpCheck))
                  .then(ClientCommandManager.literal("return")
                      .executes(this::helpReturn))
                  .then(ClientCommandManager.literal("staff")
                      .executes(this::helpStaff))
                  .then(ClientCommandManager.literal("list")
                      .executes(this::helpList))
                  .then(ClientCommandManager.literal("motd")
                      .executes(this::helpMotd))
                  .then(ClientCommandManager.literal("anni")
                      .executes(this::helpAnni))
                  .then(ClientCommandManager.literal("line")
                      .executes(this::helpLine))
                  .then(ClientCommandManager.literal("debug")
                      .executes(this::helpDebug)))

              // /gu check <playerName>
              .then(ClientCommandManager.literal("check")
                  .requires(this::userIsCaptain)
                  .then(ClientCommandManager.argument("playerName", StringArgumentType.string())
                      .executes(this::check)
                  )
              )

              // /gu return
              .then(ClientCommandManager.literal("return")
                  .requires(this::userIsVet)
                  .executes(this::returnInfo)
              )

              // /wv list
              .then(ClientCommandManager.literal("list")
                  .requires(this::userIsVet)
                  .executes(this::list)
                  .then(ClientCommandManager.literal("world")
                      .requires(this::userIsCaptain)
                      .executes(this::listWorld)
                  )
              )

                // /gu staff
                .then(ClientCommandManager.literal("staff")
                  .requires(this::userIsVet)
                  .executes(this::staff)
                )

              // /gu motd
              .then(ClientCommandManager.literal("motd")
                  .requires(this::userIsVet)
                  .executes(this::motd))

              // /gu anni
              .then(ClientCommandManager.literal("anni")
                  .executes(this::anni))

              // /wv config <key> <value> — toggle user-facing options
              .then(ClientCommandManager.literal("config")
                  .executes(this::configList)
                  .then(ClientCommandManager.argument("key", StringArgumentType.word())
                      .suggests(SUGGEST_CONFIG_KEYS)
                      .executes(this::configGet)
                      .then(ClientCommandManager.argument("value", StringArgumentType.word())
                          .suggests(SUGGEST_CONFIG_VALUES)
                          .executes(this::configSet)
                      )
                  )
              )

              // /wv line church|scrap — toggle territory boundary lines (Returners only)
              .then(ClientCommandManager.literal("line")
                  .then(ClientCommandManager.literal("church")
                      .executes(ctx -> lineToggle(ctx, "church"))
                  )
                  .then(ClientCommandManager.literal("scrap")
                      .executes(ctx -> lineToggle(ctx, "scrap"))
                  )
              )

              // /wv debug — replaced the old inline debug true/false toggle with a
              // full command tree built by DebugCommands.  Supports sub-commands for
              // diagnostics dump, logging toggle, and debug-config management.
              .then(DebugCommands.buildCommandTree())
      );
    });

    VetsLogger.info("Client initialized");
  }

  // TODO: Replace with correct code.
  private boolean userIsCaptain(FabricClientCommandSource src) {
    // TODO: This doesn't work correctly in .requires()
    //if (!org.wynnvets.guild.GuildStateManager.canExecuteCommands()) {
	//	src.sendError(Component.literal("Please wait for a few seconds after joining worlds before using vetsmod commands!"));
	//	return false;
    //}

    return true;
  }

  // TODO: Replace with correct code.
  private boolean userIsVet(FabricClientCommandSource src) {
    // TODO: This doesn't work correctly in .requires()
    //if (!org.wynnvets.guild.GuildStateManager.canExecuteCommands()) {
    //	src.sendError(Component.literal("Please wait for a few seconds after joining worlds before using vetsmod commands!"));
    //	return false;
    //}

    return true;
  }

  // Check information about a player.
  private int check(CommandContext<FabricClientCommandSource> ctx) {
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

  // Get the MOTD information.
  private int motd(CommandContext<FabricClientCommandSource> ctx) {
    // Use guild MOTD for eligible users (Returners, waitlist-unlocked, honourary-unlocked)
    boolean useGuildMotd = GuildStateManager.isReturners()
        || (GuildStateManager.isGuildless() && GuildStateManager.isWaitlistUnlocked())
        || GuildStateManager.isHonouraryUnlocked();

    if (useGuildMotd) {
      MotdFetcher.fetchGuildMotd().thenAccept(guildMotd -> {
        String text = guildMotd.getString();
        if (text != null && !text.isEmpty()) {
          ChatUtils.sendLocalMessage(guildMotd);
        } else {
          // Fall back to standard MOTD if guild MOTD is empty
          MotdFetcher.fetchMotd().thenAccept(motd -> {
            ChatUtils.sendLocalMessage(motd);
          });
        }
      });
    } else {
      MotdFetcher.fetchMotd().thenAccept(motd -> {
        ChatUtils.sendLocalMessage(motd);
      });
    }
    return 1;
  }

  // Get information about the current return.
  private int returnInfo(CommandContext<FabricClientCommandSource> ctx) {
    // Only allow return command if features are enabled (guild is Returners)
    //if (!org.wynnvets.guild.GuildStateManager.areFeaturesEnabled()) {
    //ctx.getSource().sendError(Component.literal("This command is only available for Returners guild members."));
    //return 0;
    //}

    // Fetch return from API and display it
    ReturnFetcher.fetchReturn().thenAccept(returnInfo -> {
      ChatUtils.sendLocalMessage(returnInfo);
    });

    return 1;
  }

  // Get online guild staff information.
  private int staff(CommandContext<FabricClientCommandSource> ctx) {
    if (!GuildStateManager.isUnlocked()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be unlocked to use /wv staff.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    StaffFetcher.fetchOnlineStaff().thenAccept(staffInfo -> {
      ChatUtils.sendLocalMessage(staffInfo);
    });

    return 1;
  }

  // Show online members and VetsMod status.
  private int list(CommandContext<FabricClientCommandSource> ctx) {
    ChatUtils.sendLocalMessage(
        Component.literal("Looking up online members...")
            .withStyle(ChatFormatting.GREEN)
    );

    ListFetcher.fetchList().thenAccept(listInfo -> {
      ChatUtils.sendLocalMessage(listInfo);
    });

    return 1;
  }

  // Show online members grouped by Wynncraft server/world (staff only).
  private int listWorld(CommandContext<FabricClientCommandSource> ctx) {
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

  // Get annihilation timer information.
  private int anni(CommandContext<FabricClientCommandSource> ctx) {
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

  // Return information about how each command is used.
  private int help(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();

    msg.append(Component.literal("——— VetsMod Commands ———\n")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    msg.append(Component.literal("Use /wv help <command> for details on a specific command.\n\n")
        .withStyle(ChatFormatting.GRAY));

    // Public commands (always shown)
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

    // Veteran commands (Returners / waitlist / honourary)
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

    // Unlocked commands
    if (GuildStateManager.isUnlocked()) {
      msg.append(Component.literal("/wv staff")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Show online staff members\n")
          .withStyle(ChatFormatting.GRAY));
    }

    // Returners-only commands
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

    // Staff commands
    if (GuildStateManager.isStaff()) {
      msg.append(Component.literal("/wv check <player>")
          .withStyle(ChatFormatting.YELLOW));
      msg.append(Component.literal(" — Look up a player's eligibility\n")
          .withStyle(ChatFormatting.GRAY));
    }

    // Debug (always available)
    msg.append(Component.literal("/wv debug")
        .withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal(" — Diagnostics & debug tools")
        .withStyle(ChatFormatting.GRAY));

    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  // Explain what each config value does.
  private int helpConfig(CommandContext<FabricClientCommandSource> ctx) {
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

  private int helpCheck(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv check <player>\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Look up a player's guild membership and unlock status.\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Staff").withStyle(ChatFormatting.RED));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private int helpReturn(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv return\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Display information about this week's scheduled event, "
        + "as fetched from the guild-announcements channel.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Returners guild member").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private int helpStaff(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv staff\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show a list of currently online staff members.\n")
        .withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Unlocked").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private int helpList(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv list\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show online Returners members, grouped by VetsMod usage, "
        + "honourary, and waitlist status.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Veteran (Returners, waitlist, or honourary)").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private int helpMotd(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv motd\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show the guild message of the day. Also available as "
        + "a standalone /motd command.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Veteran (Returners, waitlist, or honourary)").withStyle(ChatFormatting.GREEN));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private int helpAnni(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent msg = Component.empty();
    msg.append(Component.literal("/wv anni\n").withStyle(ChatFormatting.YELLOW));
    msg.append(Component.literal("Show how long until the next annihilation event, "
        + "if one has been announced.\n").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("Requires: ").withStyle(ChatFormatting.GRAY));
    msg.append(Component.literal("None (public)").withStyle(ChatFormatting.WHITE));
    ChatUtils.sendLocalMessage(msg);
    return 1;
  }

  private int helpLine(CommandContext<FabricClientCommandSource> ctx) {
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

  private int helpDebug(CommandContext<FabricClientCommandSource> ctx) {
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

  // Toggle territory boundary line display.
  private int lineToggle(CommandContext<FabricClientCommandSource> ctx, String alias) {
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

  // ── /wv config ──────────────────────────────────────────────────────

  /** Tab-completion provider that suggests user-configurable key names. */
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

  /** Tab-completion provider that suggests config values (true/false, plus default for tri-state keys). */
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

  /**
   * {@code /wv config} — lists all user-configurable keys and their current values.
   */
  private int configList(CommandContext<FabricClientCommandSource> ctx) {
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

  /**
   * {@code /wv config <key>} — displays the current value of a single config key.
   */
  private int configGet(CommandContext<FabricClientCommandSource> ctx) {
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

  /**
   * {@code /wv config <key> <value>} — sets a user-facing boolean config key.
   *
   * <p>Only keys listed in {@link VetsConfig#USER_CONFIG_KEYS} can be modified.
   * Internal keys (staff status, timestamps) are never exposed.</p>
   */
  private int configSet(CommandContext<FabricClientCommandSource> ctx) {
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