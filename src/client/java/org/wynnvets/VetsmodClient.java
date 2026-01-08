package org.wynnvets;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.network.chat.Component;

public class VetsmodClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Register client-side commands using the Fabric client command API
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("motd").executes(ctx -> {
				ctx.getSource().sendFeedback(Component.literal("Hello World (Placeholder)"));
				return 1;
			}));
		});
	}
}