package org.wynnvets.retro.render;

import com.wynntils.utils.mc.McUtils;
import java.util.List;
import java.util.regex.Matcher;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.ScreenSections.SlotValue;

/**
 * The values Wynncraft prints in a container's title, which retro's title drops
 * ({@code slot_values} in {@code retro_textures.yml}): each is read from the lore of the button
 * Wynncraft draws as nothing, and drawn in that button's slot, for
 * {@link org.wynnvets.listeners.RetroSlotListener RetroSlotListener}. A failure is logged once and
 * stops the values until the game restarts. Render thread only. The row logic ({@link #valueRow},
 * {@link #value}) is pure and unit-tested.
 */
public final class RetroSlotValues {

    /** The colour of a value: white, as an item count is. */
    private static final int VALUE_COLOUR = 0xFFFFFFFF;

    private static final RetroGuard GUARD =
            RetroGuard.stopping(
                    "the slot values", "the screens' values do not show until the game restarts");

    private RetroSlotValues() {}

    /**
     * Draws {@code slot}'s value in it, centred, on a container whose label retro drew.
     *
     * @param graphics the graphics, translated to the screen's container as a slot is drawn
     * @param screen the screen drawing the slot
     * @param slot the slot
     */
    public static void drawValue(GuiGraphics graphics, Screen screen, Slot slot) {
        if (GUARD.stopped() || !RetroModeManager.isPartLive(RetroPart.SCREENS) || !slot.hasItem()) {
            return;
        }
        GUARD.run(() -> draw(graphics, screen, slot));
    }

    private static void draw(GuiGraphics graphics, Screen screen, Slot slot) {
        RetroContainerTitles.Relabelled drawn = RetroContainerTitles.relabelled(screen);
        if (drawn == null || drawn.containerClass() == null) return;
        ItemStack stack = slot.getItem();
        SlotValue row =
                valueRow(
                        RetroModeManager.manifest().screens().slotValues(),
                        drawn.containerClass(),
                        stack.getHoverName().getString());
        ItemLore lore = stack.get(DataComponents.LORE);
        if (row == null || lore == null) return;
        String value = value(row, lore.lines().stream().map(Component::getString).toList());
        if (value == null) return;
        Font font = McUtils.mc().font;
        graphics.drawString(
                font,
                value,
                slot.x + (RetroStacks.SLOT_SIZE - font.width(value)) / 2,
                slot.y + (RetroStacks.SLOT_SIZE - font.lineHeight) / 2,
                VALUE_COLOUR,
                true);
    }

    /**
     * The first {@code slot_values} row for an item on a container.
     *
     * @param rows the rows
     * @param containerClass the Wynntils container's simple class name
     * @param itemName the item's name
     * @return the row; {@code null} when none matches
     */
    // Package-private for unit tests. See RetroSlotValuesTest.
    static SlotValue valueRow(List<SlotValue> rows, String containerClass, String itemName) {
        for (SlotValue row : rows) {
            if (row.name().equals(itemName) && row.containers().matcher(containerClass).matches()) {
                return row;
            }
        }
        return null;
    }

    /**
     * The value a {@code slot_values} row reads from an item's lore.
     *
     * @param row the row
     * @param lore the item's lore lines, as plain text
     * @return the first group of the first line its pattern is found in; {@code null} when none
     */
    // Package-private for unit tests. See RetroSlotValuesTest.
    static String value(SlotValue row, List<String> lore) {
        for (String line : lore) {
            Matcher matcher = row.lore().matcher(line);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }
}
