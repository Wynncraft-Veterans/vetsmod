package org.wynnvets.listeners;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wynnvets.guild.GuildStateManager;

/**
 * Registers Fabric client networking callbacks for server join and disconnect events.
 *
 * <p>On disconnect, resets {@link GuildStateManager} so that guild state is
 * re-evaluated when the player reconnects to a server.</p>
 */
public class ServerConnectionListener {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");

  public static void register() {
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
      LOGGER.info("Connected to server");
    });

    // Reset the flag when disconnecting
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
      // Reset guild info when disconnecting
      GuildStateManager.reset();
      LOGGER.info("Disconnected from server");
    });
  }
}
