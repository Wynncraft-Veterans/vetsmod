package org.wynnvets.listeners;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.util.GuildInfoListener;

public class ServerConnectionListener {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");

  public static void register() {
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
      LOGGER.info("Connected to server");
    });

    // Reset the flag when disconnecting
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
      // Reset guild info when disconnecting
      GuildInfoListener.reset();
      LOGGER.info("Disconnected from server");
    });
  }
}
