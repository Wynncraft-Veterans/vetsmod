package org.wynnvets.mixin.client.retro;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.Collection;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.wynnvets.retro.render.RetroInventoryEffects;

/**
 * Leaves Wynncraft's attack-speed pseudo-effect out of the effect list beside the inventory.
 *
 * <p>{@code EffectsInInventory.render} reads the player's effects once and draws them all;
 * {@link RetroInventoryEffects#visibleEffects} filters what it read, which is the same idea as
 * {@code BossHealthOverlayMixin}'s filter on the boss bars. The body is one call into code that
 * returns the effects unchanged while the retro pack is not live on Wynncraft.</p>
 */
@Mixin(EffectsInInventory.class)
public abstract class RetroEffectsInInventoryMixin {

    /**
     * Hands the effects the list is about to draw to
     * {@link RetroInventoryEffects#visibleEffects}.
     */
    @ModifyExpressionValue(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;II)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/player/LocalPlayer;getActiveEffects()Ljava/util/Collection;"))
    private Collection<MobEffectInstance> vetsmod$hideRetroEffects(
            Collection<MobEffectInstance> effects) {
        return RetroInventoryEffects.visibleEffects(effects);
    }
}
