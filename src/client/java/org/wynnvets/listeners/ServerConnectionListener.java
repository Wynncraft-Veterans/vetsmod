package org.wynnvets.listeners;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import org.wynnvets.util.MotdFetcher;

public class ServerConnectionListener {
    private static boolean motdDisplayed = false;
    
    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Prevent duplicate MOTD display
            if (motdDisplayed) {
                return;
            }
            motdDisplayed = true;
            
            // Fetch and display MOTD when joining a server
            MotdFetcher.fetchMotd().thenAccept(motd -> {
                // Schedule on the main thread to ensure thread safety
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.displayClientMessage(motd, false);
                    }
                });
            });
        });
        
        // Reset the flag when disconnecting
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            motdDisplayed = false;
        });
    }
}
