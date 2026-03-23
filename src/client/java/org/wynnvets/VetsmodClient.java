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
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.listeners.WynntilsEventListener;
import org.wynnvets.logging.DebugCommand;
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
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
      dispatcher.register(ClientCommandManager.literal("motd").executes(this::motd));

      // The main gu commands
      dispatcher.register(
          ClientCommandManager.literal("wv")
              // /gu help
              .then(ClientCommandManager.literal("help")
                  .executes(this::help))

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
                          .suggests(SUGGEST_BOOLEAN_VALUES)
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

              // /wv debug [true|false]
              .then(ClientCommandManager.literal("debug")
                  .executes(ctx -> { DebugCommand.execute(null); return 1; })
                  .then(ClientCommandManager.argument("enabled", StringArgumentType.word())
                      .executes(ctx -> {
                        DebugCommand.execute(StringArgumentType.getString(ctx, "enabled"));
                        return 1;
                      })
                  )
              )
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
    // Only allow MOTD command if features are enabled (guild is Returners)
    //if (!org.wynnvets.guild.GuildStateManager.areFeaturesEnabled()) {
    //	ctx.getSource().sendError(Component.literal("This command is only available for Returners guild members."));
    //	return 0;
    //}

    // Fetch MOTD from API and display it
    MotdFetcher.fetchMotd().thenAccept(motd -> {
      ChatUtils.sendLocalMessage(motd);
    });
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
    ChatUtils.sendLocalMessage(Component.literal("VetsMod Help! More information to come soon."));

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

  /** Tab-completion provider that suggests "true" / "false". */
  private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_BOOLEAN_VALUES =
      (ctx, builder) -> {
        String partial = builder.getRemaining().toLowerCase();
        if ("true".startsWith(partial)) builder.suggest("true");
        if ("false".startsWith(partial)) builder.suggest("false");
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
      boolean value = VetsConfig.get(key);
      ChatUtils.sendLocalMessage(
          Component.literal("  " + key + " = ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(String.valueOf(value))
                  .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
      );
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

    boolean value = VetsConfig.get(key);
    ChatUtils.sendLocalMessage(
        Component.literal(key + " = ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(value))
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
    );
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