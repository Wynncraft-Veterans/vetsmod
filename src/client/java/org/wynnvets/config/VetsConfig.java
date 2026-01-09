package org.wynnvets.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
    
    // Store all configuration values
    private static final Map<String, Boolean> config = new HashMap<>();
    
    // Configuration keys
    public static final String VETS_AUTOMESSAGE = "vetsAutomessage";
    
    // Default values
    static {
        // Default automessage to true (enabled)
        config.put(VETS_AUTOMESSAGE, true);
    }
    
    /**
     * Get the value of a configuration option
     * @param key The configuration key
     * @return The current value, or false if the key doesn't exist
     */
    public static boolean get(String key) {
        return config.getOrDefault(key, false);
    }
    
    /**
     * Set the value of a configuration option
     * @param key The configuration key
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
     * Check if a configuration key exists
     * @param key The configuration key to check
     * @return true if the key exists, false otherwise
     */
    public static boolean hasKey(String key) {
        return config.containsKey(key);
    }
    
    /**
     * Get all configuration keys
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
            Map<String, Boolean> loadedConfig = GSON.fromJson(json, new TypeToken<Map<String, Boolean>>(){}.getType());
            
            if (loadedConfig != null) {
                // Only load values for keys that exist in our config
                for (String key : config.keySet()) {
                    if (loadedConfig.containsKey(key)) {
                        config.put(key, loadedConfig.get(key));
                        LOGGER.info("Loaded config: {} = {}", key, loadedConfig.get(key));
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
            String json = GSON.toJson(config);
            Files.writeString(CONFIG_FILE, json);
            LOGGER.debug("Configuration saved to file");
        } catch (IOException e) {
            LOGGER.error("Failed to save config file: {}", e.getMessage());
        }
    }
}
