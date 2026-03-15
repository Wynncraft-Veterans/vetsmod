package org.wynnvets;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.util.MotdFetcher;
import org.wynnvets.util.ReturnFetcher;
import org.wynnvets.util.ChatMessageFetcher;
import org.wynnvets.util.BridgeMessageFetcher;
import org.wynnvets.util.StaffRanksFetcher;
import org.wynnvets.util.StaffFetcher;
import org.wynnvets.util.SupportersFetcher;
import org.wynnvets.util.UserInfo;
import org.wynnvets.util.GuildInfoListener;
import org.wynnvets.util.StampFetcher;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.util.chat.ChatUtils;

public class VetsmodClient implements ClientModInitializer {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");

  @Override
  public void onInitializeClient() {
    LOGGER.info("Initializing VetsMod client");

    // Load configuration from file
    VetsConfig.load();
    GuildInfoListener.loadPersistedState();
    LOGGER.info("Configuration loaded");

    // Load item definitions for legacy/enchanted detection
    ItemDefinitions.load();

    // Start the chat message fetcher
    ChatMessageFetcher.start();
    LOGGER.info("Started chat message fetcher");

    // Start the bridge message fetcher (for guildless+unlocked users)
    BridgeMessageFetcher.start();
    LOGGER.info("Started bridge message fetcher");

    // Start the supporters list fetcher (for gradient pill styling)
    SupportersFetcher.start();
    LOGGER.info("Started supporters fetcher");

    // Start confirmed staff-rank fetcher (for staff pill replacement)
    StaffRanksFetcher.start();
    LOGGER.info("Started staff ranks fetcher");

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
    boolean isCurrentlyStaff = GuildInfoListener.isStaff();
    boolean refreshStarted = GuildInfoListener.refreshStaffStatusIfNeeded(!isCurrentlyStaff);

    if (refreshStarted || GuildInfoListener.isCheckingStaffStatus()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Checking staff permissions, please retry in a moment.")
              .withStyle(ChatFormatting.YELLOW)
      );
      return 0;
    }

    if (!GuildInfoListener.isStaff()) {
      ChatUtils.sendLocalMessage(
          Component.literal("You must be staff to use /wv check.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    UserInfo.checkUser(StringArgumentType.getString(ctx, "playerName"))
        .thenAccept(userInfo -> ChatUtils.sendLocalMessage(userInfo));
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
      ChatUtils.sendLocalMessage(motd);
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
      ChatUtils.sendLocalMessage(returnInfo);
    });

    return 1;
  }

  // Get online guild staff information.
  private int staff(CommandContext<FabricClientCommandSource> ctx) {
    if (!GuildInfoListener.isUnlocked()) {
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
}