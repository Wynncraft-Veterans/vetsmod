package org.wynnvets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration manager for VetsMod toggleable features
 * Provides an expandable system for managing boolean configuration options
 */
public class VetsConfig {
  private static final Logger LOGGER = LoggerFactory.getLogger("vetsmod");
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_FILE = FabricLoader.getInstance().getGameDir().resolve("vetsmod/storage/config.json");

  // Store configuration values by type
  private static final Map<String, Boolean> config = new HashMap<>();
  private static final Map<String, Long> longConfig = new HashMap<>();

  // Configuration keys
  public static final String VETS_AUTOMESSAGE = "vetsAutomessage";
  public static final String VETS_IS_STAFF = "vetsIsStaff";
  public static final String VETS_LAST_STAFF_CHECK = "vetsLastStaffCheck";

  // Default values
  static {
    // Default automessage to true (enabled)
    config.put(VETS_AUTOMESSAGE, true);
    // Default staff status to false (unknown/non-staff until checked)
    config.put(VETS_IS_STAFF, false);
    // Default last staff-check timestamp to 0 (unknown)
    longConfig.put(VETS_LAST_STAFF_CHECK, 0L);
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
   * Check if a configuration key exists
   *
   * @param key The configuration key to check
   * @return true if the key exists, false otherwise
   */
  public static boolean hasKey(String key) {
    return config.containsKey(key);
  }

  /**
   * Get all configuration keys
   *
   * @return Array of all valid configuration keys
   */
  public static String[] getAllKeys() {
    return config.keySet().toArray(new String[0]);
  }

  /**
   * Reset all configuration options to their default values
   */
  public static void resetToDefaults() {
    save();
  }

  /**
   * Load configuration from file
   * Should be called during mod initialization
   */
  public static void load() {
    if (!Files.exists(CONFIG_FILE)) {
      LOGGER.info("Config file not found, using defaults");
      save(); // Create the file with defaults
      return;
    }

    try {
      String json = Files.readString(CONFIG_FILE);
      LOGGER.info("Loading config from: {}", CONFIG_FILE);
      JsonObject loadedConfig = GSON.fromJson(json, JsonObject.class);

      if (loadedConfig != null) {
        // Load booleans
        for (String key : config.keySet()) {
          JsonElement element = loadedConfig.get(key);
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            boolean value = element.getAsBoolean();
            config.put(key, value);
            LOGGER.info("Loaded config: {} = {}", key, value);
          }
        }

        // Load longs
        for (String key : longConfig.keySet()) {
          JsonElement element = loadedConfig.get(key);
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            long value = element.getAsLong();
            longConfig.put(key, value);
            LOGGER.info("Loaded config: {} = {}", key, value);
          }
        }

        LOGGER.info("Configuration loaded from file");
      }
    } catch (IOException e) {
      LOGGER.error("Failed to load config file: {}", e.getMessage());
    }
  }

  /**
   * Save configuration to file
   */
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

      String json = GSON.toJson(serialized);
      Files.writeString(CONFIG_FILE, json);
      LOGGER.debug("Configuration saved to file");
    } catch (IOException e) {
      LOGGER.error("Failed to save config file: {}", e.getMessage());
    }
  }
}
