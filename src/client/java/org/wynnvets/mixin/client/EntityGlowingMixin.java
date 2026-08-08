package org.wynnvets.mixin.client;

import com.wynntils.mc.extension.EntityExtension;
import com.wynntils.utils.colors.CustomColor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Force {@link Entity#isCurrentlyGlowing()} to {@code true} for entities
 * whose Wynntils-side glow colour has been set to anything other than
 * {@link CustomColor#NONE}.
 *
 * <p>Backs the S4 outline-overlay path: the {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker
 * AnniOutlineTicker} writes a per-player glow colour via
 * {@link EntityExtension#setGlowColor(CustomColor)}, and Wynntils' {@code EntityRendererMixin}
 * happily overrides {@code state.outlineColor} from that — but vanilla still gates outline
 * rendering on the {@link Entity#isCurrentlyGlowing()} flag. For a player Wynncraft never put in a
 * relationship team (no native glow), our colour would silently do nothing without this nudge.</p>
 *
 * <p>No mode / window / zone gate here on purpose — the glow colour
 * field is {@link CustomColor#NONE} by default, so this only ever fires
 * for entities the ticker (or a future feature) has explicitly enrolled.
 * Cheap to evaluate; safe outside the active S4 window.</p>
 *
 * <p>Per outlines.md §3 Option C "Cons" — the {@code EntityExtension}
 * override pipeline alone is insufficient because vanilla won't trigger
 * outline rendering for non-glowing entities. This mixin closes that
 * gap, six lines.</p>
 */
@Mixin(Entity.class)
public class EntityGlowingMixin {

    @Inject(method = "isCurrentlyGlowing()Z", at = @At("HEAD"), cancellable = true)
    private void vetsmod$forceGlowForOverrideColor(CallbackInfoReturnable<Boolean> cir) {
        if (((EntityExtension) this).getGlowColor() != CustomColor.NONE) {
            cir.setReturnValue(true);
        }
    }
}
