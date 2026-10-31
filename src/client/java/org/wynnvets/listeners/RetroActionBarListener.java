package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Handlers;
import com.wynntils.handlers.actionbar.event.ActionBarRenderEvent;
import com.wynntils.handlers.actionbar.event.ActionBarUpdatedEvent;
import com.wynntils.models.worlds.event.WorldStateEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.render.RetroHud;
import org.wynnvets.retro.render.RetroLevelSegmentMatcher;
import org.wynnvets.retro.render.RetroNumberSegmentMatcher;

/**
 * Subscribes to Wynntils' {@link ActionBarRenderEvent}, which Wynntils posts for each action bar
 * Wynncraft sends, after its models have read it and before it is drawn, so retro can take out of
 * it the bars its own HUD draws in their place ({@link RetroHud#hideWynncraftsBars}). Hands
 * {@link RetroHud#readBar} each {@link ActionBarUpdatedEvent}, for what retro draws from the bar
 * itself. Registers {@link RetroLevelSegmentMatcher} and {@link RetroNumberSegmentMatcher} too, for
 * the level and numbers Wynntils' own matchers can miss, and tells
 * {@link RetroHud#worldStateChanged} of each {@link WorldStateEvent}.
 *
 * <p>Default priority: Wynntils' own listeners ({@code CharacterStatsModel}'s and
 * {@code SpellModel}'s among them) only disable segments too, so the order does not matter.</p>
 */
public final class RetroActionBarListener {

    private static final RetroActionBarListener INSTANCE = new RetroActionBarListener();

    private RetroActionBarListener() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        Handlers.ActionBar.registerSegment(new RetroLevelSegmentMatcher());
        Handlers.ActionBar.registerSegment(RetroNumberSegmentMatcher.health());
        Handlers.ActionBar.registerSegment(RetroNumberSegmentMatcher.mana());
        VetsLogger.debug("Registered RetroActionBarListener on Wynntils event bus");
    }

    @SubscribeEvent
    public void onActionBarRender(ActionBarRenderEvent event) {
        RetroHud.hideWynncraftsBars(event);
    }

    @SubscribeEvent
    public void onActionBarUpdate(ActionBarUpdatedEvent event) {
        RetroHud.readBar(event);
    }

    @SubscribeEvent
    public void onWorldState(WorldStateEvent event) {
        RetroHud.worldStateChanged(event.getNewState());
    }
}
