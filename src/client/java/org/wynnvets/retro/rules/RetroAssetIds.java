package org.wynnvets.retro.rules;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.wynnvets.retro.manifest.ParentKind;

/**
 * The one place that spells the ids the retro pack generates and the fixed ids they sit on. A
 * generated item has one path under vetsmod's namespace: its item definition is
 * {@link #generatedItem} of the path, and its model and texture are {@link #generatedAsset} of it.
 * The class is pure, so the parser, the rules, the generator and the code that draws can all share
 * it, and a test can pin every id without Minecraft.
 */
public final class RetroAssetIds {

    /** vetsmod's namespace: the pack writes everything of its own there, so nothing is hidden. */
    public static final String NAMESPACE = "vetsmod";

    private static final String PREFIX = NAMESPACE + ":";

    /** Avomod's namespace: its potion models are stubbed where a {@code potions} row names them. */
    public static final String AVOMOD_NAMESPACE = "avomod2";

    /** The vanilla bottle drawn over the liquid, which {@link #TINTED_POTION} names itself. */
    public static final String POTION_BOTTLE = "minecraft:item/potion";

    /**
     * vetsmod's parent for a potion stub: a blank tint layer, the liquid ({@code #liquid}), then
     * {@link #POTION_BOTTLE}, so no tint reaches either.
     */
    public static final String TINTED_POTION = PREFIX + "item/retro/tinted_potion";

    /**
     * The tooltip style of the retro pack's copy of Wynncraft's unique border, which
     * {@code wynncraft_copies} writes before the tooltip alias turns the unique style vanilla.
     */
    public static final String LEGACY_TOOLTIP_STYLE = PREFIX + "legacy";

    /** The sprites of {@link #LEGACY_TOOLTIP_STYLE}, as {@code wynncraft_copies} names them. */
    public static final Set<String> LEGACY_TOOLTIP_SPRITES =
            Set.of(
                    PREFIX + "gui/sprites/tooltip/legacy_background",
                    PREFIX + "gui/sprites/tooltip/legacy_frame");

    /** The item the code draws for a glint: over a mastery tome and the Ability Tree's button. */
    public static final String GLINTING_BOOK = "minecraft:enchanted_book";

    /** The renderer vanilla's item definition of a shield names, as its {@code type}. */
    public static final String SHIELD_RENDERER = "minecraft:shield";

    /** What {@link #SHIELD_RENDERER} draws, which the shield's item model does not name. */
    public static final String SHIELD_ART = "minecraft:entity/shield_base_nopattern";

    /** The name {@link #accessoryPath} takes for the outline an empty slot of that type shows. */
    public static final String ACCESSORY_EMPTY = "empty";

    /** The overhealth hearts' colours, in the order they fill a row of hearts. */
    public static final List<String> OVERHEALTH_COLOURS = List.of("orange", "yellow", "green");

    /** The name an overhealth heart takes when it fills both of an icon's halves. */
    public static final String HEART_FULL = "full";

    /** The name an overhealth heart takes when it fills an icon's left half. */
    public static final String HEART_HALF = "half";

    private RetroAssetIds() {}

    /**
     * The GUI sprite of one overhealth heart.
     *
     * @param colour one of {@link #OVERHEALTH_COLOURS}
     * @param fill {@link #HEART_FULL} or {@link #HEART_HALF}
     * @return such as {@code vetsmod:retro/heart/orange_full}
     */
    public static String overhealthSprite(String colour, String fill) {
        return PREFIX + "retro/heart/" + colour + "_" + fill;
    }

    /**
     * The {@code source_copies} texture of one overhealth heart, {@link #overhealthSprite}'s file
     * in the GUI atlas.
     *
     * @param colour one of {@link #OVERHEALTH_COLOURS}
     * @param fill {@link #HEART_FULL} or {@link #HEART_HALF}
     * @return such as {@code vetsmod:gui/sprites/retro/heart/orange_full}
     */
    public static String overhealthTexture(String colour, String fill) {
        return PREFIX + "gui/sprites/retro/heart/" + colour + "_" + fill;
    }

    /**
     * A generated item's id, its item definition's.
     *
     * @param path its path, such as {@code retro/accessory/ring_gold}
     * @return such as {@code vetsmod:retro/accessory/ring_gold}
     */
    public static String generatedItem(String path) {
        return PREFIX + path;
    }

    /**
     * The id of a generated item's model, and of its texture.
     *
     * @param path its path, such as {@code retro/accessory/ring_gold}
     * @return such as {@code vetsmod:item/retro/accessory/ring_gold}
     */
    public static String generatedAsset(String path) {
        return PREFIX + "item/" + path;
    }

    /**
     * The path of one type's outlined icon.
     *
     * @param type the name of Wynntils' gear or tome type, such as {@code WEAPON_TOME}
     * @return such as {@code retro/icon/weapon_tome}
     */
    public static String outlinedIconPath(String type) {
        return "retro/icon/" + type.toLowerCase(Locale.ROOT);
    }

    /**
     * The item of one type's outlined icon.
     *
     * @param type the name of Wynntils' gear or tome type, such as {@code WEAPON_TOME}
     * @return such as {@code vetsmod:retro/icon/weapon_tome}
     */
    public static String outlinedIconItem(String type) {
        return generatedItem(outlinedIconPath(type));
    }

    /**
     * The path of one accessory type's tier.
     *
     * @param gearType the name of Wynntils' gear type, such as {@code RING}
     * @param tier the tier's name, such as {@code gold}, or {@link #ACCESSORY_EMPTY}
     * @return such as {@code retro/accessory/ring_gold}
     */
    public static String accessoryPath(String gearType, String tier) {
        return "retro/accessory/" + gearType.toLowerCase(Locale.ROOT) + "_" + tier;
    }

    /**
     * The item of one accessory type's tier.
     *
     * @param gearType the name of Wynntils' gear type, such as {@code RING}
     * @param tier the tier's name, such as {@code gold}, or {@link #ACCESSORY_EMPTY}
     * @return such as {@code vetsmod:retro/accessory/ring_gold}
     */
    public static String accessoryItem(String gearType, String tier) {
        return generatedItem(accessoryPath(gearType, tier));
    }

    /**
     * The id under which the retro pack copies vanilla's item definition of an item that
     * {@code gear_box_icons} names: an item model id in vetsmod's namespace, so the copy replaces
     * no pack's own definition of the item (Wynncraft's {@code items/bow.json}, say, draws its
     * bows).
     *
     * @param itemId a full item id such as {@code minecraft:iron_shovel}, which may be
     *     {@code null}
     * @return the item model id, such as {@code vetsmod:retro/minecraft/iron_shovel}, whose file
     *     is {@code vetsmod:items/retro/minecraft/iron_shovel.json}; {@code null} for an id with
     *     no namespace or no path, or for {@code null}
     */
    public static String itemDefinitionCopy(String itemId) {
        if (itemId == null) return null;
        int colon = itemId.indexOf(':');
        if (colon <= 0 || colon == itemId.length() - 1) return null;
        return PREFIX + "retro/" + itemId.substring(0, colon) + "/" + itemId.substring(colon + 1);
    }

    /**
     * The model a tint-proof stub parents. Its tint layer is {@link #blank} of the same atlas, and
     * the art goes on a layer the stack's colour does not tint.
     *
     * @param parent how the stub is held
     * @param atlas the atlas its art lives in
     * @return such as {@code vetsmod:item/retro/tint_proof_handheld_item}
     */
    public static String tintProofParent(ParentKind parent, Atlas atlas) {
        return PREFIX + "item/retro/tint_proof_" + parent.yamlName() + "_" + atlas.folder();
    }

    /**
     * The fully transparent texture of one atlas, which a stub's parent puts on the tint layer.
     *
     * @param atlas the atlas
     * @return such as {@code vetsmod:item/retro/blank}
     */
    public static String blank(Atlas atlas) {
        return PREFIX + atlas.folder() + "/retro/blank";
    }

    /**
     * The {@code source_copies} texture of one potion family's liquid.
     *
     * @param family the family, such as {@code mana}
     * @return such as {@code vetsmod:item/retro/potion/mana}
     */
    public static String potionLiquid(String family) {
        return PREFIX + "item/retro/potion/" + family;
    }

    /**
     * The {@code source_copies} texture of one name in a {@code materials} family.
     *
     * @param family the family, such as {@code paper}
     * @param name the name, such as {@code oak}
     * @return such as {@code vetsmod:item/retro/material/paper_oak}
     */
    public static String materialCopy(String family, String name) {
        return PREFIX + "item/retro/material/" + family + "_" + name;
    }

    /**
     * The {@code source_copies} texture of one state of a content book activity.
     *
     * @param activity the activity, such as {@code raid}
     * @param state the state, such as {@code locked}
     * @return such as {@code vetsmod:item/retro/book/raid_locked}
     */
    public static String bookCopy(String activity, String state) {
        return PREFIX + "item/retro/book/" + activity + "_" + state;
    }
}
