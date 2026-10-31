package org.wynnvets.listeners;

import com.wynntils.core.WynntilsMod;
import com.wynntils.handlers.actionbar.event.ActionBarRenderEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.render.RetroHud;

/**
 * Subscribes to Wynntils' {@link ActionBarRenderEvent}, which Wynntils posts for each action bar
 * Wynncraft sends, after its models have read it and before it is drawn, so retro can take out of
 * it the bars its own HUD draws in their place ({@link RetroHud#hideWynncraftsBars}).
 *
 * <p>Default priority: Wynntils' own listeners ({@code CharacterStatsModel}'s and
 * {@code SpellModel}'s among them) only disable segments too, so the order does not matter.</p>
 */
public final class RetroActionBarListener {

    private static final RetroActionBarListener INSTANCE = new RetroActionBarListener();

    private RetroActionBarListener() {}

    public static void register() {
        WynntilsMod.registerEventListener(INSTANCE);
        VetsLogger.debug("Registered RetroActionBarListener on Wynntils event bus");
    }

    @SubscribeEvent
    public void onActionBarRender(ActionBarRenderEvent event) {
        RetroHud.hideWynncraftsBars(event);
    }
}
