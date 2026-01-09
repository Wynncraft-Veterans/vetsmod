package org.wynnvets.mixin.client;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.util.ChatLogger;
import org.wynnvets.util.GuildInfoListener;

@Mixin(ChatComponent.class)
public class ChatLogMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void onChatMessage(Component message, CallbackInfo ci) {
        String messageString = message.getString();
        
        // Always log the chat message to file
        ChatLogger.logMessage(messageString);
        
        // Check if this is a guild stats message BEFORE processing
        boolean isGuildStats = GuildInfoListener.isProcessingModGuildStats() && isGuildStatsMessage(messageString);
        
        // Always process the message for guild info detection (even if we'll suppress it)
        GuildInfoListener.processMessage(message, messageString);
        
        // Hide guild stats output if it was initiated by the mod (not the user)
        if (isGuildStats) {
            ci.cancel(); // Suppress the message from being displayed
        }
    }
    
    /**
     * Checks if a message is part of the guild stats output
     */
    private boolean isGuildStatsMessage(String message) {
        String trimmed = message.trim();
        
        // Empty lines are part of guild stats output
        if (trimmed.isEmpty()) {
            return true;
        }
        
        // Check for guild stats specific content
        return trimmed.contains("Guild Since:") ||
               trimmed.contains("Owner:") ||
               trimmed.contains("Guild Level:") ||
               trimmed.contains("Needed XP:") ||
               trimmed.contains("Guild Rank:") ||
               trimmed.contains("Total Members:") ||
               // Match the guild name line - it's typically just the guild name alone
               trimmed.equals("Returners") ||
               // Match any reasonable guild name pattern (alphanumeric, spaces, underscores, etc.)
               // But exclude MOTD and other formatted messages
               (trimmed.length() > 0 && trimmed.length() < 50 && 
                !trimmed.contains(":") && 
                !trimmed.contains("Welcome") && 
                !trimmed.contains("VETSMOD") &&
                message.matches("^[A-Za-z0-9_ ]+$"));
    }
}
