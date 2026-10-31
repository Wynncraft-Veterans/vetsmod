package org.wynnvets.retro.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.RetroPart;
import org.wynnvets.retro.manifest.StackSections.StackModel;

/**
 * The single stacks drawn as another item while the retro pack is live on Wynncraft
 * ({@code stack_models} in {@code retro_textures.yml}), for the looks a model's stub cannot give: a
 * vanilla potion Wynncraft leaves alone, or an item sharing its model with others; and the
 * accessories, by level ({@link RetroAccessories}).
 *
 * <p>{@link org.wynnvets.mixin.client.retro.RetroItemModelResolverMixin
 * RetroItemModelResolverMixin} hands every stack's item model here as the stack is resolved for
 * drawing. {@link RetroAccessories} answers first, while its {@code accessories} part is on; then
 * the rows are looked up by item model, and only a stack of a row's item has its name read, for the
 * rows whose part is on. The stack itself is not changed. A failure is logged once and stops the
 * rows until the game restarts. The row lookup ({@link #modelFor}) is pure and unit-tested.</p>
 */
public final class RetroStackModels {

    private static final RetroGuard GUARD =
            RetroGuard.stopping(
                    "picking a stack's model",
                    "stacks draw as Wynncraft's until the game restarts");

    /** The rows by item model, for one manifest's rows. */
    private static final IdentityMemo<List<StackModel>, Map<Identifier, List<StackModel>>> BY_ITEM =
            new IdentityMemo<>();

    private RetroStackModels() {}

    /**
     * The item model to draw a stack with.
     *
     * @param stack the stack
     * @param model the stack's item model, or {@code null}
     * @return the model its {@code stack_models} row names, or {@code model} when none applies
     */
    public static Identifier model(ItemStack stack, Identifier model) {
        if (GUARD.stopped() || model == null || !RetroModeManager.isLiveOnWynncraft()) return model;
        return GUARD.call(() -> replaced(stack, model), model);
    }

    private static Identifier replaced(ItemStack stack, Identifier model) {
        if (RetroModeManager.isPartOn(RetroPart.ACCESSORIES)) {
            Identifier accessory = RetroAccessories.model(stack);
            if (accessory != null) return accessory;
        }
        List<StackModel> rows =
                BY_ITEM.get(
                                RetroModeManager.manifest().stacks().stackModels(),
                                RetroStackModels::byItem)
                        .get(model);
        if (rows == null) return model;
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        String replaced = modelFor(rows, name, RetroModeManager.partsOn());
        return replaced == null ? model : RetroStacks.id(replaced);
    }

    private static Map<Identifier, List<StackModel>> byItem(List<StackModel> rows) {
        Map<Identifier, List<StackModel>> grouped = new HashMap<>();
        for (StackModel row : rows) {
            grouped.computeIfAbsent(RetroStacks.id(row.item()), item -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * The model of the first row of a part that is on whose name pattern matches a stack's whole
     * name.
     *
     * @param rows the rows for the stack's item
     * @param name the stack's name, without formatting codes
     * @param partsOn the parts that are on
     * @return the row's model; {@code null} when none matches
     */
    // Package-private for unit tests. See RetroStackModelsTest.
    static String modelFor(List<StackModel> rows, String name, Set<RetroPart> partsOn) {
        for (StackModel row : rows) {
            if (partsOn.contains(row.part()) && row.name().matcher(name).matches()) {
                return row.model();
            }
        }
        return null;
    }
}
