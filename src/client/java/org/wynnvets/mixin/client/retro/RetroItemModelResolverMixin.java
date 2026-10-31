package org.wynnvets.mixin.client.retro;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.wynnvets.retro.render.RetroStackModels;

/**
 * Draws retro's single stacks as another item.
 *
 * <p>{@code appendItemLayers} reads the stack's {@code minecraft:item_model} and draws the client
 * item it names, whether in a slot, in hand or on the ground. While the retro pack is live on
 * Wynncraft, {@link RetroStackModels#model} answers with a {@code stack_models} row's item instead;
 * the stack keeps its component, so Wynntils and the rest of the game see it unchanged. The body is
 * one call into code that does nothing while the pack is not live on Wynncraft.</p>
 */
@Mixin(ItemModelResolver.class)
public abstract class RetroItemModelResolverMixin {

    /** Hands the item model about to be drawn to {@link RetroStackModels#model}. */
    @ModifyExpressionValue(
            method =
                    "appendItemLayers(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object vetsmod$retroStackModel(Object model, @Local(argsOnly = true) ItemStack stack) {
        return RetroStackModels.model(stack, (Identifier) model);
    }
}
