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
import org.wynnvets.util.GuildInfoListener;
import org.wynnvets.util.SupportersFetcher;
import org.wynnvets.util.UserInfo;
import org.wynnvets.util.chat.ChatUtils;
import org.wynnvets.util.colors.ShaderColorPalette;

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

    // Start the bridge message fetcher (for guildless+unlocked users)
    BridgeMessageFetcher.start();
    LOGGER.info("Started bridge message fetcher");

    // Start the supporters list fetcher (for gradient pill styling)
    SupportersFetcher.start();
    LOGGER.info("Started supporters fetcher");

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

              // DEBUG COMMAND (temporary): remove once shader-color pipeline is verified.
              .then(ClientCommandManager.literal("test")
                  .executes(this::debugTestGradientCommand))

                // DEBUG COMMAND (temporary): force guildless+unlocked behavior.
                .then(ClientCommandManager.literal("debugbridge")
                  .executes(this::toggleDebugBridgeOverride)
                  .then(ClientCommandManager.literal("on")
                    .executes(this::enableDebugBridgeOverride))
                  .then(ClientCommandManager.literal("off")
                    .executes(this::disableDebugBridgeOverride))
                  .then(ClientCommandManager.literal("status")
                    .executes(this::debugBridgeOverrideStatus)))
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

  // Return information about how each command is used.
  private int help(CommandContext<FabricClientCommandSource> ctx) {
    ChatUtils.sendLocalMessage(Component.literal("VetsMod Help! More information to come soon."));

    return 1;
  }

  // DEBUG COMMAND (temporary): remove after /wv test verification is complete.
  private int debugTestGradientCommand(CommandContext<FabricClientCommandSource> ctx) {
    // Wynncraft's resource-pack ships custom rendertype_text shaders that
    // detect the sentinel colour 0x00F000 and replace it with an animated
    // rainbow cycle on the GPU.  We just need to set the colour; the
    // shader does the rest while the pack is active.
    ChatUtils.sendLocalMessage(
        Component.literal("this is a test message")
            .withColor(ShaderColorPalette.RAINBOW)
            .withStyle(ChatFormatting.BOLD));
    return 1;
  }

  // DEBUG COMMAND (temporary): force guildless+unlocked bridge behavior.
  private int toggleDebugBridgeOverride(CommandContext<FabricClientCommandSource> ctx) {
    boolean enabled = !GuildInfoListener.isDebugForceGuildlessUnlocked();
    GuildInfoListener.setDebugForceGuildlessUnlocked(enabled);
    ChatUtils.sendLocalMessage(Component.literal(
        "Debug bridge override " + (enabled ? "enabled" : "disabled") +
            " (treating user as guildless+unlocked: " + enabled + ")"));
    return 1;
  }

  private int enableDebugBridgeOverride(CommandContext<FabricClientCommandSource> ctx) {
    GuildInfoListener.setDebugForceGuildlessUnlocked(true);
    ChatUtils.sendLocalMessage(Component.literal(
        "Debug bridge override enabled (treating user as guildless+unlocked: true)"));
    return 1;
  }

  private int disableDebugBridgeOverride(CommandContext<FabricClientCommandSource> ctx) {
    GuildInfoListener.setDebugForceGuildlessUnlocked(false);
    ChatUtils.sendLocalMessage(Component.literal(
        "Debug bridge override disabled (treating user as guildless+unlocked: false)"));
    return 1;
  }

  private int debugBridgeOverrideStatus(CommandContext<FabricClientCommandSource> ctx) {
    boolean enabled = GuildInfoListener.isDebugForceGuildlessUnlocked();
    ChatUtils.sendLocalMessage(Component.literal(
        "Debug bridge override status: " + (enabled ? "enabled" : "disabled")));
    return 1;
  }
}