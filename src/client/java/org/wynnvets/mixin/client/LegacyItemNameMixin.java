package org.wynnvets.mixin.client;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.wynnvets.items.ItemDefinitions;
import org.wynnvets.items.LegacyItemHandler;

/**
 * Recolors the display name returned by {@link ItemStack#getHoverName()} to
 * gold for legacy items. This ensures every rendering path (Wynntils' held-item
 * overlay, vanilla tooltip first line, chat item brackets, etc.) shows the
 * legacy-gold name without needing per-site hooks.
 */
@Mixin(ItemStack.class)
public class LegacyItemNameMixin {

  @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
  private void vetsmod$goldLegacyName(CallbackInfoReturnable<Component> cir) {
    ItemStack self = (ItemStack) (Object) this;
    Component original = cir.getReturnValue();
    String plain =
        LegacyItemHandler.normalizeName(ChatFormatting.stripFormatting(original.getString()));

    // Name-based legacy pattern
    if (plain != null && ItemDefinitions.isLegacy(plain)) {
      cir.setReturnValue(Component.literal(plain).withStyle(ChatFormatting.GOLD));
      return;
    }

    // Foil check (enchanted unidentified) — cheap, no lore access
    if (self.hasFoil() && plain != null && !ItemDefinitions.isUnenchanted(plain)) {
      cir.setReturnValue(
          Component.literal("\u2B21 ")
              .withStyle(ChatFormatting.WHITE)
              .append(
                  Component.literal("Enchanted " + plain).withStyle(ChatFormatting.GOLD)));
      return;
    }

    // Lore-based checks
    List<Component> lore = self.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
    if (!lore.isEmpty()) {
      if (LegacyItemHandler.hasBetaLegacyMarker(lore)) {
        if (plain != null) {
          cir.setReturnValue(Component.literal(plain).withStyle(ChatFormatting.GOLD));
        }
        return;
      }

      if (LegacyItemHandler.hasJunkRarity(lore)
          && (plain == null || !ItemDefinitions.isNotJunk(plain))) {
        if (plain != null) {
          cir.setReturnValue(Component.literal(plain).withStyle(ChatFormatting.GOLD));
        }
      }
    }
  }
}
