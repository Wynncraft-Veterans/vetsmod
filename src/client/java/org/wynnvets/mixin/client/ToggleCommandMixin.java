package org.wynnvets.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.util.chat.ChatUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

@Mixin(ClientPacketListener.class)
public class ToggleCommandMixin {

  @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
  private void onSendCommand(String command, CallbackInfo ci) {
    // Check if this is a toggle command
    if (command.startsWith("toggle ")) {
      String[] parts = command.split(" ", 3);

      // Must have at least "toggle <key>" (2 parts after splitting)
      if (parts.length >= 2) {
        String key = parts[1];

        // Only intercept if the key is one of our VetsMod config keys
        if (VetsConfig.hasKey(key)) {
          // Cancel the server command
          ci.cancel();

          // Handle the toggle
          handleVetsToggle(parts);
        }
      }
    }
  }

  /**
   * Handle VetsMod toggle commands
   *
   * @param parts Command split by spaces
   */
  private void handleVetsToggle(String[] parts) {
    String key = parts[1];

    // If no value provided, show current value
    if (parts.length < 3) {
      boolean currentValue = VetsConfig.get(key);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " is currently ")
              .withStyle(ChatFormatting.YELLOW)
              .append(Component.literal(String.valueOf(currentValue))
                  .withStyle(currentValue ? ChatFormatting.GREEN : ChatFormatting.RED))
      );
      return;
    }

    String valueStr = parts[2].toLowerCase();

    // Parse boolean value
    Boolean newValue = null;
    if (valueStr.equals("true") || valueStr.equals("on") || valueStr.equals("enabled")) {
      newValue = true;
    } else if (valueStr.equals("false") || valueStr.equals("off") || valueStr.equals("disabled")) {
      newValue = false;
    }

    if (newValue == null) {
      ChatUtils.sendLocalMessage(
          Component.literal("Invalid value. Use: true/false, on/off, or enabled/disabled")
              .withStyle(ChatFormatting.RED)
      );
      return;
    }

    // Set the value
    VetsConfig.set(key, newValue);

    // Confirm to player
    ChatUtils.sendLocalMessage(
        Component.literal(key + " set to ")
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(String.valueOf(newValue))
                .withStyle(newValue ? ChatFormatting.GREEN : ChatFormatting.RED))
    );
  }
}
