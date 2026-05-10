package org.wynnvets;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.commands.CommandRegistry;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.debug.DebugConfigManager;
import org.wynnvets.debug.dump.DebugKeyHandler;
import org.wynnvets.fetcher.polling.GuildRosterCache;
import org.wynnvets.fetcher.polling.WynnAliasCache;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.listeners.RankChangeListener;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.listeners.WynntilsEventListener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.queue.QueueDetector;
import org.wynnvets.queue.QueueStateListener;
import org.wynnvets.queue.QueueStateManager;
import org.wynnvets.rendering.territory.TerritoryLineRenderer;

/**
 * Client-side entry point for the VetsMod Fabric mod.
 *
 * <p>Bootstraps all mod subsystems on client initialisation: configuration,
 * guild state, item definitions, message fetchers, server connection hooks,
 * and the {@code /wv} client command tree (via {@link CommandRegistry}).
 * Each subsystem is started in dependency order so that later components
 * can rely on earlier ones.</p>
 */
public class VetsmodClient implements ClientModInitializer {

  @Override
  public void onInitializeClient() {
    VetsLogger.info("Client initializing");

    // Initialise debug config keys *before* VetsConfig.load() so that any
    // persisted debug settings are picked up when config values are read.
    DebugConfigManager.init();
    VetsConfig.load();

    // Restore persisted debug logging if enabled within the last 3 days
    long debugEnabledAt = VetsConfig.getLong(VetsConfig.VETS_DEBUG_ENABLED_AT);
    if (debugEnabledAt > 0) {
      long threeDaysMs = 3L * 24 * 60 * 60 * 1000;
      if (System.currentTimeMillis() - debugEnabledAt < threeDaysMs) {
        VetsLogger.setDebugEnabled(true);
        VetsLogger.info("Debug logging restored (enabled {} hours ago)",
            (System.currentTimeMillis() - debugEnabledAt) / 3_600_000);
      } else {
        VetsConfig.setLong(VetsConfig.VETS_DEBUG_ENABLED_AT, 0L);
        VetsLogger.info("Debug logging expired after 3 days, auto-disabled");
      }
    }

    GuildStateManager.loadPersistedState();
    ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
      WynntilsEventListener.register();
      RankChangeListener.register();
      QueueDetector.register();
      QueueStateManager.addListener(new QueueStateListener() {
        @Override
        public void onQueueEntered(String worldName) {
          V1ApiManager.sendQueueStatus(true, worldName);
        }

        @Override
        public void onQueueExited(String reason) {
          V1ApiManager.sendQueueStatus(false, "");
        }
      });
    });
    ItemDefinitions.load();

    V1ApiManager.connect();
    OutboundDisplayHandler.register();
    SupportersPoller.start();
    StaffRanksPoller.start();
    GuildRosterCache.start();
    WynnAliasCache.start();
    ServerConnectionListener.register();
    TerritoryLineRenderer.register();
    DebugKeyHandler.register();

    // Register the /wv command tree (and standalone /motd alias)
    ClientCommandRegistrationCallback.EVENT.register(CommandRegistry::register);

    VetsLogger.info("Client initialized");
  }
}