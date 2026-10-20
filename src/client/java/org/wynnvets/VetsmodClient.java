package org.wynnvets;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.wynnvets.api.V1ApiManager;
import org.wynnvets.chat.OutboundDisplayHandler;
import org.wynnvets.commands.CommandRegistry;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.debug.DebugConfigManager;
import org.wynnvets.debug.dump.DebugKeyHandler;
import org.wynnvets.distribute.distributor.MemberSlotPresser;
import org.wynnvets.distribute.opener.GuildManageOpener;
import org.wynnvets.distribute.walker.GuildLogWalker;
import org.wynnvets.distribute.walker.MembersListSearcher;
import org.wynnvets.distribute.walker.MembersListWalker;
import org.wynnvets.fetcher.polling.AnniSnapshotPoller;
import org.wynnvets.fetcher.polling.AnniStampPoller;
import org.wynnvets.fetcher.polling.PolledJsonMap;
import org.wynnvets.fetcher.polling.StaffRanksPoller;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.listeners.PartyRosterListener;
import org.wynnvets.listeners.RankChangeListener;
import org.wynnvets.listeners.ServerConnectionListener;
import org.wynnvets.listeners.WynntilsEventListener;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.ScrollSpotMarkerProvider;
import org.wynnvets.mwe.anni.aggressive.AggressiveAlertDispatcher;
import org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker;
import org.wynnvets.mwe.anni.aggressive.GhostsPromptHandler;
import org.wynnvets.mwe.anni.bossbar.VetsBossBarManager;
import org.wynnvets.mwe.anni.mode.AnniWindowWatcher;
import org.wynnvets.mwe.anni.network.AnniWsHandler;
import org.wynnvets.mwe.anni.outline.AnniOutlineRegistry;
import org.wynnvets.mwe.anni.outline.AnniOutlineTicker;
import org.wynnvets.mwe.anni.zone.AnniZone;
import org.wynnvets.mwe.anni.zone.AnniZoneLineRenderer;
import org.wynnvets.queue.QueueDetector;
import org.wynnvets.queue.QueueStatusReporter;
import org.wynnvets.rendering.territory.TerritoryLineRenderer;

/**
 * Client-side entry point for the VetsMod Fabric mod. Fabric creates it and calls
 * {@link #onInitializeClient()}, which bootstraps the client in two phases:
 *
 * <ol>
 *   <li>{@link #onInitializeClient()} runs during the game's start-up, before Wynntils' own
 *       entrypoint in production: configuration and guild state, item definitions, the
 *       WebSockets and their listeners, the pollers, the tick, render and server-connection
 *       hooks, and the callback through which Fabric builds the {@code /wv} command tree (via
 *       {@link CommandRegistry}).</li>
 *   <li>{@link #onClientStarted()} runs once the game has started: everything that needs
 *       Wynntils' event bus or one of its core components. Its Javadoc says why the two are
 *       kept apart.</li>
 * </ol>
 *
 * <p>Some of the order in {@link #onInitializeClient()} matters: {@code DebugConfigManager.init()},
 * {@code VetsConfig.load()}, the debug-logging restore and
 * {@code GuildStateManager.loadPersistedState()} each read what the one before set up;
 * {@code AnniAggressiveTicker} registers before the {@code END_CLIENT_TICK} listeners that read
 * its flag; and the socket listeners register straight after {@code V1ApiManager.connect()},
 * which only starts the first connect, and rely on being in place before its handshake
 * completes. The rest is today's order, kept as it is.</p>
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
        restorePersistedDebugLogging();

        GuildStateManager.loadPersistedState();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> onClientStarted());
        ItemDefinitions.load();

        V1ApiManager.connect();
        OutboundDisplayHandler.register();
        AnniWsHandler.register();
        AnniWindowWatcher.register();
        VetsBossBarManager.register();
        AnniOutlineRegistry.register();
        AnniOutlineTicker.register();
        // S5 — aggressive-mode components. AnniAggressiveTicker computes the
        // per-tick "is aggressive active" flag the aggressive-mode components
        // read. Registering it before AggressiveAlertDispatcher and
        // GhostsPromptHandler, its readers on the same END_CLIENT_TICK event,
        // means they see this tick's value (Fabric runs a phase's listeners in
        // registration order); readers on other hooks are not ordered by this.
        // ScrollSpotMarkerProvider is deferred to CLIENT_STARTED (onClientStarted
        // below) — touching Wynntils' Models class here can crash the game (see
        // the comment there).
        AnniAggressiveTicker.register();
        AggressiveAlertDispatcher.register();
        AnniZoneLineRenderer.register();
        GhostsPromptHandler.register();
        AnniZone.start();
        SupportersPoller.start();
        StaffRanksPoller.start();
        AnniStampPoller.start();
        AnniSnapshotPoller.start();
        PolledJsonMap.GUILD_ROSTER.start();
        PolledJsonMap.WYNN_ALIASES.start();
        ServerConnectionListener.register();
        TerritoryLineRenderer.register();
        DebugKeyHandler.register();

        // Register the /wv command tree (and standalone /motd alias)
        ClientCommandRegistrationCallback.EVENT.register(CommandRegistry::register);

        VetsLogger.info("Client initialized");
    }

    /**
     * The second phase of the bootstrap: the steps that need Wynntils' event bus or one of its core
     * components. {@link #onInitializeClient()} registers it as a
     * {@code ClientLifecycleEvents.CLIENT_STARTED} listener, which Fabric runs once, on the game
     * thread, when {@code Minecraft.run()} begins. Every mod's client entrypoint has run by then,
     * so Wynntils' {@code WynntilsMod.init} has created its event bus; Wynntils' features start
     * later.
     *
     * <p>Nothing that {@link #onInitializeClient()} runs may touch Wynntils' event bus or a
     * Wynntils core component ({@code Models}, {@code Managers}, {@code Handlers},
     * {@code Services}). In production, Fabric Loader runs client entrypoints in mod-id order, so
     * vetsmod's runs before Wynntils', while the bus is still null:</p>
     * <ul>
     *   <li>{@code WynntilsMod.registerEventListener} throws a {@code NullPointerException} at
     *       once, and Fabric fails the game's start-up with it.</li>
     *   <li>A first touch of {@code Models} constructs {@code ServerListModel}, which at once
     *       schedules a server-list update on a pool thread. If that thread reaches
     *       {@code Managers} before Wynntils' entrypoint has created the bus, {@code Managers}'
     *       static initialiser fails, and Wynntils' own entrypoint then dies with
     *       {@code NoClassDefFoundError}. That crashed the client on 2026-06-18.</li>
     * </ul>
     *
     * <p>Wynntils' bus calls listeners of equal priority in the order they registered, so the order
     * here is kept as it is.</p>
     */
    private static void onClientStarted() {
        WynntilsEventListener.register();
        GuildManageOpener.register();
        MembersListSearcher.register();
        MembersListWalker.register();
        GuildLogWalker.register();
        MemberSlotPresser.register();
        RankChangeListener.register();
        PartyRosterListener.register();
        QueueDetector.register();
        // S5 — registerWithWynntils() touches `Models.Marker`, a Wynntils core
        // component, so it runs here and never in onInitializeClient. In
        // production, onInitializeClient runs before Wynntils' entrypoint
        // (`com.wynntils.fabric.WynntilsModFabric`) has created its event bus,
        // and a first touch of `Models` there starts the `ServerListModel` ->
        // `Managers` race in this method's Javadoc. If the race is lost,
        // `Managers.<clinit>` fails, `Managers` stays unusable and Wynntils' own
        // init dies, as the client did on 2026-06-18.
        ScrollSpotMarkerProvider.registerWithWynntils();
        // S7 — AnniPartyReporter.register() itself touches no Wynntils class,
        // but its snapshot listener reaches Models.Party (through
        // PartyRosterListener.requestRecapture()), and a snapshot could arrive
        // before Wynntils' entrypoint has run, so it subscribes here rather
        // than in onInitializeClient
        // (mod-init-snapshot-listeners-can-reach-wynntils-before-its-init). The
        // reporter subscribes to AnniSnapshotCache and fires
        // PartyRosterListener.requestRecapture() on organiser-set transitions,
        // meant to stop a window-open mid-static-party going silent. Today a
        // window opening with an unchanged organiser set fires nothing
        // (party-reporter-window-open-not-a-trigger).
        org.wynnvets.mwe.anni.AnniPartyReporter.register();
        QueueStatusReporter.register();
    }

    /**
     * Turns debug logging back on if {@code /wv debug true} turned it on less than three days ago.
     * If that was three days ago or more, clears the stored time instead. The time is stored as
     * {@link VetsConfigKeys#VETS_DEBUG_ENABLED_AT} in {@code config.json}, which
     * {@link VetsConfig#load()} reads, so this runs after it.
     */
    private static void restorePersistedDebugLogging() {
        long debugEnabledAt = VetsConfig.getLong(VetsConfigKeys.VETS_DEBUG_ENABLED_AT);
        if (debugEnabledAt > 0) {
            long threeDaysMs = 3L * 24 * 60 * 60 * 1000;
            if (System.currentTimeMillis() - debugEnabledAt < threeDaysMs) {
                VetsLogger.setDebugEnabled(true);
                VetsLogger.info(
                        "Debug logging restored (enabled {} hours ago)",
                        (System.currentTimeMillis() - debugEnabledAt) / 3_600_000);
            } else {
                VetsConfig.setLong(VetsConfigKeys.VETS_DEBUG_ENABLED_AT, 0L);
                VetsLogger.info("Debug logging expired after 3 days, auto-disabled");
            }
        }
    }
}
