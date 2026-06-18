package org.wynnvets.mixin.client;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.mwe.anni.outline.AnniOutlineRegistry;
import org.wynnvets.mwe.anni.outline.AnniOutlineTicker;

/**
 * Zeroes {@link EntityRenderState#outlineColor} for {@link AbstractClientPlayer}
 * outsiders while the S4 highlight gate holds.
 *
 * <p><b>Why not the getTeamColor route.</b> The earlier
 * {@code EntityTeamColorMixin} returned 0 from {@code Entity.getTeamColor()},
 * but vanilla 1.21.11's {@code EntityRenderer.extractRenderState} wraps the
 * team-colour return through {@code ARGB.opaque(int)} before writing it to
 * {@code state.outlineColor}:
 * <pre>
 * state.outlineColor = shouldEntityAppearGlowing(entity)
 *     ? ARGB.opaque(entity.getTeamColor())   // forces alpha=0xFF
 *     : 0;
 * </pre>
 * {@code ARGB.opaque(0)} is {@code 0xFF000000} — opaque black — which the
 * outline buffer happily renders as a black glow halo. Confirmed live
 * 2026-06-17: AutisticCrumbs (Wynncraft donator team, glowing flag set)
 * rendered with a solid black outline when our team-colour mixin was active.</p>
 *
 * <p>Suppressing at {@code extractRenderState} TAIL instead skips the
 * ARGB.opaque wrap entirely — we just clobber {@code state.outlineColor}
 * to {@code 0} (which IS the "no outline" sentinel) for outsiders. For
 * registry members, Wynntils' own {@code EntityRendererMixin} TAIL inject
 * runs and overrides {@code state.outlineColor} from
 * {@code EntityExtension.getGlowColor()}, which the
 * {@link AnniOutlineTicker} has already set to the tier colour — so we
 * skip those.</p>
 *
 * <p>Mixin order vs. Wynntils' TAIL inject doesn't matter because the
 * branches are disjoint: registry members get their colour via the glow
 * pipeline (which we don't touch), and outsiders get {@code outlineColor = 0}
 * regardless of which TAIL inject runs first — Wynntils' inject only fires
 * when {@code getGlowColor() != NONE}, which is precisely the registry-member
 * case.</p>
 */
@Mixin(EntityRenderer.class)
public class EntityOutlineColorMixin {

    @Inject(
            method = "extractRenderState("
                    + "Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
                    + "F)V",
            at = @At("TAIL"))
    private void vetsmod$suppressOutsiderOutline(
            Entity entity,
            EntityRenderState state,
            float partialTick,
            CallbackInfo ci) {
        if (!AnniOutlineTicker.isOutlineSuppressionActive()) return;
        if (!VetsConfig.get(VetsConfig.VETS_ANNI_OUTLINES_ENABLED)) return;
        if (!(entity instanceof AbstractClientPlayer player)) return;

        String username = player.getGameProfile().name();
        if (username == null) return;

        if (AnniOutlineRegistry.getEntry(username) != null) return;

        state.outlineColor = 0;
    }
}
