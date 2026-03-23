package org.wynnvets.listeners;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.guild.GuildStateManager;

/**
 * Registers Fabric client networking callbacks for server join and disconnect events.
 *
 * <p>On disconnect, resets {@link GuildStateManager} so that guild state is
 * re-evaluated when the player reconnects to a server.</p>
 */
public class ServerConnectionListener {

  public static void register() {
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
      V1ApiManager.connect();
      OutboundDisplayHandler.register();
      VetsLogger.debug("Connected to server");
    });

    // Reset the flag when disconnecting
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
      // Reset guild info when disconnecting
      GuildStateManager.reset();
      V1ApiManager.disconnect();
      OutboundDisplayHandler.unregister();
      OutboundDisplayHandler.clearCaches();
      VetsLogger.debug("Disconnected from server, guild state reset");
    });
  }
}
