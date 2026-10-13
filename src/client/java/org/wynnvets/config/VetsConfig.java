package org.wynnvets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wynntils.utils.render.Texture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.logging.VetsLogger;

/**
 * Configuration manager for VetsMod toggleable features.
 *
 * <p>Provides boolean, long, string and tri-state configuration options,
 * persisted as JSON to {@code vetsmod/storage/config.json}.  Apart from the
 * debug keys, the key names are declared in {@link VetsConfigKeys}, whose
 * class doc describes the internal, user-facing and debug kinds.</p>
 */
public class VetsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getGameDir().resolve("vetsmod/storage/config.json");

    // Store configuration values by type
    private static final Map<String, Boolean> config = new ConcurrentHashMap<>();
    private static final Map<String, Long> longConfig = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> triStateConfig =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, String> stringConfig = new ConcurrentHashMap<>();

    /** Valid values for {@link VetsConfigKeys#VETS_ANNI_MODE}. */
    public static final String[] VALID_ANNI_MODES = {
        "silent", "passive", "aggressive",
    };

    /** Valid values for {@link VetsConfigKeys#VETS_ANNI_ROLE_STYLE}; the first is the
     *  default. Brigadier sorts suggestions alphabetically, so
     *  tab-completion does not show this order. Today {@code /wv config}
     *  suggests these values but refuses to set any of them
     *  ({@code config-set-rejects-role-style-and-flash-intensity}). */
    public static final String[] VALID_ROLE_STYLES = {
        "descriptive", "short", "formal",
    };

    /** Valid values for {@link VetsConfigKeys#VETS_ANNI_FLASH_INTENSITY}. Today nothing reads
     *  this array, and {@code /wv config} refuses to set any of these values
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
    public static final String[] TRISTATE_KEYS = {
        VetsConfigKeys.HANDLE_SPOILERS,
    };

    /**
     * String-valued keys for {@code /wv config}'s type dispatch: the
     * user-facing string keys, plus {@link VetsConfigKeys#VETS_ANNI_MODE}, which is not
     * user-facing (bug
     * {@code config-commands-anni-mode-suggestion-arm-unreachable}; see
     * {@code vetsmod_config.md}, the "vetsAnniMode is not in this list"
     * note). Not a list of every string key — persistence types a key by
     * its backing map, not by this array.
     */
    public static final String[] STRING_CONFIG_KEYS = {
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP,
        VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM,
        VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE,
        VetsConfigKeys.LEGACY_ITEM_FOREGROUND_COLOR,
        VetsConfigKeys.VETS_ANNI_ROLE_STYLE,
        VetsConfigKeys.VETS_ANNI_MODE,
        VetsConfigKeys.VETS_ANNI_FLASH_INTENSITY,
    };

    /**
     * Subset of user-facing keys that store an integer value (persisted as long).
     */
    public static final String[] INT_CONFIG_KEYS = {
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

    // ── Colour / sprite helpers ───────────────────────────────────────────
    // Thin delegates to LegacyItemStyle, kept from when mixins called them by
    // FQN; since the legacy highlight draws moved to LegacyHighlightPainter,
    // no mixin calls them any more.

    /** @see LegacyItemStyle#getColorNames() */
    public static Set<String> getColorNames() {
        return LegacyItemStyle.getColorNames();
    }

    /** @see LegacyItemStyle#isValidColor(String) */
    public static boolean isValidColor(String value) {
        return LegacyItemStyle.isValidColor(value);
    }

    /** @see LegacyItemStyle#isValidSprite(String) */
    public static boolean isValidSprite(String value) {
        return LegacyItemStyle.isValidSprite(value);
    }

    /** @see LegacyItemStyle#getBackgroundGradientTopColor() */
    public static int getLegacyBackgroundGradientTopColor() {
        return LegacyItemStyle.getBackgroundGradientTopColor();
    }

    /** @see LegacyItemStyle#getBackgroundGradientBottomColor() */
    public static int getLegacyBackgroundGradientBottomColor() {
        return LegacyItemStyle.getBackgroundGradientBottomColor();
    }

    /** @see LegacyItemStyle#getForegroundColor() */
    public static int getLegacyForegroundColor() {
        return LegacyItemStyle.getForegroundColor();
    }

    /** @see LegacyItemStyle#getColorRgb(String) */
    public static int getColorRgb(String name) {
        return LegacyItemStyle.getColorRgb(name);
    }

    /** @see LegacyItemStyle#getForegroundTexture() */
    public static Texture getLegacyForegroundTexture() {
        return LegacyItemStyle.getForegroundTexture();
    }

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
                    VetsConfigKeys.VETS_ANNI_MODE, "silent",
                    VetsConfigKeys.VETS_ANNI_FLASH_INTENSITY, "normal");

    /**
     * Returns the default value for a string config key, or {@code null} if
     * the key is not a string config key.
     */
    public static String getStringDefault(String key) {
        return STRING_DEFAULTS.get(key);
    }

    // Default values
    static {
        // Internal defaults
        config.put(VetsConfigKeys.VETS_AUTOMESSAGE, true);
        config.put(VetsConfigKeys.VETS_IS_STAFF, false);
        longConfig.put(VetsConfigKeys.VETS_LAST_STAFF_CHECK, 0L);
        longConfig.put(VetsConfigKeys.VETS_WAITLIST_UNLOCK_TIME, 0L);
        longConfig.put(VetsConfigKeys.VETS_HONOURARY_UNLOCK_TIME, 0L);
        longConfig.put(VetsConfigKeys.VETS_GUILD_CHECK_RESULT, 0L);
        longConfig.put(VetsConfigKeys.VETS_LAST_GUILD_CHECK, 0L);
        longConfig.put(VetsConfigKeys.VETS_DEBUG_ENABLED_AT, 0L);
        config.put(VetsConfigKeys.VETS_ANNI_ENABLED, false);
        config.put(VetsConfigKeys.VETS_ANNI_MODE_USER_SET, false);

        // /unlock <key> auth state — keys are persisted strings, the timestamp
        // is a long. Defaults to empty key + epoch=0 = "never authenticated".
        stringConfig.put(VetsConfigKeys.VETS_AUTH_KEY, "");
        stringConfig.put(VetsConfigKeys.VETS_AUTH_TIER, "");
        longConfig.put(VetsConfigKeys.VETS_AUTH_VERIFIED_AT, 0L);

        // User-facing BOOLEAN defaults — all true except colorBlindMode.
        // (moreReliableGuildCheck is true, despite what this comment used to say.)
        // This block holds 20 of the 30 USER_CONFIG_KEYS. The other ten default
        // outside it: vetsAnniEnabled (false, with the internal keys above), the
        // tri-state handleSpoilers (null = on) and the six string plus two int
        // user-facing keys, all just below.
        config.put(VetsConfigKeys.LEGACY_ITEM_HIGHLIGHTING, true);
        config.put(VetsConfigKeys.LEGACY_ITEM_SHOW_ENCHANTMENTS, true);
        config.put(VetsConfigKeys.PRINT_MOTD, true);
        config.put(VetsConfigKeys.PRINT_ANNI, true);
        config.put(VetsConfigKeys.VETS_ANNI_SHOW_HOVER_DETAILS, true);
        config.put(VetsConfigKeys.VETS_ANNI_PROMPT_RSVP, true);
        config.put(VetsConfigKeys.VETS_ANNI_SHOW_PREDICTION, true);
        config.put(VetsConfigKeys.VETS_ANNI_BOSSBAR_ENABLED, true);
        config.put(VetsConfigKeys.VETS_ANNI_FLASH_SOUND, true);
        config.put(VetsConfigKeys.VETS_ANNI_OUTLINES_ENABLED, true);
        config.put(VetsConfigKeys.VETS_ANNI_NAMETAGS_ENABLED, true);
        config.put(VetsConfigKeys.VETS_ANNI_ZONE_LINES, true);
        config.put(VetsConfigKeys.VETS_ANNI_SCROLL_WAYPOINT, true);
        config.put(VetsConfigKeys.VETS_ANNI_CHAT_ALERTS, true);
        config.put(VetsConfigKeys.VETS_ANNI_GHOSTS_PROMPT, true);
        config.put(VetsConfigKeys.PRINT_BRIDGE_MESSAGES, true);
        config.put(VetsConfigKeys.PRINT_SUCCESSFUL_AUTH, true);
        config.put(VetsConfigKeys.SHOW_SUPPORTER_GLINTS, true);
        config.put(VetsConfigKeys.COLOR_BLIND_MODE, false);
        config.put(VetsConfigKeys.MORE_RELIABLE_GUILD_CHECK, true);

        // Tri-state defaults (null = use default behaviour)
        triStateConfig.put(VetsConfigKeys.HANDLE_SPOILERS, null);

        // String defaults
        stringConfig.put(VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP, "orange");
        stringConfig.put(VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM, "crimson");
        stringConfig.put(VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE, "box_gradient_2");
        stringConfig.put(VetsConfigKeys.LEGACY_ITEM_FOREGROUND_COLOR, "orange");
        stringConfig.put(VetsConfigKeys.VETS_ANNI_ROLE_STYLE, "descriptive");
        stringConfig.put(VetsConfigKeys.VETS_ANNI_MODE, "silent");
        stringConfig.put(VetsConfigKeys.VETS_ANNI_FLASH_INTENSITY, "normal");
        stringConfig.put(VetsConfigKeys.VETS_ANNI_GHOSTS_PROMPT_SHOWN_FOR_STAMP, "");
        stringConfig.put(VetsConfigKeys.VETS_ANNI_USER_MODE, "");

        // Int defaults (stored as long)
        longConfig.put(VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_TOP_OPACITY, 69L);
        longConfig.put(VetsConfigKeys.LEGACY_ITEM_BACKGROUND_GRADIENT_BOTTOM_OPACITY, 100L);
    }

    /**
     * Get the value of a configuration option
     *
     * @param key The configuration key
     * @return The current value, or false if the key doesn't exist
     */
    public static boolean get(String key) {
        return config.getOrDefault(key, false);
    }

    /**
     * Set the value of a configuration option
     *
     * @param key   The configuration key
     * @param value The new value
     * @return true if the key exists and was updated, false otherwise
     */
    public static boolean set(String key, boolean value) {
        if (config.containsKey(key)) {
            config.put(key, value);
            save();
            return true;
        }
        return false;
    }

    /**
     * Get the tri-state value of a configuration option.
     *
     * @param key The configuration key
     * @return {@code Boolean.TRUE}, {@code Boolean.FALSE}, or {@code null} (default)
     */
    public static Boolean getTriState(String key) {
        if (!triStateConfig.containsKey(key)) return null;
        return triStateConfig.get(key);
    }

    /**
     * Set the tri-state value of a configuration option.
     *
     * @param key   The configuration key
     * @param value {@code true}, {@code false}, or {@code null} for default
     * @return true if the key exists and was updated, false otherwise
     */
    public static boolean setTriState(String key, Boolean value) {
        if (!triStateConfig.containsKey(key)) return false;
        triStateConfig.put(key, value);
        save();
        return true;
    }

    /**
     * Get the value of a long configuration option.
     *
     * @param key The configuration key
     * @return The current value, or 0 if the key doesn't exist
     */
    public static long getLong(String key) {
        return longConfig.getOrDefault(key, 0L);
    }

    /**
     * Set the value of a long configuration option.
     *
     * @param key   The configuration key
     * @param value The new value
     * @return true if the key exists and was updated, false otherwise
     */
    public static boolean setLong(String key, long value) {
        if (longConfig.containsKey(key)) {
            longConfig.put(key, value);
            save();
            return true;
        }
        return false;
    }

    /**
     * Registers a boolean configuration key with its default value.
     * If the key already exists, this is a no-op.  Intended for use by
     * subsystems that own their own config keys (e.g. debug utilities).
     * Must be called <em>before</em> {@link #load()} so that the key is
     * present when persisted values are read from disk.
     *
     * @param key          The configuration key
     * @param defaultValue The default value
     */
    public static void registerDefault(String key, boolean defaultValue) {
        config.putIfAbsent(key, defaultValue);
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

    /**
     * Get the string value of a configuration option.
     *
     * @param key The configuration key
     * @return The current value, or {@code null} if the key doesn't exist
     */
    public static String getString(String key) {
        return stringConfig.get(key);
    }

    /**
     * Set the string value of a configuration option.
     *
     * @param key   The configuration key
     * @param value The new value; not validated here, so callers own validation
     * @return true if the key exists and was updated, false otherwise
     */
    public static boolean setString(String key, String value) {
        if (!stringConfig.containsKey(key)) return false;
        stringConfig.put(key, value);
        save();
        return true;
    }

    /**
     * Load configuration from file
     * Should be called during mod initialization
     */
    public static void load() {
        if (!Files.exists(CONFIG_FILE)) {
            VetsLogger.debug("Config file not found, creating with defaults");
            save(); // Create the file with defaults
            return;
        }

        try {
            String json = Files.readString(CONFIG_FILE);
            VetsLogger.debug("Loading config from: {}", CONFIG_FILE);
            JsonObject loadedConfig = GSON.fromJson(json, JsonObject.class);

            if (loadedConfig != null) {
                // Load booleans
                for (String key : config.keySet()) {
                    JsonElement element = loadedConfig.get(key);
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isBoolean()) {
                        boolean value = element.getAsBoolean();
                        config.put(key, value);
                        VetsLogger.debug("Config: {} = {}", key, value);
                    }
                }

                // Load longs
                for (String key : longConfig.keySet()) {
                    JsonElement element = loadedConfig.get(key);
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isNumber()) {
                        long value = element.getAsLong();
                        longConfig.put(key, value);
                        VetsLogger.debug("Config: {} = {}", key, value);
                    }
                }

                // Load tri-state values (absent or null in JSON → default)
                for (String key : triStateConfig.keySet()) {
                    JsonElement element = loadedConfig.get(key);
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isBoolean()) {
                        Boolean value = element.getAsBoolean();
                        triStateConfig.put(key, value);
                        VetsLogger.debug("Config: {} = {}", key, value);
                    } else {
                        triStateConfig.put(key, null);
                        VetsLogger.debug("Config: {} = default", key);
                    }
                }

                // Load string values
                for (String key : stringConfig.keySet()) {
                    JsonElement element = loadedConfig.get(key);
                    if (element != null
                            && element.isJsonPrimitive()
                            && element.getAsJsonPrimitive().isString()) {
                        String value = element.getAsString();
                        stringConfig.put(key, value);
                        VetsLogger.debug("Config: {} = {}", key, value);
                    }
                }

                VetsLogger.debug("Configuration loaded");
            }
        } catch (IOException e) {
            VetsLogger.warn("Failed to load config: {}", e.getMessage());
        }
    }

    private static void save() {
        try {
            // Ensure the config directory exists
            Files.createDirectories(CONFIG_FILE.getParent());

            // Write the config to file
            JsonObject serialized = new JsonObject();
            for (Map.Entry<String, Boolean> entry : config.entrySet()) {
                serialized.addProperty(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Long> entry : longConfig.entrySet()) {
                serialized.addProperty(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Boolean> entry : triStateConfig.entrySet()) {
                if (entry.getValue() != null) {
                    serialized.addProperty(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, String> entry : stringConfig.entrySet()) {
                serialized.addProperty(entry.getKey(), entry.getValue());
            }

            String json = GSON.toJson(serialized);
            Files.writeString(CONFIG_FILE, json);
            VetsLogger.debug("Configuration saved");
        } catch (IOException e) {
            VetsLogger.warn("Failed to save config: {}", e.getMessage());
        }
    }
}
