package org.wynnvets;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.network.chat.Component;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.util.MotdFetcher;
import org.wynnvets.util.ChatMessageFetcher;

public class VetsmodClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Start the chat message fetcher
		ChatMessageFetcher.start();
		
		// Register server connection listener for auto-MOTD
		ServerConnectionListener.register();
		
		// Register client-side commands using the Fabric client command API
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("motd").executes(ctx -> {
				// Fetch MOTD from API and display it
				MotdFetcher.fetchMotd().thenAccept(motd -> {
					ctx.getSource().sendFeedback(motd);
				});
				return 1;
			}));
		});
	}
}