package org.wynnvets.debug.dump;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ConditionalItemModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.RangeSelectItemModel;
import net.minecraft.client.renderer.item.SelectItemModel;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.PlaceholderLookupProvider;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.retro.pack.PackFiles;

/**
 * Which models an item stack draws: the model ids its item definition, {@code items/<id>.json}
 * for the stack's {@code item_model}, picks for it in an inventory. Minecraft keeps only the
 * baked definitions, which no longer say what model ids they were built from, so this reads the
 * definition from the loaded resources again, parses it as Minecraft's loader does, and walks it
 * with the stack. The item dump and the retro report use it to name the Wynncraft model behind a
 * stack; nothing that draws depends on the walk.
 *
 * <p>The walk does what Minecraft's item models do in the GUI, with seed 0: a composite draws
 * every part, a condition, select or range dispatch draws the branch the stack picks (its
 * fallback when it picks none), a model draws its model id and a special model its base model;
 * an empty model draws nothing. It belongs on the render thread, as Minecraft's own: a property
 * may read the game's input there ({@code minecraft:extended_view} asks GLFW whether shift is
 * down, which Wynncraft's {@code items/potion.json} uses). Parsing may run anywhere, and costs as
 * much as the file is long, so both callers parse off the render thread; Wynncraft's are long:
 * {@code items/potion.json}, behind its weapons and ingredients, is about 1.5 MB, and
 * {@code items/oak_boat.json} about 8 MB.</p>
 */
public final class LiveItemModels {

    /** The display context the walk picks branches for: an inventory slot. */
    private static final ItemDisplayContext CONTEXT = ItemDisplayContext.GUI;

    /** The seed the walk gives the properties, as Minecraft gives an item in the GUI. */
    private static final int SEED = 0;

    private LiveItemModels() {}

    /**
     * The item definition of one item model, parsed.
     *
     * @param resources the resources to read it from: the loaded ones
     * @param itemModel the item model, such as {@code minecraft:potion}
     * @return its model tree; empty when no pack has its definition, or it cannot be parsed (the
     *     failure logged at debug)
     */
    public static Optional<ItemModel.Unbaked> definition(
            ResourceManager resources, Identifier itemModel) {
        Optional<Resource> resource = resources.getResource(PackFiles.ITEMS.idToFile(itemModel));
        if (resource.isEmpty()) return Optional.empty();
        try (Reader reader = resource.get().openAsReader()) {
            // As ClientItemInfoLoader parses one, so any definition Minecraft loads parses here.
            DynamicOps<JsonElement> ops =
                    new PlaceholderLookupProvider(
                                    ClientRegistryLayer.createRegistryAccess().compositeAccess())
                            .createSerializationContext(JsonOps.INSTANCE);
            return ClientItem.CODEC
                    .parse(ops, StrictJsonParser.parse(reader))
                    .result()
                    .map(ClientItem::model);
        } catch (IOException | RuntimeException e) {
            VetsLogger.debug("Could not parse the item definition of {}", itemModel, e);
            return Optional.empty();
        }
    }

    /**
     * The models a parsed definition draws for {@code stack}. Render thread.
     *
     * @param definition the definition's model tree
     * @param stack the stack
     * @param level the level its properties may read, or {@code null}
     * @param entity the entity holding it, or {@code null}
     * @return the model ids, such as {@code minecraft:item/wynn/ingredient/gold_nugget}, in the
     *     order drawn
     */
    public static List<String> models(
            ItemModel.Unbaked definition, ItemStack stack, ClientLevel level, LivingEntity entity) {
        List<String> models = new ArrayList<>();
        walk(definition, stack, level, entity, models);
        return models;
    }

    /** Appends the models this node of the definition draws for the stack. */
    private static void walk(
            ItemModel.Unbaked model,
            ItemStack stack,
            ClientLevel level,
            LivingEntity entity,
            List<String> into) {
        Optional<ItemModel.Unbaked> branch =
                switch (model) {
                    case BlockModelWrapper.Unbaked leaf -> {
                        into.add(leaf.model().toString());
                        yield Optional.empty();
                    }
                    case SpecialModelWrapper.Unbaked special -> {
                        into.add(special.base().toString());
                        yield Optional.empty();
                    }
                    case CompositeModel.Unbaked composite -> {
                        composite.models().forEach(part -> walk(part, stack, level, entity, into));
                        yield Optional.empty();
                    }
                    case ConditionalItemModel.Unbaked condition ->
                            Optional.of(
                                    condition.property().get(stack, level, entity, SEED, CONTEXT)
                                            ? condition.onTrue()
                                            : condition.onFalse());
                    case SelectItemModel.Unbaked select ->
                            selected(select.unbakedSwitch(), stack, level, entity)
                                    .or(select::fallback);
                    case RangeSelectItemModel.Unbaked range -> ranged(range, stack, level, entity);
                    default -> Optional.empty();
                };
        branch.ifPresent(next -> walk(next, stack, level, entity, into));
    }

    /** The case of {@code select} whose values hold the stack's value, if any. */
    private static <P extends SelectItemModelProperty<T>, T> Optional<ItemModel.Unbaked> selected(
            SelectItemModel.UnbakedSwitch<P, T> select,
            ItemStack stack,
            ClientLevel level,
            LivingEntity entity) {
        T value = select.property().get(stack, level, entity, SEED, CONTEXT);
        if (value == null) return Optional.empty();
        for (SelectItemModel.SwitchCase<T> option : select.cases()) {
            if (option.values().contains(value)) return Optional.of(option.model());
        }
        return Optional.empty();
    }

    /**
     * The entry with the highest threshold at or below the stack's scaled value, the later of
     * equal thresholds, as Minecraft picks it; the fallback when none is, or the value is not a
     * number.
     */
    private static Optional<ItemModel.Unbaked> ranged(
            RangeSelectItemModel.Unbaked range,
            ItemStack stack,
            ClientLevel level,
            LivingEntity entity) {
        float value = range.property().get(stack, level, entity, SEED) * range.scale();
        List<RangeSelectItemModel.Entry> entries = range.entries();
        float[] thresholds = new float[entries.size()];
        for (int i = 0; i < thresholds.length; i++) thresholds[i] = entries.get(i).threshold();
        int picked = pickedEntry(thresholds, value);
        return picked >= 0 ? Optional.of(entries.get(picked).model()) : range.fallback();
    }

    /**
     * Which entry of a range dispatch a value picks.
     *
     * @param thresholds the entries' thresholds, in file order
     * @param value the stack's scaled value
     * @return the index of the highest threshold at or below {@code value}, the later of equal
     *     thresholds; {@code -1} when none is, or {@code value} is not a number
     */
    // Package-private for unit tests. See LiveItemModelsTest.
    static int pickedEntry(float[] thresholds, float value) {
        int picked = -1;
        if (!Float.isNaN(value)) {
            for (int i = 0; i < thresholds.length; i++) {
                if (thresholds[i] <= value && (picked < 0 || thresholds[i] >= thresholds[picked])) {
                    picked = i;
                }
            }
        }
        return picked;
    }
}
