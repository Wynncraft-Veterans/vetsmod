package org.wynnvets.listeners;

import com.wynntils.core.components.Managers;
import com.wynntils.core.mod.ConnectionManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.distribute.DistributeRuns;
import org.wynnvets.guild.GuildLifecycle;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.logging.VetsLogger;

/**
 * Registers Fabric client networking callbacks for server join and disconnect events.
 *
 * <p>On join, the v1 WebSockets open only when Wynntils reports a Wynncraft connection
 * ({@link #onWynncraft()}); on any other server, and in singleplayer, vetsmod opens no socket and
 * sends no frame (its HTTP pollers still run, as they do from launch). On disconnect, it closes
 * the sockets and clears the per-connection guild, auth and world-join state through
 * {@link org.wynnvets.guild.GuildLifecycle#reset() GuildLifecycle.reset()}, so that it is
 * re-evaluated when the player reconnects to a server, and ends any {@code /wv distribute} run
 * and its queue
 * ({@link org.wynnvets.distribute.DistributeRuns#reset() DistributeRuns.reset()}).</p>
 */
public final class ServerConnectionListener {

    private ServerConnectionListener() {}

    /**
     * Whether Wynntils reports a connection to Wynncraft. Wynntils sets its connection state when
     * it starts connecting to a Wynncraft host, before login, and keeps it across a world
     * transfer, so this is already true when Fabric's join callback runs. The same test gates
     * {@code GuildChatDispatcher}'s command interception. Safe only after {@code CLIENT_STARTED}.
     */
    public static boolean onWynncraft() {
        return Managers.Connection.getConnectionState()
                != ConnectionManager.ConnectionState.DISCONNECTED;
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    if (onWynncraft()) {
                        V1ApiManager.connect();
                    } else {
                        VetsLogger.debug(
                                "Joined a server Wynntils does not report as Wynncraft; v1 sockets"
                                        + " stay closed");
                    }
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
                    DistributeRuns.reset();
                    VetsLogger.debug("Disconnected from server, guild state reset");
                });
    }
}
