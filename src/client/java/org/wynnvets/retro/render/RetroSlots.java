package org.wynnvets.retro.render;

import com.wynntils.models.inventory.type.InventoryAccessory;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.InventoryUtils;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TooltipDisplay;
import org.wynnvets.rendering.colors.VanillaGuiColors;
import org.wynnvets.rendering.image.Rect;
import org.wynnvets.retro.LivePack;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.ScreenSections;
import org.wynnvets.retro.manifest.ScreenSections.SlotFill;

/**
 * The slots retro marks ({@code slot_fills} in {@code retro_textures.yml}), and the slots that stop
 * answering the mouse.
 *
 * <p>Slot fills apply on the inventory screen and on the containers whose label
 * {@link RetroContainerTitles} draws, since those show the vanilla container; never on a container
 * that keeps Wynncraft's label and art, never on Wynntils' full-screen views (whose player
 * inventory part Wynncraft fills with its own buttons, so the player's slots there are not the
 * player's: the content book's scroll buttons are menu slots 65 and 69), and only while the retro
 * pack is live. A {@code player} row picks the player's slots that Wynntils names, matched by the
 * slot's own inventory and its index there, never by the menu index (in the inventory's menu, 7
 * and 8 are armour). A container row picks slots of a chest by row, column and number, every slot
 * outside Wynntils' bounds, or every slot ({@code slots: all}); never a slot in its
 * {@code except}, nor any slot of a container its {@code unless} matches. A picked slot shows white
 * glass, under its item if it has one, since its item cannot be moved; an accessory slot, which the
 * player fills, stays a slot under a white edge. With the {@code glass} part off no row picks a
 * slot; the inventory's slots the live pack painted over stay inert, and it paints them only with
 * {@code screens} on.</p>
 *
 * <ul>
 *   <li>{@link #paintIfPanel}, for
 *       {@link org.wynnvets.listeners.RetroSlotListener RetroSlotListener}: the white glass,
 *       before the slot's item is drawn over it, the slot's frame left showing, and the accessory
 *       slots' white edge.</li>
 *   <li>{@link #hovered}, for
 *       {@link org.wynnvets.mixin.client.retro.RetroHoveredSlotMixin RetroHoveredSlotMixin}: no
 *       hovered slot over an inventory slot the live pack's {@code image_edits} painted over (the
 *       crafting grid, result and offhand; {@link LivePack#imageEdits}), nor over an
 *       empty panel slot that never takes an item. Accessory slots stay hoverable, so an
 *       accessory can still be put on by click or drag. Only on Wynncraft
 *       ({@link RetroModeManager#isLiveOnWynncraft()}): the pack can stay live in singleplayer,
 *       where every slot is a slot, and the crafting grid the texture edits hide still works.</li>
 * </ul>
 *
 * <p>Which slots a screen has is worked out once per screen, and again if its label decision
 * changes. A failure drawing a slot is logged once and costs that slot that frame. A failure
 * finding what the mouse is over is logged once and stops the mouse rule until the game restarts,
 * so every slot then answers the mouse. The row logic works on plain
 * numbers ({@link #containerSlots}, {@link #covers}, {@link #paintedOver}, {@link #panelsOf}) and
 * is unit-tested; nothing static here touches Wynntils. Render thread only.</p>
 */
public final class RetroSlots {

    /** A chest's width. */
    private static final int COLUMNS = ScreenSections.LAST_COLUMN + 1;

    /**
     * The texture the inventory screen draws ({@code AbstractContainerScreen.INVENTORY_LOCATION}),
     * as {@code image_edits} names it.
     */
    // Package-private for unit tests. See RetroSlotsTest.
    static final String INVENTORY_TEXTURE = "minecraft:gui/container/inventory";

    private static final RetroGuard DRAW_GUARD =
            RetroGuard.retrying("drawing a panel slot", "it shows as a plain slot");

    private static final RetroGuard MOUSE_GUARD =
            RetroGuard.stopping(
                    "the panel slots' mouse rule",
                    "every slot answers the mouse until the game restarts");

    private static final RetroGuard WORK_OUT_GUARD =
            RetroGuard.retrying("working out a screen's panel slots", "none are drawn on it");

    /**
     * The panel slots of the last screen asked about, by the label decision they were worked out
     * with: a change means work them out again.
     */
    private static final WeakIdentityMemo<
                    AbstractContainerScreen<?>,
                    IdentityMemo<RetroContainerTitles.Relabelled, Panels>>
            PANELS = new WeakIdentityMemo<>();

    /** The letter Wynncraft's names use as a space (its font draws it blank). */
    private static final char WYNN_SPACE = '\u00C0';

    /** How thick the white edge of the accessory slots is, against a slot's own one pixel. */
    private static final int BORDER = 2;

    /** Wynntils' accessory slots, read on first use. */
    private static Set<Integer> accessorySlots;

    private RetroSlots() {}

    /**
     * A screen's panel slots, by menu index.
     *
     * @param picked the slots a {@code slot_fills} row picks
     * @param unmovable those whose item is fixed, which show white glass and with no item answer no
     *     mouse. The others, the accessories, are the player's to fill
     * @param inert the slots that never answer the mouse
     */
    // Package-private for unit tests. See RetroSlotsTest.
    record Panels(BitSet picked, BitSet unmovable, BitSet inert) {
        static final Panels NONE = new Panels(new BitSet(), new BitSet(), new BitSet());
    }

    /**
     * What {@link #panelsOf} needs of a slot.
     *
     * @param index the slot's index in its menu
     * @param containerSlot the slot's index in its own inventory
     * @param own whether its inventory is the player's
     * @param x the slot's column, {@code Slot.x}
     * @param y the slot's row, {@code Slot.y}
     */
    // Package-private for unit tests. See RetroSlotsTest.
    record SlotKey(int index, int containerSlot, boolean own, int x, int y) {}

    /**
     * Marks {@code slot} when a {@code slot_fills} row picks it on {@code screen}: white glass,
     * under its item if it has one, where the item cannot be moved; and for an accessory slot,
     * which stays a plain slot, a thick white edge along its bottom, and for the last along its
     * right, which sets the four apart from the inventory they sit in.
     *
     * @param graphics the graphics, translated to the screen's container as a slot is drawn
     * @param screen the screen drawing the slot
     * @param slot the slot
     */
    public static void paintIfPanel(GuiGraphics graphics, Screen screen, Slot slot) {
        if (!RetroModeManager.isPartLive(RetroPart.GLASS)
                || !(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        DRAW_GUARD.run(() -> paint(graphics, container, slot));
    }

    private static void paint(
            GuiGraphics graphics, AbstractContainerScreen<?> container, Slot slot) {
        Panels found = panels(container);
        if (!found.picked().get(slot.index)) return;
        if (found.unmovable().get(slot.index)) {
            graphics.renderFakeItem(RetroStacks.whiteGlass(), slot.x, slot.y);
            return;
        }
        int right = slot.x + RetroStacks.SLOT_SIZE;
        int bottom = slot.y + RetroStacks.SLOT_SIZE;
        graphics.fill(
                slot.x - 1, bottom, right + 1, bottom + BORDER, VanillaGuiColors.SLOT_HIGHLIGHT);
        if (slot.getContainerSlot() == Collections.max(accessorySlots())) {
            graphics.fill(
                    right,
                    slot.y - 1,
                    right + BORDER,
                    bottom + BORDER,
                    VanillaGuiColors.SLOT_HIGHLIGHT);
        }
    }

    /**
     * The slot the mouse is over, as {@code AbstractContainerScreen.getHoveredSlot} found it,
     * or {@code null} when retro made that slot inert. A failure gives {@code slot} back, so the
     * mouse is never lost.
     *
     * @param screen the screen
     * @param slot the slot vanilla found, or {@code null}
     * @return {@code slot}, or {@code null}
     */
    public static Slot hovered(AbstractContainerScreen<?> screen, Slot slot) {
        if (slot == null || !RetroModeManager.isLiveOnWynncraft()) return slot;
        return MOUSE_GUARD.call(() -> answering(screen, slot), slot);
    }

    private static Slot answering(AbstractContainerScreen<?> screen, Slot slot) {
        Panels found = panels(screen);
        if (found.inert().get(slot.index)) return null;
        if (found.unmovable().get(slot.index) && holdsNothing(slot)) return null;
        return slot;
    }

    /** Whether a slot looks empty: no item, or Wynncraft's filler, unnamed or with no tooltip. */
    private static boolean holdsNothing(Slot slot) {
        if (!slot.hasItem()) return true;
        ItemStack stack = slot.getItem();
        TooltipDisplay tooltip = stack.get(DataComponents.TOOLTIP_DISPLAY);
        return (tooltip != null && tooltip.hideTooltip())
                || blankName(stack.getHoverName().getString());
    }

    /**
     * Whether an item's name is blank, as Wynncraft's filler items' are: nothing but formatting
     * codes, spaces, Wynncraft's own spacing and glyphs.
     *
     * @param name the name, which may hold formatting codes
     * @return whether it has no letter or digit
     */
    // Package-private for unit tests. See RetroSlotsTest.
    static boolean blankName(String name) {
        String stripped = ChatFormatting.stripFormatting(name);
        return stripped == null
                || stripped.codePoints()
                        .noneMatch(c -> c != WYNN_SPACE && Character.isLetterOrDigit(c));
    }

    /** {@code screen}'s panel slots, worked out on first sight and when its label changes. */
    private static Panels panels(AbstractContainerScreen<?> screen) {
        return PANELS.get(screen, memoFor -> new IdentityMemo<>())
                .get(RetroContainerTitles.relabelled(screen), title -> workOut(screen, title));
    }

    /**
     * Which of the screen's slots are glazed, unmovable and inert: none on a screen retro has not
     * relabelled or on a full-screen view.
     */
    private static Panels workOut(
            AbstractContainerScreen<?> screen, RetroContainerTitles.Relabelled title) {
        return WORK_OUT_GUARD.call(() -> panelsOn(screen, title), Panels.NONE);
    }

    private static Panels panelsOn(
            AbstractContainerScreen<?> screen, RetroContainerTitles.Relabelled title) {
        boolean inventory = screen instanceof InventoryScreen;
        if (!inventory && (title == null || title.fullscreen())) return Panels.NONE;
        boolean glass = RetroModeManager.isPartOn(RetroPart.GLASS);
        List<SlotFill> fills = RetroModeManager.manifest().screens().slotFills();
        AbstractContainerMenu menu = screen.getMenu();
        Set<Integer> containerPanels =
                glass && title != null && menu instanceof ChestMenu chest
                        ? containerSlots(
                                fills,
                                title.containerClass(),
                                title.title(),
                                chest.getRowCount() * COLUMNS,
                                title.bounds())
                        : Set.of();
        Set<Integer> playerPanels =
                glass && fills.stream().anyMatch(SlotFill.PlayerSlots.class::isInstance)
                        ? wynntilsPlayerSlots()
                        : Set.of();
        LocalPlayer player = McUtils.player();
        List<SlotKey> slots = new ArrayList<>(menu.slots.size());
        for (Slot slot : menu.slots) {
            boolean own = player != null && slot.container == player.getInventory();
            slots.add(new SlotKey(slot.index, slot.getContainerSlot(), own, slot.x, slot.y));
        }
        return panelsOf(
                slots,
                containerPanels,
                playerPanels,
                accessorySlots(),
                inventory ? LivePack.imageEdits(INVENTORY_TEXTURE) : List.of());
    }

    /**
     * A screen's panel slots, from what its rows pick.
     *
     * @param slots the screen's slots
     * @param containerPanels the container slots the {@code slot_fills} rows pick
     * @param playerPanels the player's slots they pick
     * @param accessories the player's accessory slots, which the player fills; a container slot of
     *     the same number is no accessory
     * @param painted the rectangles {@code image_edits} painted on the screen's texture: none but
     *     on the inventory screen
     * @return its panel slots, by menu index
     */
    // Package-private for unit tests. See RetroSlotsTest.
    static Panels panelsOf(
            List<SlotKey> slots,
            Set<Integer> containerPanels,
            Set<Integer> playerPanels,
            Set<Integer> accessories,
            List<Rect> painted) {
        Panels found = new Panels(new BitSet(), new BitSet(), new BitSet());
        for (SlotKey slot : slots) {
            int index = slot.containerSlot();
            if (slot.own() ? playerPanels.contains(index) : containerPanels.contains(index)) {
                found.picked().set(slot.index());
                if (!slot.own() || !accessories.contains(index)) {
                    found.unmovable().set(slot.index());
                }
            }
            if (paintedOver(painted, slot.x(), slot.y())) found.inert().set(slot.index());
        }
        return found;
    }

    /** The player's slots Wynntils names: the compass, content book, accessories and pouch. */
    private static Set<Integer> wynntilsPlayerSlots() {
        Set<Integer> slots = new TreeSet<>(accessorySlots());
        slots.add(InventoryUtils.COMPASS_SLOT_NUM);
        slots.add(InventoryUtils.CONTENT_BOOK_SLOT_NUM);
        slots.add(InventoryUtils.INGREDIENT_POUCH_SLOT_NUM);
        return slots;
    }

    /** Wynntils' accessory slots, by their index in the player's inventory. */
    private static Set<Integer> accessorySlots() {
        if (accessorySlots == null) {
            Set<Integer> slots = new HashSet<>();
            for (int slot : InventoryAccessory.getSlots()) slots.add(slot);
            accessorySlots = Set.copyOf(slots);
        }
        return accessorySlots;
    }

    /**
     * The container slots the {@code slot_fills} container rows glaze on one container,
     * whatever they hold. A {@code fallback} row counts only when no row before it applied.
     *
     * @param fills the {@code slot_fills} rows; {@code player} rows are skipped
     * @param containerClass the simple name of its Wynntils container class, or {@code null}
     * @param title its title as retro draws it
     * @param size how many container slots it has
     * @param bounds the slots Wynntils says hold its items, or {@code null} for none
     * @return the container slots to paint, each below {@code size}
     */
    // Package-private for unit tests. See RetroSlotsTest.
    static Set<Integer> containerSlots(
            List<SlotFill> fills,
            String containerClass,
            String title,
            int size,
            Collection<Integer> bounds) {
        Set<Integer> found = new TreeSet<>();
        boolean applied = false;
        for (SlotFill fill : fills) {
            if (!(fill instanceof SlotFill.ContainerSlots row)
                    || (row.fallback() && applied)
                    || !applies(row, containerClass, title)) {
                continue;
            }
            applied = true;
            for (int slot = 0; slot < size; slot++) {
                if (covers(row, slot, bounds)) found.add(slot);
            }
        }
        return found;
    }

    /**
     * Whether a container row applies to a container.
     *
     * @param row the row
     * @param containerClass the simple name of its Wynntils container class, or {@code null}
     * @param title its title as retro draws it, or {@code null}
     * @return whether the row's container pattern, or its title pattern, matches, and its
     *     {@code unless} matches neither
     */
    // Package-private for unit tests. See RetroSlotsTest.
    static boolean applies(SlotFill.ContainerSlots row, String containerClass, String title) {
        if (row.unless() != null
                && ((containerClass != null && row.unless().matcher(containerClass).matches())
                        || (title != null && row.unless().matcher(title).matches()))) {
            return false;
        }
        if (row.containers() != null) {
            return containerClass != null && row.containers().matcher(containerClass).matches();
        }
        return title != null && row.titles().matcher(title).matches();
    }

    /**
     * Whether a slot of the inventory lies under what {@code image_edits} painted: its square
     * wholly inside one of the rectangles. The inventory screen draws its texture from the
     * container's corner, so a slot's position is its place on the texture.
     *
     * @param painted the rectangles painted on {@link #INVENTORY_TEXTURE}
     * @param x the slot's column, {@code Slot.x}
     * @param y the slot's row, {@code Slot.y}
     * @return whether a rectangle covers the slot whole; a slot only partly painted still shows
     */
    // Package-private for unit tests. See RetroSlotsTest.
    static boolean paintedOver(Collection<Rect> painted, int x, int y) {
        Rect square = new Rect(x, y, RetroStacks.SLOT_SIZE, RetroStacks.SLOT_SIZE);
        for (Rect rect : painted) {
            if (rect.contains(square)) return true;
        }
        return false;
    }

    /**
     * Whether a container row paints a slot of a chest.
     *
     * @param row the row
     * @param slot the slot, counted across then down from 0
     * @param bounds the slots Wynntils says hold the container's items, or {@code null} when it
     *     gives none, in which case {@code outside_bounds} picks nothing
     * @return whether the row picks every slot, or the slot is in one of its rows, columns or
     *     slots, or outside the bounds when the row asks for that, and not in its {@code except}
     */
    // Package-private for unit tests. See RetroSlotsTest.
    static boolean covers(SlotFill.ContainerSlots row, int slot, Collection<Integer> bounds) {
        return !row.except().contains(slot)
                && (row.all()
                        || row.slots().contains(slot)
                        || row.rows().contains(slot / COLUMNS)
                        || row.cols().contains(slot % COLUMNS)
                        || (row.outsideBounds() && bounds != null && !bounds.contains(slot)));
    }
}
