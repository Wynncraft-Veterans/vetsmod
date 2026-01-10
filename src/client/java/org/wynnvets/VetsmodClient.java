package org.wynnvets;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.util.MotdFetcher;
import org.wynnvets.util.ReturnFetcher;
import org.wynnvets.util.ChatMessageFetcher;

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
			dispatcher.register(ClientCommandManager.literal("motd").executes(ctx -> {
				// Check if initial guild check has completed first (more important check)
				if (!org.wynnvets.util.GuildInfoListener.canExecuteCommands()) {
					ctx.getSource().sendError(Component.literal("Please wait for a few seconds after joining worlds before using vetsmod commands!"));
					return 0;
				}
				
				// Only allow MOTD command if features are enabled (guild is Returners)
				if (!org.wynnvets.util.GuildInfoListener.areFeaturesEnabled()) {
					ctx.getSource().sendError(Component.literal("This command is only available for Returners guild members."));
					return 0;
				}
				
				// Fetch MOTD from API and display it
				MotdFetcher.fetchMotd().thenAccept(motd -> {
					ctx.getSource().sendFeedback(motd);
				});
				return 1;
			}));
			
			dispatcher.register(ClientCommandManager.literal("return").executes(ctx -> {
				// Check if initial guild check has completed first (more important check)
				if (!org.wynnvets.util.GuildInfoListener.canExecuteCommands()) {
					ctx.getSource().sendError(Component.literal("Please wait for a few seconds after joining worlds before using vetsmod commands!"));
					return 0;
				}
				
				// Only allow return command if features are enabled (guild is Returners)
				if (!org.wynnvets.util.GuildInfoListener.areFeaturesEnabled()) {
					ctx.getSource().sendError(Component.literal("This command is only available for Returners guild members."));
					return 0;
				}
				
				// Fetch return from API and display it
				ReturnFetcher.fetchReturn().thenAccept(returnInfo -> {
					ctx.getSource().sendFeedback(returnInfo);
				});
				return 1;
			}));

			dispatcher.register(ClientCommandManager.literal("aronUUID").executes(ctx -> {
				Minecraft minecraft = Minecraft.getInstance();
				LocalPlayer player = minecraft.player;
				if (player != null) {
					MotdFetcher.getUUID(player.getName().getString()).thenAccept(uuid -> {
						ctx.getSource().sendFeedback(uuid);
						ctx.getSource().sendFeedback(Component.literal("UUID from player info: " + player.getUUID().toString()));
					});

					MotdFetcher.getPlayerInformation(player.getUUID()).thenAccept(playerInfo -> {
						ctx.getSource().sendFeedback(playerInfo);
					});
				}

				return 1;
			}));
		});
	}
}