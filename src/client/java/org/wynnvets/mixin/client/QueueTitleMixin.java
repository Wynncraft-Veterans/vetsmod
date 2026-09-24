package org.wynnvets.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.queue.QueueDetector;

/**
 * Intercepts title packets directly at the network handler level to detect
 * Wynncraft's world-queue title ("Queueing for XX##...").
 *
 * <p>This mixin exists because the primary detection path via Wynntils' {@code TitleSetTextEvent}
 * can be silently bypassed when another mod cancels that event. What keeps this path fed then is
 * vanilla's thread hop: {@code setTitleText} is entered first on the network thread, where
 * Wynntils' own {@code HEAD} inject returns early and this one runs; on the second, render-thread
 * entry Wynntils' inject runs before this one (see below) and its {@code ci.cancel()} for a
 * cancelled event skips it.
 * {@link org.wynnvets.queue.QueueDetector#handleTitleText(String) QueueDetector.handleTitleText}
 * is therefore also called off the render thread.</p>
 *
 * <p><b>The {@code priority = 500} does not add to that, and it orders the
 * opposite way from how it reads.</b> Mixin applies in ascending priority
 * order, so 500 is applied <em>before</em> the default 1000 — and at
 * {@code HEAD} the later-applied callback is prepended, so this one runs
 * <em>last</em>. A third-party cancelling {@code HEAD} inject at <b>any</b>
 * priority above 500 would skip it — the default 1000 is merely the common
 * case — which is the very scenario the
 * paragraph above cites. Nothing is known-broken today; whether the defence
 * is wanted is filed as
 * {@code queue-title-mixin-priority-inverts-its-own-goal}. See
 * {@code vetsmod_mixins.md} §"Injection priorities" for which way
 * {@code priority} runs.</p>
 */
@Mixin(value = ClientPacketListener.class, priority = 500)
public class QueueTitleMixin {

    @Inject(
            method =
                    "setTitleText(Lnet/minecraft/network/protocol/game/ClientboundSetTitleTextPacket;)V",
            at = @At("HEAD"))
    private void vetsmod$onSetTitleText(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        QueueDetector.handleTitleText(packet.text().getString());
    }
}
