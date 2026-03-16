package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.models.guild.event.GuildEvent;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.models.worlds.type.WorldState;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;


/**
 * Subscribes to Wynntils events for world state and guild changes, replacing
 * the previous approach of sending {@code /guild stats} and parsing chat output.
 */
public final class WynntilsEventListener {

    /** Singleton instance registered with the Wynntils event bus. */
    private static final WynntilsEventListener INSTANCE = new WynntilsEventListener();

    private WynntilsEventListener() {
    }

    /**
     * Registers this listener with the Wynntils event bus.
     * Should be called once during client initialization.
     */
    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        GuildStateManager.setWynntilsReady();
        VetsLogger.debug("Registered Wynntils event listeners");
    }

    /**
     * Unregisters this listener from the Wynntils event bus.
     */
    public static void unregister() {
        WynntilsMod.unregisterEventListener(INSTANCE);
    }

    /**
     * Handles world state transitions from Wynntils.
     *
     * <p>When the player enters a {@link WorldState#WORLD}, triggers
     * {@link GuildStateManager#onEnteredWorld()} to begin guild and staff
     * rank resolution.
     *
     * @param event the world state change event
     */
    @SubscribeEvent
    public void onWorldStateChanged(WorldStateEvent event) {
        if (event.getNewState() == WorldState.WORLD) {
            VetsLogger.debug("WorldStateEvent: entered WORLD (world={}, firstJoin={})",
                    event.getWorldName(), event.isFirstJoinWorld());
            GuildStateManager.onEnteredWorld();
        } else if (event.getOldState() == WorldState.WORLD
                && event.getNewState() != WorldState.WORLD) {
            VetsLogger.debug("WorldStateEvent: left WORLD → {}", event.getNewState());
        }
    }

    /**
     * Handles the player joining a guild.
     *
     * <p>Delegates to {@link GuildStateManager#onGuildInfoUpdated()} to
     * re-evaluate guild membership and feature gates.
     *
     * @param event the guild joined event
     */
    @SubscribeEvent
    public void onGuildJoined(GuildEvent.Joined event) {
        VetsLogger.debug("GuildEvent.Joined: {}", event.getGuildName());
        GuildStateManager.onGuildInfoUpdated();
    }

    /**
     * Handles the player leaving a guild.
     *
     * <p>Delegates to {@link GuildStateManager#onGuildInfoUpdated()} to
     * re-evaluate guild membership and feature gates.
     *
     * @param event the guild left event
     */
    @SubscribeEvent
    public void onGuildLeft(GuildEvent.Left event) {
        VetsLogger.debug("GuildEvent.Left: {}", event.getGuildName());
        GuildStateManager.onGuildInfoUpdated();
    }
}
