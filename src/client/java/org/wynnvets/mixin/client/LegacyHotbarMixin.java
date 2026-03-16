package org.wynnvets.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.items.LegacyItemHandler;

/**
 * Draws the legacy-item highlight on hotbar slots rendered by the in-game HUD.
 * Mirrors the behaviour of {@link LegacyHighlightMixin} which only covers
 * inventory / container screens ({@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}).
 */
@Mixin(Gui.class)
public class LegacyHotbarMixin {

  private static final int LEGACY_HIGHLIGHT_COLOR = 0xB0F0501E;

  private static final ResourceLocation WYNNTILS_HIGHLIGHT =
      ResourceLocation.fromNamespaceAndPath("wynntils", "textures/ui_components/highlight.png");
  private static final int UNIQUE_HIGHLIGHT_COLOR = 0xFFFFFF00;

  @Inject(
      method =
          "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/DeltaTracker;"
              + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
      at = @At("HEAD"))
  private void vetsmod$renderLegacyHotbarHighlight(
      GuiGraphics guiGraphics,
      int x,
      int y,
      DeltaTracker deltaTracker,
      Player player,
      ItemStack stack,
      int seed,
      CallbackInfo ci) {
    if (stack.isEmpty()) return;
    if (LegacyItemHandler.isLegacyItem(stack)) {
      guiGraphics.fill(x, y, x + 16, y + 16, LEGACY_HIGHLIGHT_COLOR);
      guiGraphics.blit(
          RenderType::guiTextured,
          WYNNTILS_HIGHLIGHT,
          x - 1,
          y - 1,
          0f,
          0f,
          18,
          18,
          256,
          256,
          UNIQUE_HIGHLIGHT_COLOR);
    }
  }
}
