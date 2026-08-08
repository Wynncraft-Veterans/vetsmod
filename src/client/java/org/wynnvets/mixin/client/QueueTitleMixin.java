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
 * <p>This mixin exists because the primary detection path via Wynntils'
 * {@code TitleSetTextEvent} can be silently bypassed when another mod
 * (e.g. WynnLimbo) injects into the same method and cancels the callback
 * before Wynntils' mixin fires.  By reading the packet ourselves at
 * {@code HEAD} with high priority, we guarantee vetsmod sees the title
 * text regardless of other mods' injection order.</p>
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
