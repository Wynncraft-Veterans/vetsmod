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
import org.wynnvets.distribute.distributor.MemberSlotPresser;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.walker.GuildLogWalker;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.fetcher.polling.AnniSnapshotPoller;
import org.wynnvets.fetcher.polling.AnniStampPoller;
import org.wynnvets.fetcher.polling.GuildRosterCache;
import org.wynnvets.fetcher.polling.WynnAliasCache;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.listeners.PartyRosterListener;
import org.wynnvets.listeners.RankChangeListener;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.listeners.WynntilsEventListener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.aggressive.AggressiveAlertDispatcher;
import org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker;
import org.wynnvets.mwe.anni.aggressive.GhostsPromptHandler;
import org.wynnvets.mwe.anni.waypoint.ScrollSpotMarkerProvider;
import org.wynnvets.mwe.anni.zone.AnniZoneLineRenderer;
import org.wynnvets.mwe.anni.bossbar.VetsBossBarManager;
import org.wynnvets.mwe.anni.mode.AnniModeManager;
import org.wynnvets.mwe.anni.mode.AnniWindowWatcher;
import org.wynnvets.mwe.anni.mode.StreamerModeChatDetector;
import org.wynnvets.mwe.anni.network.AnniWsHandler;
import org.wynnvets.mwe.anni.outline.AnniOutlineRegistry;
import org.wynnvets.mwe.anni.outline.AnniOutlineTicker;
import org.wynnvets.mwe.anni.zone.AnniZone;
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
      GuildManageOpener.register();
      MembersListSearcher.register();
      MembersListWalker.register();
      GuildLogWalker.register();
      MemberSlotPresser.register();
      RankChangeListener.register();
      PartyRosterListener.register();
      QueueDetector.register();
      // S5 — must register AFTER Wynntils' own onInitializeClient has
      // run (`com.wynntils.fabric.WynntilsModFabric`). Wynntils is a
      // separate fabric mod; entrypoint order between mods is not
      // guaranteed, and touching `Models.Marker` from vetsmod's
      // onInitializeClient triggers `Models.<clinit>` — which in turn
      // tries to post events on `WynntilsMod.eventBus`. If our init runs
      // first, eventBus is null and the resulting NPE leaves Wynntils'
      // own init permanently broken (cascade-crashes in
      // `Managers.<clinit>` later). CLIENT_STARTED fires after every
      // mod's onInitializeClient, so by here Wynntils' init has completed
      // and the eventBus is wired up. See attempt-3 crash log.
      ScrollSpotMarkerProvider.registerWithWynntils();
      // S7 — same deferred-init rule as ScrollSpotMarkerProvider above. The
      // reporter subscribes to AnniSnapshotCache and fires
      // PartyRosterListener.requestRecapture() on organiser-set transitions
      // so a window-open mid-static-party doesn't go silent.
      org.wynnvets.mwe.anni.party.AnniPartyReporter.init();
      QueueStateManager.addListener(new QueueStateListener() {
        @Override
        public void onQueueEntered(String worldName) {
          // Only report queue state from vets-context users -- the server
          // can't usefully attribute queue status from sessions that
          // never sent a `register` frame.
          if (!GuildStateManager.isUnlocked()) return;
          V1ApiManager.sendQueueStatus(true, worldName);
        }

        @Override
        public void onQueueExited(String reason) {
          if (!GuildStateManager.isUnlocked()) return;
          V1ApiManager.sendQueueStatus(false, "");
        }
      });
    });
    ItemDefinitions.load();

    V1ApiManager.connect();
    OutboundDisplayHandler.register();
    AnniWsHandler.register();
    AnniWindowWatcher.register();
    AnniModeManager.register();
    StreamerModeChatDetector.register();
    VetsBossBarManager.register();
    AnniOutlineRegistry.register();
    AnniOutlineTicker.register();
    // S5 — aggressive-mode components. AnniAggressiveTicker computes the
    // per-tick "is aggressive active" flag every other component reads;
    // register it first so the other four observe a populated flag on
    // their first tick. ScrollSpotMarkerProvider is deferred to
    // CLIENT_STARTED above — touching Wynntils' Models class here
    // crashes the game (see comment above).
    AnniAggressiveTicker.register();
    AggressiveAlertDispatcher.register();
    AnniZoneLineRenderer.register();
    GhostsPromptHandler.register();
    AnniZone.start();
    SupportersPoller.start();
    StaffRanksPoller.start();
    AnniStampPoller.start();
    AnniSnapshotPoller.start();
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