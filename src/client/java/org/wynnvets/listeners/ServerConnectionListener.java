package org.wynnvets.listeners;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.guild.GuildLifecycle;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.logging.VetsLogger;

/**
 * Registers Fabric client networking callbacks for server join and disconnect events.
 *
 * <p>On disconnect, clears the per-connection guild, auth and world-join state through
 * {@link GuildLifecycle#reset() GuildLifecycle.reset()}, so that it is re-evaluated when the
 * player reconnects to a server.</p>
 */
public final class ServerConnectionListener {

    private ServerConnectionListener() {}

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    V1ApiManager.connect();
                    OutboundDisplayHandler.register();
                    VetsLogger.debug("Connected to server");
                });

        // Reset the flag when disconnecting
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    // Reset guild info when disconnecting
                    GuildLifecycle.reset();
                    // BEGIN PATCH(old-server-compat): Remove this line.
                    LegacyItemHandler.newTooltipStylesAvailable = false;
                    // END PATCH(old-server-compat)
                    V1ApiManager.disconnect();
                    OutboundDisplayHandler.unregister();
                    OutboundDisplayHandler.clearCaches();
                    VetsLogger.debug("Disconnected from server, guild state reset");
                });
    }
}
