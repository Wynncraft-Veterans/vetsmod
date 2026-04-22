package org.wynnvets.debug.dump;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Static debug-only capture of the most recent tooltip list seen by
 * {@link org.wynnvets.mixin.client.legacy.LegacyItemTooltipMixin}.
 *
 * <p>The mixin runs at the inner {@code GuiGraphics.setTooltipForNextFrame}
 * overload, which sits <em>after</em> any third-party processor that
 * intercepts {@code AbstractContainerScreen.renderTooltip}'s call site
 * (e.g. wynnmod's {@code LowAbstractContainerScreenMixin} {@code @WrapOperation}).
 * The captured input list therefore reflects post-Wynntils, post-wynnmod
 * state — exactly what vetsmod's legacy detection actually sees.</p>
 *
 * <p>Used by {@link ItemDumpHandler} to compare the captured list against
 * a freshly-built tooltip ({@code Screen.getTooltipFromItem}, which fires
 * Wynntils events but skips wynnmod's render-time wrap), making the
 * difference between the two a clean fingerprint of any third-party
 * tooltip rewrite.</p>
 */
public final class TooltipCapture {

    private static volatile boolean hasCapture = false;
    private static volatile long capturedAtNanos = 0L;
    private static volatile ItemStack capturedStack = ItemStack.EMPTY;
    private static volatile List<Component> inputSnapshot = List.of();
    private static volatile List<Component> outputSnapshot = List.of();
    private static volatile boolean processed = false;
    private static volatile boolean reentryGuardActive = false;
    private static volatile boolean inputOutputSameInstance = false;
    private static volatile Identifier borderIdentifier = null;
    private static volatile boolean lastProcessedWasLegacyAfter = false;

    private TooltipCapture() {}

    /**
     * Records a tooltip processing event. Lists are shallow-copied (the
     * Component instances themselves are immutable from the consumer's
     * point of view; Component trees are walked at serialization time).
     *
     * <p>The {@code outer} flag distinguishes the top-level vetsmod
     * tooltip pass from the recursive re-invocation that
     * {@link org.wynnvets.mixin.client.legacy.LegacyItemTooltipMixin} makes
     * after rewriting the list. Reentry calls (outer=false) are dropped
     * here because the outer record already contains the full input/output
     * pair for the same hover — keeping the inner overwrite would discard
     * the most useful information for diff tooling.</p>
     */
    public static void record(
            boolean outer,
            ItemStack stack,
            List<Component> input,
            List<Component> output,
            boolean processed,
            boolean reentryGuardActive,
            Identifier borderIdentifier,
            boolean lastProcessedWasLegacyAfter) {
        if (!outer && hasCapture) {
            // Don't let the recursive setTooltipForNextFrame call clobber the
            // outer record; the outer record is the meaningful one.
            return;
        }
        TooltipCapture.capturedStack = stack;
        TooltipCapture.inputOutputSameInstance = (input == output);
        TooltipCapture.inputSnapshot = input != null ? new ArrayList<>(input) : List.of();
        TooltipCapture.outputSnapshot = output != null ? new ArrayList<>(output) : List.of();
        TooltipCapture.processed = processed;
        TooltipCapture.reentryGuardActive = reentryGuardActive;
        TooltipCapture.borderIdentifier = borderIdentifier;
        TooltipCapture.lastProcessedWasLegacyAfter = lastProcessedWasLegacyAfter;
        TooltipCapture.capturedAtNanos = System.nanoTime();
        TooltipCapture.hasCapture = true;
    }

    public static boolean inputOutputSameInstance() {
        return inputOutputSameInstance;
    }

    public static boolean hasCapture() {
        return hasCapture;
    }

    public static long capturedAtNanos() {
        return capturedAtNanos;
    }

    public static ItemStack capturedStack() {
        return capturedStack;
    }

    public static List<Component> inputSnapshot() {
        return inputSnapshot;
    }

    public static List<Component> outputSnapshot() {
        return outputSnapshot;
    }

    public static boolean processed() {
        return processed;
    }

    public static boolean reentryGuardActive() {
        return reentryGuardActive;
    }

    public static Identifier borderIdentifier() {
        return borderIdentifier;
    }

    public static boolean lastProcessedWasLegacyAfter() {
        return lastProcessedWasLegacyAfter;
    }
}
