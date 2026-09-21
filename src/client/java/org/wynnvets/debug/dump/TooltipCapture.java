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
 * a freshly-built tooltip ({@code Screen.getTooltipFromItem}).  That path
 * fires Wynntils' {@code ItemTooltipFlagsEvent} but none of the render-time
 * call-site wraps: neither Wynntils' own {@code ItemTooltipRenderEvent.Pre},
 * where its tooltip features rewrite the list, nor wynnmod's wrap.  The
 * difference between the two is therefore a fingerprint of render-time
 * tooltip rewrites, Wynntils' included.</p>
 */
public final class TooltipCapture {

    // All ten fields below are written only by record() and read through the
    // accessors. The initial values stand until the first stored record;
    // hasCapture never returns to false.
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

    /**
     * Whether the last stored record's input and output were the same list
     * instance, which
     * {@link org.wynnvets.mixin.client.legacy.LegacyItemTooltipMixin
     * LegacyItemTooltipMixin} passes when it did not rewrite the tooltip.
     * Use this rather than comparing the snapshots:
     * {@link #record} copies input and output into separate lists.
     */
    public static boolean inputOutputSameInstance() {
        return inputOutputSameInstance;
    }

    /** Whether {@link #record} has stored a capture yet; never reset. */
    public static boolean hasCapture() {
        return hasCapture;
    }

    /**
     * {@code System.nanoTime()} at the last stored record, or {@code 0} before
     * the first; meaningful only as a difference from another
     * {@code nanoTime()} reading.
     */
    public static long capturedAtNanos() {
        return capturedAtNanos;
    }

    /**
     * The stack passed with the last stored record.
     * {@link org.wynnvets.mixin.client.legacy.LegacyItemTooltipMixin
     * LegacyItemTooltipMixin} passes
     * {@link org.wynnvets.items.LegacyItemHandler#currentItemStack
     * LegacyItemHandler.currentItemStack}, which is set outside the hooked
     * call and may not belong to the recorded tooltip; {@code ItemStack.EMPTY}
     * before the first record.
     */
    public static ItemStack capturedStack() {
        return capturedStack;
    }

    /**
     * A shallow copy of the list the mixin received on the last stored record;
     * an empty list before the first.
     */
    public static List<Component> inputSnapshot() {
        return inputSnapshot;
    }

    /**
     * A shallow copy of the list passed on for rendering: the rewritten list
     * when {@link #processed()} is true, otherwise the input again; an empty
     * list before the first record.
     */
    public static List<Component> outputSnapshot() {
        return outputSnapshot;
    }

    /**
     * Whether the last stored record came from a pass in which
     * {@link org.wynnvets.items.LegacyItemHandler#processTooltip LegacyItemHandler.processTooltip}
     * returned a rewritten list, so the mixin cancelled the call and re-invoked
     * it with the rewrite.
     */
    public static boolean processed() {
        return processed;
    }

    /**
     * Whether the last stored record was made while the mixin's reentry guard
     * was set — in practice always {@code false}: the only call that passes
     * {@code true} is the mixin's reentry branch, which cannot run before the
     * rewrite branch has already stored a record, so {@link #record} drops it.
     */
    public static boolean reentryGuardActive() {
        return reentryGuardActive;
    }

    /**
     * The tooltip-style identifier carried by the last stored record:
     * {@link org.wynnvets.items.LegacyItemHandler#LEGACY_BORDER LEGACY_BORDER}
     * when the list was rewritten, the item was flagged legacy and new tooltip
     * styles are available; otherwise the call's own background identifier,
     * which may be {@code null}. {@code null} before the first record.
     */
    public static Identifier borderIdentifier() {
        return borderIdentifier;
    }

    /**
     * {@link org.wynnvets.items.LegacyItemHandler#lastProcessedWasLegacy
     * LegacyItemHandler.lastProcessedWasLegacy} as read when the last stored
     * record was made, after
     * {@link org.wynnvets.items.LegacyItemHandler#processTooltip
     * LegacyItemHandler.processTooltip} had run for that pass.
     */
    public static boolean lastProcessedWasLegacyAfter() {
        return lastProcessedWasLegacyAfter;
    }
}
