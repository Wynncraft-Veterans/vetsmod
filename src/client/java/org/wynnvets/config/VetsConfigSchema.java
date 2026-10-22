package org.wynnvets.config;

import java.util.Map;

/**
 * What is known about the keys in {@link VetsConfigKeys} beyond their names: which ones the player
 * can set with {@code /wv config}, how that command types them, the valid values of the keys that
 * take a fixed set of names other than colours (colour names are {@link NamedColor}'s), and the
 * defaults its {@code reset} restores. {@link VetsConfig} holds the stored values and their
 * first-run defaults.
 */
public final class VetsConfigSchema {
    /** Valid values for {@link VetsConfigKeys#VETS_ANNI_ROLE_STYLE}; the first is the
     *  default. Brigadier sorts suggestions alphabetically, so
     *  tab-completion does not show this order. Today {@code /wv config}
     *  suggests these values but refuses to set any of them
     *  ({@code config-set-rejects-role-style-and-flash-intensity}). */
    public static final String[] VALID_ROLE_STYLES = {
        "descriptive", "short", "formal",
    };

    /** Valid values for {@link VetsConfigKeys#VETS_ANNI_FLASH_INTENSITY}. Today nothing in
     *  the mod reads this array, and {@code /wv config} refuses to set any of these values
     *  ({@code config-set-rejects-role-style-and-flash-intensity}). */
    public static final String[] VALID_FLASH_INTENSITIES = {
        "subtle", "normal", "strong",
    };

    /**
     * Ordered list of configuration keys that can be toggled by the player via
     * {@code /wv config <key> <value>}.  Internal keys (staff status, timestamps,
     * etc.) are intentionally excluded.
     */
    public static final String[] USER_CONFIG_KEYS = {
        VetsConfigKeys.LEGACY_ITEM_HIGHLIGHTING,
        VetsConfigKeys.LEGACY_ITEM_SHOW_ENCHANTMENTS,
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP,
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY,
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM,
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY,
        VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE,
        VetsConfigKeys.LEGACY_ITEM_FOREGROUND_COLOR,
        VetsConfigKeys.PRINT_MOTD,
        VetsConfigKeys.PRINT_ANNI,
        VetsConfigKeys.VETS_ANNI_ENABLED,
        VetsConfigKeys.VETS_ANNI_SHOW_HOVER_DETAILS,
        VetsConfigKeys.VETS_ANNI_PROMPT_RSVP,
        VetsConfigKeys.VETS_ANNI_SHOW_PREDICTION,
        VetsConfigKeys.VETS_ANNI_ROLE_STYLE,
        VetsConfigKeys.VETS_ANNI_BOSSBAR_ENABLED,
        VetsConfigKeys.VETS_ANNI_FLASH_INTENSITY,
        VetsConfigKeys.VETS_ANNI_FLASH_SOUND,
        VetsConfigKeys.VETS_ANNI_OUTLINES_ENABLED,
        VetsConfigKeys.VETS_ANNI_NAMETAGS_ENABLED,
        VetsConfigKeys.VETS_ANNI_ZONE_LINES,
        VetsConfigKeys.VETS_ANNI_SCROLL_WAYPOINT,
        VetsConfigKeys.VETS_ANNI_CHAT_ALERTS,
        VetsConfigKeys.VETS_ANNI_GHOSTS_PROMPT,
        VetsConfigKeys.PRINT_BRIDGE_MESSAGES,
        VetsConfigKeys.PRINT_SUCCESSFUL_AUTH,
        VetsConfigKeys.SHOW_SUPPORTER_GLINTS,
        VetsConfigKeys.COLOR_BLIND_MODE,
        VetsConfigKeys.HANDLE_SPOILERS,
        VetsConfigKeys.MORE_RELIABLE_GUILD_CHECK,
    };

    /**
     * Subset of user-facing keys that support tri-state values: {@code true},
     * {@code false}, or {@code null} (meaning "use default behaviour").
     */
    static final String[] TRISTATE_KEYS = {
        VetsConfigKeys.HANDLE_SPOILERS,
    };

    /**
     * String-valued keys for {@code /wv config}'s type dispatch: the
     * user-facing string keys. Not a list of every string key — persistence
     * types a key by its backing map, not by this array, and
     * {@link VetsConfigKeys#VETS_ANNI_MODE} is a string key that is absent here
     * because it is not user-facing (see {@code vetsmod_config.md}, the
     * "vetsAnniMode is not in this list" note).
     */
    static final String[] STRING_CONFIG_KEYS = {
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP,
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM,
        VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE,
        VetsConfigKeys.LEGACY_ITEM_FOREGROUND_COLOR,
        VetsConfigKeys.VETS_ANNI_ROLE_STYLE,
        VetsConfigKeys.VETS_ANNI_FLASH_INTENSITY,
    };

    /**
     * Subset of user-facing keys that store an integer value (persisted as long).
     */
    static final String[] INT_CONFIG_KEYS = {
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY,
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY,
    };

    /** Valid sprite names for {@link VetsConfigKeys#LEGACY_ITEM_FOREGROUND_SPRITE}, matching
     *  the Wynntils {@code HighlightTexture} enum order (parallel to
     *  {@link LegacyItemStyle#FOREGROUND_TEXTURES}). */
    public static final String[] VALID_SPRITES = {
        "wynn",
        "tag",
        "circle_transparent",
        "circle_opaque",
        "circle_outline_large",
        "circle_outline_small",
        "box_transparent",
        "box_opaque",
        "box_gradient_1",
        "box_gradient_2",
    };

    // ── Int config defaults (for reset) ─────────────────────────────────

    private static final Map<String, Long> INT_DEFAULTS =
            Map.of(
                    VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY, 69L,
                    VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY, 100L);

    /**
     * Returns the default value for an int config key, or {@code null} if
     * the key is not an int config key.
     */
    public static Long getIntDefault(String key) {
        return INT_DEFAULTS.get(key);
    }

    // ── String config defaults (for reset) ──────────────────────────────

    private static final Map<String, String> STRING_DEFAULTS =
            Map.of(
                    VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP, "orange",
                    VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM, "crimson",
                    VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE, "box_gradient_2",
                    VetsConfigKeys.LEGACY_ITEM_FOREGROUND_COLOR, "orange",
                    VetsConfigKeys.VETS_ANNI_ROLE_STYLE, "descriptive",
                    VetsConfigKeys.VETS_ANNI_FLASH_INTENSITY, "normal");

    /**
     * Returns the default value for a string config key, or {@code null} if
     * the key is not a string config key.
     */
    public static String getStringDefault(String key) {
        return STRING_DEFAULTS.get(key);
    }

    public static boolean isUserConfigKey(String key) {
        for (String userKey : USER_CONFIG_KEYS) {
            if (userKey.equals(key)) return true;
        }
        return false;
    }

    public static boolean isTriStateKey(String key) {
        for (String tsKey : TRISTATE_KEYS) {
            if (tsKey.equals(key)) return true;
        }
        return false;
    }

    public static boolean isStringKey(String key) {
        for (String sk : STRING_CONFIG_KEYS) {
            if (sk.equals(key)) return true;
        }
        return false;
    }

    public static boolean isIntKey(String key) {
        for (String ik : INT_CONFIG_KEYS) {
            if (ik.equals(key)) return true;
        }
        return false;
    }

    private VetsConfigSchema() {}
}
