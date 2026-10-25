package org.wynnvets.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The values {@code config.json} starts from. {@link VetsConfig} seeds its four maps from
 * {@link #seed}, and the build runs {@link #main} to write the same values to {@code config.yml},
 * the jar resource {@link ShippedConfig} reads for {@code /wv config <key> default}. Nothing here
 * touches Fabric or Minecraft, which is what lets the build run it.
 */
public final class VetsConfigDefaults {

    private VetsConfigDefaults() {}

    /** Puts every key's default into {@link VetsConfig}'s four maps, one per value type. */
    static void seed(
            Map<String, Boolean> config,
            Map<String, Long> longConfig,
            Map<String, Boolean> triStateConfig,
            Map<String, String> stringConfig) {
        // Internal defaults
        config.put(VetsConfigKeys.VETS_AUTOMESSAGE, true);
        config.put(VetsConfigKeys.VETS_IS_STAFF, false);
        longConfig.put(VetsConfigKeys.VETS_LAST_STAFF_CHECK, 0L);
        longConfig.put(VetsConfigKeys.VETS_GUILD_CHECK_RESULT, 0L);
        longConfig.put(VetsConfigKeys.VETS_LAST_GUILD_CHECK, 0L);
        stringConfig.put(VetsConfigKeys.VETS_GUILD_CHECK_OWNER, "");
        longConfig.put(VetsConfigKeys.VETS_DEBUG_ENABLED_AT, 0L);
        config.put(VetsConfigKeys.VETS_ANNI_MODE_USER_SET, false);

        // /unlock <key> auth state — keys are persisted strings, the timestamp
        // is a long. Defaults to empty key + epoch=0 = "never authenticated".
        stringConfig.put(VetsConfigKeys.VETS_AUTH_KEY, "");
        stringConfig.put(VetsConfigKeys.VETS_AUTH_TIER, "");
        longConfig.put(VetsConfigKeys.VETS_AUTH_VERIFIED_AT, 0L);

        // User-facing BOOLEAN defaults — all true except colorBlindMode.
        // (moreReliableGuildCheck is true, despite what this comment used to say.)
        // This block holds 20 of the 29 USER_CONFIG_KEYS. The other nine default
        // outside it: the tri-state handleSpoilers (null = on) and the six string
        // plus two int user-facing keys, all just below.
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
     * The pairs a first-run {@code config.json} holds, in the order {@code save()} writes the
     * maps: booleans, longs, tri-states, strings. That is what {@link #seed} puts, plus the debug
     * keys {@link org.wynnvets.debug.DebugConfigManager#init() DebugConfigManager#init()}
     * registers before {@code load()}. A tri-state left unset ({@code null}) is left out, as
     * {@code save()} leaves it out.
     */
    static Map<String, Object> firstRunValues() {
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Boolean> triStates = new LinkedHashMap<>();
        Map<String, String> strings = new LinkedHashMap<>();
        seed(booleans, longs, triStates, strings);
        org.wynnvets.debug.DebugConfigManager.DEFAULTS.forEach(booleans::putIfAbsent);

        Map<String, Object> values = new LinkedHashMap<>(booleans);
        values.putAll(longs);
        triStates.forEach(
                (key, value) -> {
                    if (value != null) values.put(key, value);
                });
        values.putAll(strings);
        return values;
    }

    /**
     * Writes {@link #firstRunValues()} as YAML to the path in {@code args[0]}. Run by
     * {@code build.gradle}'s {@code generateConfigYml} task, never by the mod.
     */
    public static void main(String[] args) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String yaml =
                "# Written by the build from VetsConfigDefaults: what config.json starts from.\n"
                        + "# /wv config <key> default reads it. Do not edit.\n"
                        + new Yaml(options).dump(firstRunValues());
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        Files.writeString(out, yaml, StandardCharsets.UTF_8);
    }
}
