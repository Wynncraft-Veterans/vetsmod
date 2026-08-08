package org.wynnvets.config;

import com.wynntils.utils.render.Texture;
import java.util.Set;

/**
 * Computes the resolved rendering style for legacy item slot highlights
 * from the user's {@link VetsConfig} settings.
 *
 * <p>Legacy item highlights use a two-layer visual: a colour gradient fill
 * (top + bottom colours with independent opacity) overlaid by a tinted
 * Wynntils highlight sprite.  This class reads the named-colour and
 * sprite config values from {@link VetsConfig} and resolves them to
 * ARGB ints / {@link Texture} constants for the renderer.</p>
 */
public final class LegacyItemStyle {

    /** Parallel to {@link VetsConfig#VALID_SPRITES} (same index = same sprite). */
    private static final Texture[] FOREGROUND_TEXTURES = {
        Texture.HIGHLIGHT_WYNN,
        Texture.HIGHLIGHT_TAG,
        Texture.HIGHLIGHT_CIRCLE_TRANSPARENT,
        Texture.HIGHLIGHT_CIRCLE_OPAQUE,
        Texture.HIGHLIGHT_CIRCLE_OUTLINE_LARGE,
        Texture.HIGHLIGHT_CIRCLE_OUTLINE_SMALL,
        Texture.HIGHLIGHT_BOX_TRANSPARENT,
        Texture.HIGHLIGHT_BOX_OPAQUE,
        Texture.HIGHLIGHT_BOX_GRADIENT_1,
        Texture.HIGHLIGHT_BOX_GRADIENT_2,
    };

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
     * Resolve {@link VetsConfig#LEGACY_ITEM_FOREGROUND_SPRITE} to its
     * corresponding Wynntils atlas-backed {@link Texture}.
     *
     * @return the matching highlight {@link Texture}; falls back to
     *         {@link Texture#HIGHLIGHT_WYNN} on unknown values
     */
    public static Texture getForegroundTexture() {
        String name = VetsConfig.getString(VetsConfig.LEGACY_ITEM_FOREGROUND_SPRITE);
        if (name != null) {
            for (int i = 0; i < VetsConfig.VALID_SPRITES.length; i++) {
                if (VetsConfig.VALID_SPRITES[i].equalsIgnoreCase(name)) return FOREGROUND_TEXTURES[i];
            }
        }
        return Texture.HIGHLIGHT_WYNN;
    }
}
