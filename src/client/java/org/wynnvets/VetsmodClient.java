package org.wynnvets;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.util.MotdFetcher;
import org.wynnvets.util.ReturnFetcher;
import org.wynnvets.util.ChatMessageFetcher;
import org.wynnvets.util.UserInfo;

public class VetsmodClient implements ClientModInitializer {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");

  @Override
  public void onInitializeClient() {
    LOGGER.info("Initializing VetsMod client");

    // Load configuration from file
    VetsConfig.load();
    LOGGER.info("Configuration loaded");

    // Start the chat message fetcher
    ChatMessageFetcher.start();
    LOGGER.info("Started chat message fetcher");

    // Register server connection listener for auto-MOTD
    ServerConnectionListener.register();
    LOGGER.info("Registered server connection listener");

    // Register client-side commands using the Fabric client command API
    LOGGER.info("Registering client commands");
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

              // /gu motd
              .then(ClientCommandManager.literal("motd")
                  .requires(this::userIsVet)
                  .executes(this::motd))
      );
    });
  }

  // TODO: Replace with correct code.
  private boolean userIsCaptain(FabricClientCommandSource src) {
    // TODO: This doesn't work correctly in .requires()
    //if (!org.wynnvets.util.GuildInfoListener.canExecuteCommands()) {
    //	src.sendError(Component.literal("Please wait for a few seconds after joining worlds before using vetsmod commands!"));
    //	return false;
    //}

    return true;
  }

  // TODO: Replace with correct code.
  private boolean userIsVet(FabricClientCommandSource src) {
    // TODO: This doesn't work correctly in .requires()
    //if (!org.wynnvets.util.GuildInfoListener.canExecuteCommands()) {
    //	src.sendError(Component.literal("Please wait for a few seconds after joining worlds before using vetsmod commands!"));
    //	return false;
    //}

    return true;
  }

  // Check information about a player.
  private int check(CommandContext<FabricClientCommandSource> ctx) {
    UserInfo.wynnAge(StringArgumentType.getString(ctx, "playerName"))
        .thenAccept(userInfo -> ctx.getSource().sendFeedback(userInfo));
    return 1;
  }

  // Get the MOTD information.
  private int motd(CommandContext<FabricClientCommandSource> ctx) {
    // Only allow MOTD command if features are enabled (guild is Returners)
    //if (!org.wynnvets.util.GuildInfoListener.areFeaturesEnabled()) {
    //	ctx.getSource().sendError(Component.literal("This command is only available for Returners guild members."));
    //	return 0;
    //}

    // Fetch MOTD from API and display it
    MotdFetcher.fetchMotd().thenAccept(motd -> {
      ctx.getSource().sendFeedback(motd);
    });
    return 1;
  }

  // Get information about the current return.
  private int returnInfo(CommandContext<FabricClientCommandSource> ctx) {
    // Only allow return command if features are enabled (guild is Returners)
    //if (!org.wynnvets.util.GuildInfoListener.areFeaturesEnabled()) {
    //ctx.getSource().sendError(Component.literal("This command is only available for Returners guild members."));
    //return 0;
    //}

    // Fetch return from API and display it
    ReturnFetcher.fetchReturn().thenAccept(returnInfo -> {
      ctx.getSource().sendFeedback(returnInfo);
    });

    return 1;
  }

  // Return information about how each command is used.
  private int help(CommandContext<FabricClientCommandSource> ctx) {
    ctx.getSource().sendFeedback(Component.literal("VetsMod Help! More information to come soon."));

    return 1;
  }
}