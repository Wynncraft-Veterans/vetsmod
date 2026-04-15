package org.wynnvets.config;

import java.util.Set;

/**
 * Computes the resolved rendering style for legacy item slot highlights
 * from the user's {@link VetsConfig} settings.
 *
 * <p>Legacy item highlights use a two-layer visual: a colour gradient fill
 * (top + bottom colours with independent opacity) overlaid by a tinted
 * Wynntils spritesheet tile.  This class reads the named-colour and
 * sprite config values from {@link VetsConfig} and resolves them to
 * ARGB ints / pixel offsets for the renderer.</p>
 */
public final class LegacyItemStyle {

    private LegacyItemStyle() {}

    // ── Colour validation ──────────────────────────────────────────────

    /**
     * Returns the ordered set of valid colour names.
     */
    public static Set<String> getColorNames() {
        return NamedColor.getNames();
    }

    /**
     * Check whether a value is a valid colour name.
     */
    public static boolean isValidColor(String value) {
        return NamedColor.isValid(value);
    }

    /**
     * Check whether a value is a valid sprite name.
     */
    public static boolean isValidSprite(String value) {
        if (value == null) return false;
        for (String s : VetsConfig.VALID_SPRITES) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    // ── Resolved rendering values ─────────────────────────────────────

    /**
     * Parse {@link VetsConfig#LEGACY_ITEM_BACKGROUND_GRADIENT_TOP} into an ARGB int,
     * combining the colour name with the top opacity setting.
     *
     * @return the computed ARGB colour, or orange at 69% on error
     */
    public static int getBackgroundGradientTopColor() {
        String name = VetsConfig.getString(VetsConfig.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP);
        int rgb = NamedColor.getRgbOrDefault(name, 0xFFA500);
        int opacity = (int) VetsConfig.getLong(VetsConfig.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY);
        if ("transparent".equalsIgnoreCase(name)) return 0x00000000;
        return NamedColor.withAlpha(rgb, opacity);
    }

    /**
     * Parse {@link VetsConfig#LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM} into an ARGB int,
     * combining the colour name with the bottom opacity setting.
     *
     * @return the computed ARGB colour, or crimson at 100% on error
     */
    public static int getBackgroundGradientBottomColor() {
        String name = VetsConfig.getString(VetsConfig.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM);
        int rgb = NamedColor.getRgbOrDefault(name, 0xDC143C);
        int opacity = (int) VetsConfig.getLong(VetsConfig.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY);
        if ("transparent".equalsIgnoreCase(name)) return 0x00000000;
        return NamedColor.withAlpha(rgb, opacity);
    }

    /**
     * Parse {@link VetsConfig#LEGACY_ITEM_FOREGROUND_COLOR} into a fully-opaque ARGB int.
     *
     * @return the parsed colour, or {@code 0xFFFFA500} (orange) on error
     */
    public static int getForegroundColor() {
        String name = VetsConfig.getString(VetsConfig.LEGACY_ITEM_FOREGROUND_COLOR);
        if (name != null) {
            if ("transparent".equalsIgnoreCase(name)) return 0x00000000;
            Integer rgb = NamedColor.getRgb(name);
            if (rgb != null) return 0xFF000000 | rgb;
        }
        return 0xFFFFA500;
    }

    /**
     * Resolve a colour name to its RGB int (no alpha), for display in chat.
     * Returns 0xFFA500 (orange) on error.
     */
    public static int getColorRgb(String name) {
        return NamedColor.getRgbOrDefault(name, 0xFFA500);
    }

    /**
     * Get the spritesheet U-offset (in pixels) for {@link VetsConfig#LEGACY_ITEM_FOREGROUND_SPRITE}.
     *
     * @return ordinal × 18, defaulting to 0 (wynn)
     */
    public static int getForegroundSpriteOffset() {
        String name = VetsConfig.getString(VetsConfig.LEGACY_ITEM_FOREGROUND_SPRITE);
        if (name != null) {
            for (int i = 0; i < VetsConfig.VALID_SPRITES.length; i++) {
                if (VetsConfig.VALID_SPRITES[i].equalsIgnoreCase(name)) return i * 18;
            }
        }
        return 0;
    }
}
