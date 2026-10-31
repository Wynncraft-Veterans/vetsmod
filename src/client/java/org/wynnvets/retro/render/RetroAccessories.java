package org.wynnvets.retro.render;

import com.wynntils.core.components.Models;
import com.wynntils.models.items.properties.GearTypeItemProperty;
import com.wynntils.models.items.properties.LeveledItemProperty;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.retro.LivePack;
import org.wynnvets.retro.RetroModeManager;
import org.wynnvets.retro.manifest.StackSections.AccessoryTier;
import org.wynnvets.retro.manifest.StackSections.AccessoryTiers;
import org.wynnvets.retro.rules.RetroAssetIds;

/**
 * The item a ring, bracelet or necklace is drawn as while the retro pack is live: its type's art in
 * the tier of its level ({@code accessory_tiers} in {@code retro_textures.yml}).
 *
 * <p>{@link RetroStackModels} asks for every stack it draws. Wynntils names a stack's gear type and
 * level when it has annotated it, so the first lookup answers for most stacks, and a stack it has
 * not annotated stays Wynncraft's. Nothing static here touches Wynntils; {@link #itemFor} is pure
 * and unit-tested. Render thread only.</p>
 */
final class RetroAccessories {

    private RetroAccessories() {}

    /**
     * The item model to draw a stack with.
     *
     * @param stack the stack
     * @return its tier's item model; {@code null} when it is no accessory, Wynntils does not know
     *     its level, or the live pack did not write that tier
     */
    static Identifier model(ItemStack stack) {
        Optional<GearTypeItemProperty> gear =
                Models.Item.asWynnItemProperty(stack, GearTypeItemProperty.class);
        if (gear.isEmpty()) return null;
        AccessoryTiers rows = RetroModeManager.manifest().stacks().accessoryTiers();
        String gearType = gear.get().getGearType().name();
        if (!rows.art().containsKey(gearType)) return null;
        Optional<LeveledItemProperty> level =
                Models.Item.asWynnItemProperty(stack, LeveledItemProperty.class);
        if (level.isEmpty()) return null;
        String item = itemFor(rows, gearType, level.get().getLevel());
        if (item == null || !LivePack.isGeneratedItem(item)) return null;
        return RetroStacks.id(item);
    }

    /**
     * The item of one accessory type at one level.
     *
     * @param rows the {@code accessory_tiers} section
     * @param gearType the name of Wynntils' gear type, such as {@code RING}
     * @param level the piece's level
     * @return its item's id, such as {@code vetsmod:retro/accessory/ring_gold}; {@code null} for a
     *     type with no art
     */
    // Package-private for unit tests. See RetroAccessoriesTest.
    static String itemFor(AccessoryTiers rows, String gearType, int level) {
        if (!rows.art().containsKey(gearType)) return null;
        for (AccessoryTier tier : rows.tiers()) {
            if (level < tier.below()) {
                return RetroAssetIds.accessoryItem(gearType, tier.name());
            }
        }
        return null;
    }
}
