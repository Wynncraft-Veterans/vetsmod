package org.wynnvets.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of named colours available for legacy-item highlight configuration.
 *
 * <p>All values are stored as opaque RGB ({@code 0xRRGGBB}).  Alpha is applied
 * at render time based on a separate opacity setting.</p>
 */
public final class NamedColor {

    private static final Map<String, Integer> COLORS;

    static {
        var c = new LinkedHashMap<String, Integer>();

        // Minecraft formatting colours (§ codes)
        c.put("black",        0x000000);
        c.put("dark_blue",    0x0000AA);
        c.put("dark_green",   0x00AA00);
        c.put("dark_aqua",    0x00AAAA);
        c.put("dark_red",     0xAA0000);
        c.put("dark_purple",  0xAA00AA);
        c.put("gold",         0xFFAA00);
        c.put("gray",         0xAAAAAA);
        c.put("dark_gray",    0x555555);
        c.put("blue",         0x5555FF);
        c.put("green",        0x55FF55);
        c.put("aqua",         0x55FFFF);
        c.put("red",          0xFF5555);
        c.put("light_purple", 0xFF55FF);
        c.put("yellow",       0xFFFF55);
        c.put("white",        0xFFFFFF);

        // Wynntils rarity highlights
        c.put("unique",       0xFFFF00);
        c.put("rare",         0xFF00FF);
        c.put("legendary",    0x00FFFF);
        c.put("fabled",       0xFF5555);
        c.put("mythic",       0x4C004C);
        c.put("crafted",      0x008A8A);

        // Common CSS colours
        c.put("orange",       0xFFA500);
        c.put("cyan",         0x00FFFF);
        c.put("magenta",      0xFF00FF);
        c.put("pink",         0xFFC0CB);
        c.put("lime",         0x00FF00);
        c.put("teal",         0x008080);
        c.put("coral",        0xFF7F50);
        c.put("salmon",       0xFA8072);
        c.put("crimson",      0xDC143C);
        c.put("indigo",       0x4B0082);
        c.put("violet",       0xEE82EE);
        c.put("turquoise",    0x40E0D0);
        c.put("silver",       0xC0C0C0);
        c.put("maroon",       0x800000);
        c.put("navy",         0x000080);
        c.put("olive",        0x808000);

        // Custom mod colours
        c.put("legacy_orange", 0xF0501E);

        // Special
        c.put("transparent",  0x000000);

        COLORS = Collections.unmodifiableMap(c);
    }

    private NamedColor() {}

    /** Returns the ordered set of all valid colour names. */
    public static Set<String> getNames() {
        return COLORS.keySet();
    }

    /** Check whether a value is a valid colour name. */
    public static boolean isValid(String name) {
        return name != null && COLORS.containsKey(name.toLowerCase());
    }

    /**
     * Look up the RGB int for a colour name.
     *
     * @return the RGB value, or {@code null} if the name is not recognised
     */
    public static Integer getRgb(String name) {
        if (name == null) return null;
        return COLORS.get(name.toLowerCase());
    }

    /**
     * Look up the RGB int for a colour name, returning a default on miss.
     */
    public static int getRgbOrDefault(String name, int defaultRgb) {
        Integer rgb = getRgb(name);
        return rgb != null ? rgb : defaultRgb;
    }

    /**
     * Combine an RGB colour with an opacity percentage (0–100) to produce an
     * ARGB int suitable for Minecraft rendering.
     *
     * @param rgb     base colour ({@code 0xRRGGBB})
     * @param opacity percentage 0–100 (clamped)
     * @return ARGB int ({@code 0xAARRGGBB})
     */
    public static int withAlpha(int rgb, int opacity) {
        int clamped = Math.max(0, Math.min(100, opacity));
        int alpha = (int) Math.round(clamped * 255.0 / 100.0);
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
