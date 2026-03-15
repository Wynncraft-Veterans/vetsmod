package org.wynnvets.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.chat.ChatUtils;

/**
 * Intercepts the {@code /unlock <password>} command to gate mod features
 * behind a local password check.
 *
 * <p>The unlock state is managed by {@link GuildStateManager} and persisted
 * across sessions via {@link org.wynnvets.config.VetsConfig}.</p>
 */
@Mixin(ClientPacketListener.class)
public class UnlockCommandMixin {

  @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
  private void onSendCommand(String command, CallbackInfo ci) {
    // Check if this is an unlock command
    if (command.startsWith("unlock ")) {
      String[] parts = command.split(" ", 2);

      // Must have exactly "unlock <password>" (2 parts after splitting)
      if (parts.length == 2) {
        // Cancel the server command
        ci.cancel();

        // Handle the unlock
        handleUnlock(parts[1]);
      } else if (parts.length == 1) {
        // Just "/unlock" with no password
        ci.cancel();
        ChatUtils.sendLocalMessage(
            Component.literal("Usage: /unlock <password>")
                .withStyle(ChatFormatting.RED)
        );
      }
    }
  }

  /**
   * Handle unlock command
   *
   * @param password The password to attempt
   */
  private void handleUnlock(String password) {
    // Attempt unlock
    boolean success = GuildStateManager.tryUnlock(password);

    if (success) {
      ChatUtils.sendLocalMessage(
          Component.literal("Mod unlocked successfully!")
              .withStyle(ChatFormatting.GREEN)
      );
    } else {
      ChatUtils.sendLocalMessage(
          Component.literal("Incorrect password")
              .withStyle(ChatFormatting.RED)
      );
    }
  }
}
