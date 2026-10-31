package org.wynnvets.mixin.client.accessors;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads which bar the HUD draws above the hotbar this frame, so retro's HUD draws its experience
 * bar only where vanilla would draw one.
 *
 * <p>{@code Gui.contextualInfoBar} pairs the private {@code Gui.ContextualInfo} vanilla picked
 * (empty, experience, locator or a mount's jump bar) with the {@link ContextualBarRenderer} that
 * draws it; {@code renderHotbarAndDecorations} updates it before it draws the bar, from
 * conditions that are partly private (how recently the experience or the jump changed). Its
 * renderer is an {@link net.minecraft.client.gui.contextualbar.ExperienceBarRenderer
 * ExperienceBarRenderer} exactly when vanilla draws the experience bar
 * ({@link org.wynnvets.retro.render.RetroHud RetroHud}).</p>
 */
@Mixin(Gui.class)
public interface GuiAccessor {

    @Accessor("contextualInfoBar")
    Pair<?, ContextualBarRenderer> vetsmod$getContextualInfoBar();
}
