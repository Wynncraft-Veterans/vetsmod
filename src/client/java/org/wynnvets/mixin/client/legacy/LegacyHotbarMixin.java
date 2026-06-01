package org.wynnvets.mixin.client.legacy;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
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

  private static final Identifier WYNNTILS_HIGHLIGHT =
      Identifier.fromNamespaceAndPath("wynntils", "textures/ui_components/sprites/highlight_wynn.png");

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
    // Bail out when legacy item highlighting is disabled
    if (!org.wynnvets.config.VetsConfig.get(org.wynnvets.config.VetsConfig.LEGACY_ITEM_HIGHLIGHTING)) return;
    if (LegacyItemHandler.isLegacyItem(stack)) {
      guiGraphics.fillGradient(x, y, x + 16, y + 16,
          org.wynnvets.config.VetsConfig.getLegacyBackgroundGradientTopColor(),
          org.wynnvets.config.VetsConfig.getLegacyBackgroundGradientBottomColor());
      guiGraphics.blit(
          RenderPipelines.GUI_TEXTURED,
          WYNNTILS_HIGHLIGHT,
          x - 1,
          y - 1,
          (float) org.wynnvets.config.VetsConfig.getLegacyForegroundSpriteOffset(),
          0f,
          18,
          18,
          256,
          256,
          org.wynnvets.config.VetsConfig.getLegacyForegroundColor());
    }
  }
}
