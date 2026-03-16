package org.wynnvets.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.fetcher.polling.BridgeMessageFetcher;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.chat.ChatUtils;

/**
 * Intercepts the {@code /frumamode} command to toggle Fruma bridge mode.
 *
 * <p>When Fruma mode is active, guild chat messages received from the server
 * are suppressed if they were already displayed via the bridge fetcher,
 * preventing duplicate messages for players in the Fruma region.</p>
 */
@Mixin(ClientPacketListener.class)
public class FrumaModeCommandMixin {

  @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
  private void onSendCommand(String command, CallbackInfo ci) {
    if (!command.regionMatches(true, 0, "frumamode", 0, 9)) {
      return;
    }

    ci.cancel();
    String[] parts = command.trim().split("\\s+");
    if (parts.length > 1) {
      ChatUtils.sendLocalMessage(
          Component.literal("Usage: /frumamode")
              .withStyle(ChatFormatting.RED)
      );
      return;
    }

    if (!GuildStateManager.canExecuteCommands()) {
      ChatUtils.sendLocalMessage(
          Component.literal("Please wait until you have joined a world before using /frumamode.")
              .withStyle(ChatFormatting.YELLOW)
      );
      return;
    }

    if (GuildStateManager.isGuildless()) {
      ChatUtils.sendLocalMessage(
          Component.literal("[TEMP] You must be in a guild to use /frumamode.")
              .withStyle(ChatFormatting.RED)
      );
      return;
    }

    boolean nextState = !BridgeMessageFetcher.isFrumaModeEnabled();
    BridgeMessageFetcher.setFrumaModeEnabled(nextState);

    ChatUtils.sendLocalMessage(
        Component.literal("[TEMP] Fruma mode ")
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(nextState ? "enabled" : "disabled")
                .withStyle(nextState ? ChatFormatting.GREEN : ChatFormatting.RED))
            .append(Component.literal(".").withStyle(ChatFormatting.YELLOW))
    );
  }
}