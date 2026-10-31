package org.wynnvets.retro.render;

import com.wynntils.core.components.Models;
import com.wynntils.models.items.items.game.GearBoxItem;
import com.wynntils.models.items.items.game.TomeItem;
import com.wynntils.models.items.items.gui.AbilityTreeItem;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
import org.wynnvets.retro.LivePack;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.RetroReport;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.StackSections.OutlinedIcon;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * What retro draws over a slot's item while its pack is live: the gear type over an unidentified
 * gear box, the tome type over a mastery tome ({@code gear_box_icons} and {@code outlined_icons} in
 * {@code retro_textures.yml}), and the glint on the compass menu's Ability Tree button.
 *
 * <p>Every gear box is one chest in the retro pack, whatever its type, since Wynncraft gives every
 * box one model per tier ({@code item_models}). So the GUI draws the type's vanilla item over the
 * box, at {@link #ICON_SCALE} of the slot and centred on it, after the slot's item, as Wynntils'
 * own {@code UnidentifiedItemIconFeature} draws its icon (which {@code wynntils_config} turns off
 * while retro is on). Every mastery tome is one enchanted book the same way, drawn again here for
 * its glint, which a model's stub cannot give, so that the glint lies on the book and under the
 * icon. A weapon's and a tome's icon come with an outline of their own ({@code outlined_icons}),
 * the size their rows give, so they read over the art they are drawn on. The Ability Tree's button,
 * the same book, is drawn again for its glint alone, on a screen retro relabelled: the tree's own
 * screen keeps Wynncraft's art, and Wynntils' item class there is its Ability Points button. The
 * boxes and tomes are the {@code gear_boxes} part's; the button's glint needs only a relabelled
 * screen, which {@code screens} being on decides.
 * {@link org.wynnvets.listeners.RetroSlotIconListener RetroSlotIconListener} calls it for every
 * container and inventory slot and every hotbar slot.</p>
 *
 * <p>The item is drawn from vanilla's item definition of it, which the pack copies under
 * vetsmod's namespace ({@link RetroStacks#vanillaIcon}). The icon stacks are made once per
 * generation. A failure is logged once and stops the icons until the game restarts. Render thread
 * only. The placement maths ({@link #iconOffset}) and the row lookup ({@link #itemFor}) are pure
 * and unit-tested; loading the class builds no Minecraft or Wynntils object.</p>
 */
public final class RetroSlotIcons {

    /** The icon's size, as a share of the slot's 16 pixels: three quarters. */
    // Package-private for unit tests. See RetroSlotIconsTest.
    static final float ICON_SCALE = 0.75f;

    private static final RetroGuard GUARD =
            RetroGuard.stopping(
                    "the slot icons", "gear boxes and tomes show no type until the game restarts");

    /** The icon stacks made so far, by gear or tome type, for one generation's report. */
    private static final IdentityMemo<RetroReport, Map<String, ItemStack>> STACKS =
            new IdentityMemo<>();

    private RetroSlotIcons() {}

    /** What to draw over a slot's item. */
    // Package-private for unit tests. See RetroSlotIconsTest.
    sealed interface SlotOverlay {
        SlotOverlay NONE = new None();
        SlotOverlay GLINT = new Glint();

        /** Nothing. */
        record None() implements SlotOverlay {}

        /** The glinting book alone. */
        record Glint() implements SlotOverlay {}

        /**
         * A type's icon.
         *
         * @param type the name of Wynntils' gear or tome type
         * @param scale the size to draw its stack at, as a share of the slot's
         * @param glint whether the glinting book lies under it
         */
        record Icon(String type, float scale, boolean glint) implements SlotOverlay {}
    }

    /**
     * Draws retro's overlay over a slot's item, if its stack has one.
     *
     * @param graphics the graphics, translated to the screen's container as a slot is drawn
     * @param screen the screen drawing the slot
     * @param slot the slot
     */
    public static void drawOverSlot(GuiGraphics graphics, Screen screen, Slot slot) {
        if (GUARD.stopped() || !RetroModeManager.isPackLive()) return;
        GUARD.run(
                () -> {
                    boolean relabelled = RetroContainerTitles.relabelled(screen) != null;
                    draw(graphics, overlayFor(slot.getItem(), relabelled), slot.x, slot.y);
                });
    }

    /**
     * Draws retro's overlay over a hotbar slot's item, if its stack has one.
     *
     * @param graphics the graphics
     * @param stack the slot's stack
     * @param x the left of the slot's item square
     * @param y the top of the slot's item square
     */
    public static void drawOverHotbarSlot(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (GUARD.stopped() || !RetroModeManager.isPackLive()) return;
        GUARD.run(() -> draw(graphics, overlayFor(stack, false), x, y));
    }

    private static SlotOverlay overlayFor(ItemStack stack, boolean relabelled) {
        if (stack == null || stack.isEmpty()) return SlotOverlay.NONE;
        if (Models.Item.asWynnItem(stack, AbilityTreeItem.class).isPresent()) {
            return abilityTreeOverlay(relabelled);
        }
        if (!RetroModeManager.isPartOn(RetroPart.GEAR_BOXES)) return SlotOverlay.NONE;
        Optional<TomeItem> tome = Models.Item.asWynnItem(stack, TomeItem.class);
        if (tome.isPresent()) return icon(tome.get().getItemInfo().type().name(), true);
        Optional<GearBoxItem> box = Models.Item.asWynnItem(stack, GearBoxItem.class);
        return box.isPresent() ? icon(box.get().getGearType().name(), false) : SlotOverlay.NONE;
    }

    /**
     * What Wynntils' {@code AbilityTreeItem} shows: its class covers the compass menu's Ability
     * Tree button, on a screen retro relabelled, and the tree screen's Ability Points button, on a
     * screen that keeps Wynncraft's art.
     *
     * @param relabelled whether retro drew the screen's label
     * @return the glint on a relabelled screen; nothing elsewhere
     */
    // Package-private for unit tests. See RetroSlotIconsTest.
    static SlotOverlay abilityTreeOverlay(boolean relabelled) {
        return relabelled ? SlotOverlay.GLINT : SlotOverlay.NONE;
    }

    private static SlotOverlay icon(String type, boolean glint) {
        return new SlotOverlay.Icon(
                type, iconScale(RetroModeManager.manifest().stacks().outlinedIcons(), type), glint);
    }

    /** Draws the overlay: nothing, the glinting book, or the icon, over the book if it glints. */
    private static void draw(GuiGraphics graphics, SlotOverlay overlay, int x, int y) {
        switch (overlay) {
            case SlotOverlay.None none -> {}
            case SlotOverlay.Glint glint ->
                    graphics.renderFakeItem(RetroStacks.glintingBook(), x, y);
            case SlotOverlay.Icon icon -> {
                if (icon.glint()) graphics.renderFakeItem(RetroStacks.glintingBook(), x, y);
                drawIcon(graphics, icon, x, y);
            }
        }
    }

    private static void drawIcon(GuiGraphics graphics, SlotOverlay.Icon icon, int x, int y) {
        ItemStack stack = stackFor(icon.type());
        if (stack.isEmpty()) return;
        float scale = icon.scale();
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(x + iconOffset(scale), y + iconOffset(scale));
            pose.scale(scale, scale);
            graphics.renderFakeItem(stack, 0, 0);
        } finally {
            pose.popMatrix();
        }
    }

    /**
     * The icon stack for one type, made once per generation.
     *
     * @return the stack; empty for a type with no row, or a row whose item this game lacks
     */
    private static ItemStack stackFor(String type) {
        return STACKS.get(RetroModeManager.lastReport(), report -> new HashMap<>())
                .computeIfAbsent(type, RetroSlotIcons::makeStack);
    }

    private static ItemStack makeStack(String type) {
        if (RetroModeManager.manifest().stacks().outlinedIcons().containsKey(type)) {
            return RetroStacks.generatedIcon(RetroAssetIds.outlinedIconItem(type));
        }
        String itemId = itemFor(type);
        return itemId == null
                ? ItemStack.EMPTY
                : RetroStacks.vanillaIcon(itemId, LivePack::itemDefinition);
    }

    /**
     * The item {@code gear_box_icons} draws over a gear box of one type.
     *
     * @param gearType the name of Wynntils' gear type, such as {@code SPEAR}
     * @return the item's id, such as {@code minecraft:iron_shovel}; {@code null} for a type with
     *     no row
     */
    // Package-private for unit tests. See RetroSlotIconsTest.
    static String itemFor(String gearType) {
        return RetroModeManager.manifest().stacks().gearBoxIcons().get(gearType);
    }

    /**
     * The scale a type's icon stack is drawn at.
     *
     * @param outlined the {@code outlined_icons} rows
     * @param type the name of Wynntils' gear or tome type
     * @return its row's {@link #drawnScale}; {@link #ICON_SCALE} for a type with no such row, which
     *     is the {@code gear_box_icons} items, the Allegiance tome's shield among them
     */
    // Package-private for unit tests. See RetroSlotIconsTest.
    static float iconScale(Map<String, OutlinedIcon> outlined, String type) {
        OutlinedIcon row = outlined.get(type);
        return row == null ? ICON_SCALE : drawnScale(row);
    }

    /**
     * The scale to draw an outlined icon at, whose texture the outline has grown, so that its art
     * stays the share of the slot its row gives.
     *
     * @param icon the icon's row
     * @return the scale of the whole texture
     */
    // Package-private for unit tests. See RetroSlotIconsTest.
    static float drawnScale(OutlinedIcon icon) {
        return (float)
                (icon.scale()
                        * (RetroStacks.SLOT_SIZE + 2 * icon.outlinePixels())
                        / RetroStacks.SLOT_SIZE);
    }

    /**
     * How far an icon sits in from the slot's top left, on both axes, so that it is centred.
     *
     * @param scale the icon's size, as a share of the slot's
     * @return {@code 16 * (1 - scale) / 2} pixels
     */
    // Package-private for unit tests. See RetroSlotIconsTest.
    static float iconOffset(float scale) {
        return RetroStacks.SLOT_SIZE * (1 - scale) / 2;
    }
}
