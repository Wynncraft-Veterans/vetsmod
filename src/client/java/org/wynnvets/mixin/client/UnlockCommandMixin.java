package org.wynnvets.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.util.GuildInfoListener;

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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
          minecraft.player.displayClientMessage(
              Component.literal("Usage: /unlock <password>")
                  .withStyle(ChatFormatting.RED),
              false
          );
        }
      }
    }
  }

  /**
   * Handle unlock command
   *
   * @param password The password to attempt
   */
  private void handleUnlock(String password) {
    Minecraft minecraft = Minecraft.getInstance();

    if (minecraft.player == null) {
      return;
    }

    // Attempt unlock
    boolean success = GuildInfoListener.tryUnlock(password);

    if (success) {
      minecraft.player.displayClientMessage(
          Component.literal("Mod unlocked successfully!")
              .withStyle(ChatFormatting.GREEN),
          false
      );
    } else {
      minecraft.player.displayClientMessage(
          Component.literal("Incorrect password")
              .withStyle(ChatFormatting.RED),
          false
      );
    }
  }
}
