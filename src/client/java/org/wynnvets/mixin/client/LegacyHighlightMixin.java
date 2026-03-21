package org.wynnvets.mixin.client;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.items.LegacyItemHandler;

/**
 * Draws a red-orange background highlight on inventory slots that contain
 * legacy items, overriding any existing Wynntils rarity highlight.
 * Also captures the hovered item's foil state for tooltip processing.
 */
@Mixin(AbstractContainerScreen.class)
public class LegacyHighlightMixin {

  // Red-orange between fabled (#FF5555) and T3 ingredient (#E64D00)
  // ARGB: ~69% opacity, RGB (240, 80, 30)
  private static final int LEGACY_HIGHLIGHT_COLOR = 0xB0F0501E;

  // Wynntils highlight spritesheet: 256x256, each tile 18x18, WYNN variant at ordinal 0
  private static final Identifier WYNNTILS_HIGHLIGHT =
      Identifier.fromNamespaceAndPath("wynntils", "textures/ui_components/highlight.png");
  // Wynntils "unique" rarity tint — fully opaque yellow
  private static final int UNIQUE_HIGHLIGHT_COLOR = 0xFFFFFF00;

  @Shadow protected Slot hoveredSlot;

  @Inject(method = "renderSlot", at = @At("HEAD"))
  private void vetsmod$renderLegacyHighlight(
      GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
    ItemStack stack = slot.getItem();
    if (stack.isEmpty()) return;
    // Bail out when legacy item highlighting is disabled
    if (!org.wynnvets.config.VetsConfig.get(org.wynnvets.config.VetsConfig.LEGACY_ITEM_HIGHLIGHTING)) return;
    // Skip menus that abuse enchantment glints as selectors (e.g. "Island Rules")
    if (LegacyItemHandler.isBlockedScreen()) return;

    String name = LegacyItemHandler.normalizeName(
        ChatFormatting.stripFormatting(stack.getHoverName().getString()));
    if (name != null && ItemDefinitions.isLegacy(name)) {
      drawLegacyHighlight(guiGraphics, slot);
      return;
    }

    List<Component> lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();

    // Misc-category legacy items (e.g. Raw Cod, Gunpowder) — matched by name in
    // misc_definitions AND confirmed by a "Misc. Item" rarity line in lore.
    if (name != null && ItemDefinitions.isMiscLegacy(name) && LegacyItemHandler.hasMiscRarity(lore)) {
      drawLegacyHighlight(guiGraphics, slot);
      return;
    }

    if (LegacyItemHandler.hasBetaLegacyMarker(lore)) {
      drawLegacyHighlight(guiGraphics, slot);
      return;
    }

    if (stack.hasFoil() && !ItemDefinitions.isEnchantExcludedItem(stack)) {
      String foilName = LegacyItemHandler.normalizeName(
          ChatFormatting.stripFormatting(stack.getHoverName().getString()));
      if (foilName == null || !ItemDefinitions.isUnenchanted(foilName)) {
        drawLegacyHighlight(guiGraphics, slot);
        return;
      }
    }

    if (LegacyItemHandler.hasJunkRarity(lore) && (name == null || !ItemDefinitions.isNotJunk(name))) {
      drawLegacyHighlight(guiGraphics, slot);
      return;
    }

    // Crafting-rarity items — any item whose lore contains a "Crafting Item" rarity line.
    if (LegacyItemHandler.hasCraftingRarity(lore)) {
      drawLegacyHighlight(guiGraphics, slot);
    }
  }

  private static void drawLegacyHighlight(GuiGraphics guiGraphics, Slot slot) {
    guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, LEGACY_HIGHLIGHT_COLOR);
    guiGraphics.blit(
        RenderPipelines.GUI_TEXTURED,
        WYNNTILS_HIGHLIGHT,
        slot.x - 1,
        slot.y - 1,
        0f,
        0f,
        18,
        18,
        256,
        256,
        UNIQUE_HIGHLIGHT_COLOR);
  }

  @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"))
  private void vetsmod$captureHoveredItemFoil(
      GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
    if (hoveredSlot != null && hoveredSlot.hasItem()) {
      ItemStack hovered = hoveredSlot.getItem();
      LegacyItemHandler.currentItemHasFoil = hovered.hasFoil() && !ItemDefinitions.isEnchantExcludedItem(hovered);
    } else {
      LegacyItemHandler.currentItemHasFoil = false;
    }
  }
}
