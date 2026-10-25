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
 * can be silently bypassed when another mod cancels that event. Vanilla enters
 * {@code setTitleText} twice per packet: first on the network thread, where Wynntils' own
 * {@code HEAD} inject returns early, then on the render thread, where it fires the event and
 * {@code ci.cancel()}s for a cancelled one. This inject runs ahead of Wynntils' on both
 * entries (see below), so a cancelled event cannot starve it.
 * {@link org.wynnvets.queue.QueueDetector#handleTitleText(String) QueueDetector.handleTitleText}
 * is therefore also called off the render thread.</p>
 *
 * <p><b>The {@code priority = 500} is what makes that true.</b> Mixin applies in ascending
 * priority order, so a lower number is applied first, and at {@code HEAD} each callback goes in
 * before the method's original first instruction, so the first-applied runs <em>first</em>
 * (confirmed in game on 2026-10-23). 500 is below the default 1000 that Wynntils' mixin and most
 * third-party mods use, so this inject runs ahead of their {@code HEAD} injects and a cancel from
 * any of them cannot skip it. Running first costs nothing, since the inject never cancels. See
 * {@code vetsmod_mixins.md} &sect;"Injection priorities" for which way {@code priority} runs.</p>
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
