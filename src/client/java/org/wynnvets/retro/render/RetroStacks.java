package org.wynnvets.retro.render;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.wynnvets.retro.LivePack;

/**
 * The stacks retro draws for itself, and the parsed ids its rows name. Each decorative stack is
 * made on first use, so loading the class builds no Minecraft object. Render thread only.
 */
final class RetroStacks {

    /** The side of a slot's item square, which draws its item and answers the mouse. */
    static final int SLOT_SIZE = 16;

    /** The parsed ids, by the text they parse. */
    private static final Map<String, Identifier> IDS = new ConcurrentHashMap<>();

    private static ItemStack whiteGlass;
    private static ItemStack glintingBook;

    private RetroStacks() {}

    /**
     * An id a row names, parsed once.
     *
     * @param id the id's text
     * @return the id
     */
    static Identifier id(String id) {
        return IDS.computeIfAbsent(id, Identifier::parse);
    }

    /**
     * The white glass drawn in a panel slot: vanilla's pane, which Wynncraft does not redefine.
     *
     * @return the shared stack
     */
    static ItemStack whiteGlass() {
        if (whiteGlass == null) whiteGlass = new ItemStack(Items.WHITE_STAINED_GLASS_PANE);
        return whiteGlass;
    }

    /**
     * Vanilla's enchanted book, which Wynncraft does not redefine, with its glint.
     *
     * @return the shared stack
     */
    static ItemStack glintingBook() {
        if (glintingBook == null) {
            glintingBook = new ItemStack(Items.ENCHANTED_BOOK);
            glintingBook.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return glintingBook;
    }

    /**
     * A stack drawn as another item model.
     *
     * @param item the stack's item
     * @param modelId the item model it draws
     * @return a new stack
     */
    static ItemStack withModel(Item item, String modelId) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.ITEM_MODEL, id(modelId));
        return stack;
    }

    /**
     * A vanilla item's icon, drawn from the pack's copy of its item definition when there is one,
     * because Wynncraft's own definitions of several items draw its weapons.
     *
     * @param itemId the item's id
     * @param liveCopy the live pack's copy of an item's definition, by item id, as
     *     {@link LivePack#itemDefinition} answers
     * @return a new stack; empty for an id this game has no item for
     */
    static ItemStack vanillaIcon(String itemId, Function<String, Optional<String>> liveCopy) {
        Identifier id = Identifier.tryParse(itemId);
        Optional<Item> item =
                id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item.get());
        liveCopy.apply(itemId)
                .map(Identifier::tryParse)
                .ifPresent(model -> stack.set(DataComponents.ITEM_MODEL, model));
        return stack;
    }

    /**
     * An icon the pack generated, which draws on any item, since its model is named outright.
     *
     * @param itemId the generated item definition's id
     * @return a new stack; empty when the live generation did not write it
     */
    static ItemStack generatedIcon(String itemId) {
        return LivePack.isGeneratedItem(itemId) ? withModel(Items.PAPER, itemId) : ItemStack.EMPTY;
    }
}
