package org.wynnvets.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.wynnvets.queue.QueueStateManager;

/**
 * Suppresses Brigadier's command-error visuals while the player is in the
 * Wynncraft world queue.  The queue server registers almost no commands, so
 * every typed command (e.g. {@code /g}) triggers a red "Unknown or incomplete
 * command" tooltip and red input highlighting.  Neither is useful — vetsmod
 * intercepts the relevant commands client-side anyway.
 */
@Mixin(CommandSuggestions.class)
public class CommandSuggestionsMixin {

    /**
     * Prevents the red error text from rendering below the chat input.
     */
    @Inject(
            method = "renderUsage(Lnet/minecraft/client/gui/GuiGraphics;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void vetsmod$suppressQueueUsageErrors(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (QueueStateManager.isInQueue()) {
            ci.cancel();
        }
    }

    /**
     * Prevents Brigadier from applying red {@code UNPARSED_STYLE} formatting
     * to the command text in the input box.  Returning {@code null} tells the
     * {@link net.minecraft.client.gui.components.EditBox} to use its default
     * (white) formatter instead.
     */
    @Inject(
            method = "formatChat(Ljava/lang/String;I)Lnet/minecraft/util/FormattedCharSequence;",
            at = @At("HEAD"),
            cancellable = true)
    private void vetsmod$suppressQueueInputFormatting(
            String string, int i, CallbackInfoReturnable<FormattedCharSequence> cir) {
        if (QueueStateManager.isInQueue()) {
            cir.setReturnValue(null);
        }
    }
}
