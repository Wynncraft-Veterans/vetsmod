package org.wynnvets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.logging.VetsLogger;

/**
 * Configuration manager for VetsMod toggleable features.
 *
 * <p>Provides boolean, long, string and tri-state configuration options,
 * persisted as JSON to {@code vetsmod/storage/config.json}.  Apart from the
 * debug keys, the key names are declared in {@link VetsConfigKeys}, whose
 * class doc describes the internal, user-facing and debug kinds; the
 * {@code /wv config} key lists and valid-value lists are in
 * {@link VetsConfigSchema}. The first-run defaults are in
 * {@link VetsConfigDefaults}, and the copy {@code /wv config <key> default}
 * restores from is {@link ShippedConfig}.</p>
 */
public final class VetsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getGameDir().resolve("vetsmod/storage/config.json");

    // Store configuration values by type
    private static final Map<String, Boolean> config = new ConcurrentHashMap<>();
    private static final Map<String, Long> longConfig = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> triStateConfig =
            Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, String> stringConfig = new ConcurrentHashMap<>();

    // Default values. VetsConfigDefaults holds them, and the build writes the same values to
    // the config.yml that /wv config <key> default reads (ShippedConfig).
    static {
        VetsConfigDefaults.seed(config, longConfig, triStateConfig, stringConfig);
    }

    private VetsConfig() {}

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
     *
     * <p>A file that can't be parsed (malformed JSON, an out-of-range number, bytes that aren't
     * UTF-8) never stops the game from starting: it is logged at warn, moved aside as
     * {@code config.json.bad-<epoch>}, and the defaults are used. So the player's settings and
     * stored {@code /unlock} key are lost until that file is repaired and moved back.</p>
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
        } catch (RuntimeException | CharacterCodingException e) {
            // The file doesn't parse (a hand edit's typo, a number out of range, bytes that
            // aren't UTF-8). Move it aside before any setter's save() can overwrite it, and run
            // on the defaults, keeping any keys already applied.
            VetsLogger.warn(
                    "Config file {} could not be read ({}); using defaults",
                    CONFIG_FILE,
                    e.toString());
            quarantineBadConfig();
        } catch (IOException e) {
            VetsLogger.warn("Failed to load config: {}", e.getMessage());
        }
    }

    private static void quarantineBadConfig() {
        Path aside = CONFIG_FILE.resolveSibling("config.json.bad-" + System.currentTimeMillis());
        try {
            Files.move(CONFIG_FILE, aside);
            VetsLogger.warn("Moved the unreadable config file to {}", aside);
        } catch (IOException | RuntimeException e) {
            VetsLogger.warn("Could not move the unreadable config file aside: {}", e.toString());
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
