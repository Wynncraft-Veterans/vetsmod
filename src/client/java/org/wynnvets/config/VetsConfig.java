package org.wynnvets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.logging.VetsLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration manager for VetsMod toggleable features.
 *
 * <p>Provides an expandable system for managing boolean and long configuration
 * options, persisted as JSON to {@code vetsmod/storage/config.json}.  Keys fall
 * into two categories:</p>
 * <ul>
 *   <li><b>Internal</b> — used by the mod to cache transient state (e.g.
 *       {@link #VETS_IS_STAFF}, {@link #VETS_LAST_STAFF_CHECK}).  These are
 *       <em>not</em> exposed to the {@code /wv config} command.</li>
 *   <li><b>User-facing</b> — toggleable by the player via {@code /wv config
 *       &lt;key&gt; &lt;value&gt;}.  Listed in {@link #USER_CONFIG_KEYS}.</li>
 * </ul>
 */
public class VetsConfig {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_FILE = FabricLoader.getInstance().getGameDir().resolve("vetsmod/storage/config.json");

  // Store configuration values by type
  private static final Map<String, Boolean> config = new HashMap<>();
  private static final Map<String, Long> longConfig = new HashMap<>();

  // ── Internal configuration keys (not user-facing) ───────────────────────
  public static final String VETS_AUTOMESSAGE = "vetsAutomessage";
  public static final String VETS_IS_STAFF = "vetsIsStaff";
  public static final String VETS_LAST_STAFF_CHECK = "vetsLastStaffCheck";
  public static final String VETS_WAITLIST_UNLOCK_TIME = "vetsWaitlistUnlockTime";
  public static final String VETS_HONOURARY_UNLOCK_TIME = "vetsHonouraryUnlockTime";
  public static final String VETS_UNLOCK_EXPIRY_WARNINGS = "vetsUnlockExpiryWarnings";

  // ── User-facing configuration keys (toggled via /wv config) ─────────────

  /** Whether legacy/enchanted/junk item highlighting is shown in tooltips and inventory slots. */
  public static final String LEGACY_ITEM_HIGHLIGHTING = "legacyItemHighlighting";

  /** Whether the MOTD is automatically printed on world join. */
  public static final String PRINT_MOTD = "printMOTD";

  /** Whether the annihilation stamp is automatically printed on world join. */
  public static final String PRINT_ANNI = "printANNI";

  /** Whether bridge (guild chat relay) messages are displayed in chat. */
  public static final String PRINT_BRIDGE_MESSAGES = "printBridgeMessages";

  /** Whether supporter animated gradient glints are shown on nametags and pills. */
  public static final String SHOW_SUPPORTER_GLINTS = "showSupporterGlints";

  /** Whether {@code ||spoiler||} markers are rendered as hoverable spoiler labels. */
  public static final String HANDLE_SPOILERS = "handleSpoilers";

  /**
   * Ordered list of configuration keys that can be toggled by the player via
   * {@code /wv config <key> <value>}.  Internal keys (staff status, timestamps,
   * etc.) are intentionally excluded.
   */
  public static final String[] USER_CONFIG_KEYS = {
      LEGACY_ITEM_HIGHLIGHTING,
      PRINT_MOTD,
      PRINT_ANNI,
      PRINT_BRIDGE_MESSAGES,
      SHOW_SUPPORTER_GLINTS,
      HANDLE_SPOILERS,
  };

  // Default values
  static {
    // Internal defaults
    config.put(VETS_AUTOMESSAGE, true);
    config.put(VETS_IS_STAFF, false);
    longConfig.put(VETS_LAST_STAFF_CHECK, 0L);
    longConfig.put(VETS_WAITLIST_UNLOCK_TIME, 0L);
    longConfig.put(VETS_HONOURARY_UNLOCK_TIME, 0L);
    longConfig.put(VETS_UNLOCK_EXPIRY_WARNINGS, 0L);

    // User-facing defaults (all enabled by default)
    config.put(LEGACY_ITEM_HIGHLIGHTING, true);
    config.put(PRINT_MOTD, true);
    config.put(PRINT_ANNI, true);
    config.put(PRINT_BRIDGE_MESSAGES, true);
    config.put(SHOW_SUPPORTER_GLINTS, true);
    config.put(HANDLE_SPOILERS, true);
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
   * Check if a configuration key exists.
   *
   * @param key The configuration key to check
   * @return true if the key exists, false otherwise
   */
  public static boolean hasKey(String key) {
    return config.containsKey(key);
  }

  /**
   * Check if a configuration key is user-facing (togglable via {@code /wv config}).
   *
   * @param key The configuration key to check
   * @return true if it is a user-configurable key
   */
  public static boolean isUserConfigKey(String key) {
    for (String userKey : USER_CONFIG_KEYS) {
      if (userKey.equals(key)) return true;
    }
    return false;
  }

  /**
   * Get all configuration keys.
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
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            boolean value = element.getAsBoolean();
            config.put(key, value);
            VetsLogger.debug("Config: {} = {}", key, value);
          }
        }

        // Load longs
        for (String key : longConfig.keySet()) {
          JsonElement element = loadedConfig.get(key);
          if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            long value = element.getAsLong();
            longConfig.put(key, value);
            VetsLogger.debug("Config: {} = {}", key, value);
          }
        }

        VetsLogger.debug("Configuration loaded");
      }
    } catch (IOException e) {
      VetsLogger.warn("Failed to load config: {}", e.getMessage());
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
      VetsLogger.debug("Configuration saved");
    } catch (IOException e) {
      VetsLogger.warn("Failed to save config: {}", e.getMessage());
    }
  }
}
