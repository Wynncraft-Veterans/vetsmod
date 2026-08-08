package org.wynnvets.mixin.client;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.wynnvets.mwe.anni.bossbar.VetsBossBarManager;

/**
 * Render-side filter that hides all non-vetsmod boss bars while
 * {@link VetsBossBarManager#isActive()} is true.
 *
 * <p>Earlier iterations of this mixin cancelled
 * {@code BossHealthOverlay#update(ClientboundBossEventPacket)} at
 * HEAD (Option B per
 * {@code vets-anni/.claude/ephemeral/vetsmod-integration-investigation-prep/boss-bar.md}
 * §3). That cancelled the packet entirely, leaving the vanilla
 * {@code events} map missing entries the server still believes
 * exist — and subsequent UpdateProgress/UpdateName/UpdateStyle
 * packets called {@code events.get(uuid).setName(...)}, dereferenced
 * the {@code null} return, and crashed the client. Reproduced live
 * on 2026-06-16 during S3 testing.</p>
 *
 * <p>This rework lets vanilla and Wynntils track bars normally —
 * Wynntils' {@code Models.StreamerMode.isInStream()} signal works
 * without the streamer-mode let-through hack, vanilla never NPEs,
 * and the only behaviour change while we're active is that vanilla's
 * render loop skips every bar except the one keyed at
 * {@link VetsBossBarManager#barUuid()}. Wynntils' own overlays
 * render through their own paths and aren't affected.</p>
 *
 * <p>Priority 500 isn't load-bearing here (there's no cancellation
 * order to win against Wynntils) but kept for symmetry with the
 * existing {@link QueueTitleMixin} ranking — and so if a future
 * collision adds another render-path mixin, we sit early.</p>
 */
@Mixin(value = BossHealthOverlay.class, priority = 500)
public abstract class BossHealthOverlayMixin {

    /**
     * Filter vanilla's events.values() iteration in render(GuiGraphics).
     * When we're active, return either a single-element collection
     * holding our synthetic bar, or empty if our bar isn't currently
     * in the map (defensive; shouldn't happen but a dropped tick won't
     * crash). When we're inactive, return the unmodified
     * collection — vanilla draws everything as usual.
     */
    @Redirect(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;"))
    private Collection<LerpingBossEvent> vetsmod$filterRenderToOurs(
            Map<UUID, LerpingBossEvent> events) {
        if (!VetsBossBarManager.isActive()) return events.values();
        LerpingBossEvent ours = events.get(VetsBossBarManager.barUuid());
        if (ours == null) return Collections.emptyList();
        return Collections.singletonList(ours);
    }
}
