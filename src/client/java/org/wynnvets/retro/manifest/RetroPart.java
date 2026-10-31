package org.wynnvets.retro.manifest;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A part of retro the player can turn off on its own, by its name in {@code retro_textures.yml}'s
 * {@code parts} section, which says what each covers. Every part is on by default. The stored form
 * of the parts turned off is {@link #formatOff} and {@link #parseOff}; a name this client does not
 * know is ignored as it is read, and a part this client does not find named is on.
 */
public enum RetroPart {
    /** Blocks in the world. */
    BLOCKS("blocks"),
    /** Mobs and other entities. */
    MOBS("mobs"),
    /** Weapons and their skins. */
    WEAPONS("weapons"),
    /** Armour, as an icon and worn. */
    ARMOUR("armour"),
    /** Rings, bracelets and necklaces. */
    ACCESSORIES("accessories"),
    /** Profession tools. */
    TOOLS("tools"),
    /** Ingredients, materials, meals and papers. */
    CRAFTING("crafting"),
    /** Potions. */
    POTIONS("potions"),
    /** Unidentified gear boxes and mastery tomes. */
    GEAR_BOXES("gear_boxes"),
    /** Tooltip frames. */
    TOOLTIPS("tooltips"),
    /** Container art, titles and what goes with them. */
    SCREENS("screens"),
    /** The glass on slots that are not the player's to fill. */
    GLASS("glass"),
    /** How an item's rarity is marked in its slot. */
    HIGHLIGHTS("highlights"),
    /** Vanilla's hearts, food and experience bar. */
    HUD("hud"),
    /** Effect icons and the effects the inventory's list leaves out. */
    EFFECTS("effects"),
    /** Vanilla's player list. */
    PLAYER_LIST("player_list"),
    /** Every other item. */
    ITEMS("items");

    private static final Set<RetroPart> ALL =
            Collections.unmodifiableSet(EnumSet.allOf(RetroPart.class));

    private final String yamlName;

    RetroPart(String yamlName) {
        this.yamlName = yamlName;
    }

    /**
     * The part's name in {@code retro_textures.yml} and in the stored setting.
     *
     * @return the name, lowercase
     */
    public String yamlName() {
        return yamlName;
    }

    /**
     * Looks a part up by its name. The match is exact.
     *
     * @param yamlName the name, which may be {@code null}
     * @return the part, or {@code null} for {@code null} or a name this client does not know
     */
    public static RetroPart fromYaml(String yamlName) {
        for (RetroPart part : values()) {
            if (part.yamlName.equals(yamlName)) {
                return part;
            }
        }
        return null;
    }

    /**
     * Looks a part up by what a player or the stored setting spells, whatever its case or spacing.
     *
     * @param name the name
     * @return the part, or {@code null} for a name this client does not know
     */
    public static RetroPart fromName(String name) {
        return fromYaml(name.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Every part, the state of a setting nobody has touched.
     *
     * @return an unmodifiable set, in declaration order
     */
    public static Set<RetroPart> all() {
        return ALL;
    }

    /**
     * The parts left when some are taken away.
     *
     * @param taken the parts to leave out
     * @return an unmodifiable set, in declaration order
     */
    public static Set<RetroPart> allBut(Set<RetroPart> taken) {
        EnumSet<RetroPart> rest = EnumSet.allOf(RetroPart.class);
        rest.removeAll(taken);
        return Collections.unmodifiableSet(rest);
    }

    /**
     * Reads the stored setting.
     *
     * @param stored the names of the parts turned off, separated by commas, which may be
     *     {@code null}; a name this client does not know is ignored, whatever its case or spacing
     * @return the parts turned off, unmodifiable, in declaration order
     */
    public static Set<RetroPart> parseOff(String stored) {
        EnumSet<RetroPart> off = EnumSet.noneOf(RetroPart.class);
        if (stored != null) {
            for (String name : stored.split(",")) {
                RetroPart part = fromName(name);
                if (part != null) off.add(part);
            }
        }
        return Collections.unmodifiableSet(off);
    }

    /**
     * Writes the stored setting.
     *
     * @param off the parts turned off
     * @return their names in declaration order, separated by commas; empty for none
     */
    public static String formatOff(Set<RetroPart> off) {
        return names(off, ",");
    }

    /**
     * Joins parts' names.
     *
     * @param parts the parts
     * @param separator what goes between two names
     * @return their names in declaration order; empty for none
     */
    public static String names(Collection<RetroPart> parts, String separator) {
        return EnumSet.allOf(RetroPart.class).stream()
                .filter(parts::contains)
                .map(RetroPart::yamlName)
                .collect(Collectors.joining(separator));
    }
}
