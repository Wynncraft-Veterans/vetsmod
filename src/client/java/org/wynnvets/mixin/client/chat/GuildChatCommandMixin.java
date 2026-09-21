package org.wynnvets.mixin.client.chat;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.commands.GuildChatDispatcher;

/**
 * Intercepts outbound chat commands to handle guild chat ({@code /g}),
 * honourary guild chat ({@code /wg}), staff chat ({@code /v}), related
 * staff commands, and the {@code /gu invite} / {@code /guild invite}
 * gate. {@link GuildChatDispatcher#intercept} holds the full prefix list.
 *
 * <p>All business logic is delegated to {@link GuildChatDispatcher} so
 * this mixin class stays minimal and focused on the injection point.</p>
 */
@Mixin(ClientPacketListener.class)
public class GuildChatCommandMixin {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void onSendCommand(String command, CallbackInfo ci) {
        if (GuildChatDispatcher.intercept(command)) {
            ci.cancel();
        }
    }
}
