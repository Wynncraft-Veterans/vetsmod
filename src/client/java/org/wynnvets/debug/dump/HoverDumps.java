package org.wynnvets.debug.dump;

import com.google.gson.JsonObject;
import com.wynntils.utils.mc.McUtils;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.items.LegacyItemHandler;
import org.wynnvets.util.ContainerScreens;

/**
 * Reads the live client state behind an item dump: the slot under the cursor, a tooltip built fresh
 * for the stack, and the last tooltip {@link TooltipCapture} recorded at render time.
 */
final class HoverDumps {

    private HoverDumps() {}

    // ── Hovered-slot dumping ─────────────────────────────────────────

    /**
     * Records which slot was being hovered when the dump was triggered.
     * Validates the stashed slot reference against the current screen and
     * its menu so that stale state (e.g. dump key pressed after the
     * container closed) is reported as unavailable rather than silently
     * emitting bogus indices.
     */
    static JsonObject buildHoveredSlotDump(ItemStack stack) {
        JsonObject obj = new JsonObject();
        Slot slot = LegacyItemHandler.currentHoveredSlot;
        if (slot == null) {
            obj.addProperty("status", "no slot recorded (hover an item in a container first)");
            return obj;
        }

        AbstractContainerScreen<?> containerScreen = ContainerScreens.current();
        if (containerScreen == null) {
            obj.addProperty(
                    "status", "no container screen open (stashed slot reference may be stale)");
            return obj;
        }
        // Reject stale references — Slot belongs to the previous menu.
        if (slot.container != null && containerScreen.getMenu().slots.indexOf(slot) < 0) {
            obj.addProperty("status", "stashed slot does not belong to current menu (stale)");
            return obj;
        }

        obj.addProperty("status", "ok");
        // The number macros / ServerboundContainerClickPacket want.
        obj.addProperty("menuSlotIndex", slot.index);
        obj.addProperty("containerSlotIndex", slot.getContainerSlot());
        int containerSize = slot.container != null ? slot.container.getContainerSize() : -1;
        obj.addProperty("containerSize", containerSize);
        // Wynncraft chest GUIs are always 9 columns wide; derive row/col so
        // macro authors can cross-check against the in-game grid layout.
        int containerSlot = slot.getContainerSlot();
        obj.addProperty("row", containerSlot / 9);
        obj.addProperty("column", containerSlot % 9);
        obj.addProperty("x", slot.x);
        obj.addProperty("y", slot.y);
        obj.addProperty("containerId", containerScreen.getMenu().containerId);
        obj.addProperty("screenTitle", containerScreen.getTitle().getString());
        obj.addProperty(
                "stackMatchesSlotItem", ItemStack.isSameItemSameComponents(slot.getItem(), stack));
        return obj;
    }

    // ── Tooltip-line dumping ─────────────────────────────────────────

    /**
     * Builds a fresh tooltip via {@link Screen#getTooltipFromItem} and
     * serializes each line. Returns a JsonObject with status info and an
     * array of line dumps. The fresh tooltip bypasses every render-time
     * wrap, Wynntils' {@code ItemTooltipRenderEvent.Pre} rewrites as well as
     * wynnmod's decoration, though Wynntils' {@code ItemTooltipFlagsEvent}
     * still fires (it hooks {@code ItemStack#getTooltipLines}).
     */
    static JsonObject buildFreshTooltipDump(ItemStack stack) {
        JsonObject obj = new JsonObject();
        Minecraft mc = McUtils.mc();
        if (mc == null) {
            obj.addProperty("status", "no Minecraft instance");
            return obj;
        }
        if (mc.screen == null) {
            obj.addProperty(
                    "status", "no screen open (the fresh dump is only taken under an open screen)");
            return obj;
        }
        try {
            List<Component> fresh = Screen.getTooltipFromItem(mc, stack);
            obj.addProperty("status", "ok");
            obj.addProperty("lineCount", fresh.size());
            obj.add("lines", ComponentDumps.dumpComponentLines(fresh));
        } catch (Throwable t) {
            obj.addProperty("status", "error");
            obj.addProperty("error", t.toString());
        }
        return obj;
    }

    /**
     * Serializes the most recent capture from {@link TooltipCapture}, with
     * staleness flags and the metadata recorded at capture time.
     */
    static JsonObject buildCapturedTooltipDump(ItemStack stack) {
        JsonObject obj = new JsonObject();
        if (!TooltipCapture.hasCapture()) {
            obj.addProperty("status", "no capture recorded yet (hover a tooltip first)");
            return obj;
        }
        long ageNanos = System.nanoTime() - TooltipCapture.capturedAtNanos();
        obj.addProperty("status", "ok");
        obj.addProperty("ageMillis", ageNanos / 1_000_000L);
        obj.addProperty(
                "capturedStackMatchesDumpedStack",
                ItemStack.isSameItemSameComponents(TooltipCapture.capturedStack(), stack));
        obj.addProperty("processed", TooltipCapture.processed());
        obj.addProperty("reentryGuardActive", TooltipCapture.reentryGuardActive());
        Identifier border = TooltipCapture.borderIdentifier();
        obj.addProperty("borderIdentifier", border != null ? border.toString() : null);
        obj.addProperty(
                "lastProcessedWasLegacyAfter", TooltipCapture.lastProcessedWasLegacyAfter());

        List<Component> input = TooltipCapture.inputSnapshot();
        List<Component> output = TooltipCapture.outputSnapshot();
        obj.addProperty("inputLineCount", input.size());
        obj.addProperty("outputLineCount", output.size());
        obj.addProperty("outputIsSameInstance", TooltipCapture.inputOutputSameInstance());
        obj.add("inputLines", ComponentDumps.dumpComponentLines(input));
        // TooltipCapture.record copies input and output into separate ArrayLists,
        // so the output tree is always dumped; outputIsSameInstance above is what
        // says whether the mixin rewrote the list.
        obj.add("outputLines", ComponentDumps.dumpComponentLines(output));
        return obj;
    }
}
