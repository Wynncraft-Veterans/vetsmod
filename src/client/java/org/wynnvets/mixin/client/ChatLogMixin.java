package org.wynnvets.mixin.client;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.util.ChatLogger;

@Mixin(ChatComponent.class)
public class ChatLogMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void onChatMessage(Component message, CallbackInfo ci) {
        // Log the chat message to file
        ChatLogger.logMessage(message.getString());
    }
}
