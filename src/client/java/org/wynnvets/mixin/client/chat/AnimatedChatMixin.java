package org.wynnvets.mixin.client.chat;

import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.wynnvets.rendering.colors.AnimatedGradientSequence;

/**
 * Intercepts chat message display-queue insertion to replace static
 * {@link FormattedCharSequence}s with {@link AnimatedGradientSequence}
 * wrappers when an animation config has been set via
 * {@link AnimatedGradientSequence#beginAnimation}.
 *
 * <p>New {@code GuiMessage.Line} entries are created at the beginning of
 * {@code trimmedMessages} (via {@code addFirst}). We record the list size
 * before processing and replace the newly inserted entries afterwards.</p>
 */
@Mixin(ChatComponent.class)
public class AnimatedChatMixin {

    @Shadow private List<GuiMessage.Line> trimmedMessages;

    /** First pre-existing line before current insertion, used as insertion boundary. */
    @Unique private GuiMessage.Line vetsmod$prevFirstLine = null;

    @Inject(method = "addMessageToDisplayQueue", at = @At("HEAD"))
    private void vetsmod$beforeAddLines(GuiMessage message, CallbackInfo ci) {
        vetsmod$prevFirstLine = trimmedMessages.isEmpty() ? null : trimmedMessages.get(0);
    }

    @Inject(method = "addMessageToDisplayQueue", at = @At("RETURN"))
    private void vetsmod$afterAddLines(GuiMessage message, CallbackInfo ci) {
        int added = 0;
        if (vetsmod$prevFirstLine == null) {
            added = trimmedMessages.size();
        } else {
            while (added < trimmedMessages.size()
                    && trimmedMessages.get(added) != vetsmod$prevFirstLine) {
                added++;
            }
        }

        // New lines are inserted at the front of the list (addFirst).
        for (int i = 0; i < added; i++) {
            GuiMessage.Line old = trimmedMessages.get(i);
            FormattedCharSequence animated =
                    new AnimatedGradientSequence(
                            old.content(),
                            AnimatedGradientSequence.effectiveDefaultStart(),
                            AnimatedGradientSequence.effectiveDefaultEnd(),
                            AnimatedGradientSequence.DEFAULT_CYCLE_TIME_MS);
            trimmedMessages.set(
                    i, new GuiMessage.Line(old.addedTime(), animated, old.tag(), old.endOfEntry()));
        }

        vetsmod$prevFirstLine = null;
    }
}
