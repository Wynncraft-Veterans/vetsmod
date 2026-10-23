package org.wynnvets.debug;

import java.util.Map;
import org.wynnvets.config.VetsConfig;

/**
 * Manages debug-only configuration keys, separate from user-facing config.
 *
 * <p>Debug keys are toggled via {@code /wv debug set <key> <value>} and are
 * intentionally kept out of the main
 * {@link org.wynnvets.config.VetsConfigKeys VetsConfigKeys} constants so that
 * debug tooling does not clutter production code.</p>
 *
 * <p>Call {@link #init()} <em>before</em> {@link VetsConfig#load()} so that
 * persisted values are picked up on startup.</p>
 */
public final class DebugConfigManager {

    /** When true, pressing numpad {@code +} while hovering an item dumps its full component tree. */
    public static final String ITEM_DUMP = "itemDump";

    /** Each debug key's default. {@link #init()} registers them, and
     *  {@link org.wynnvets.config.VetsConfigDefaults VetsConfigDefaults} writes them to the
     *  shipped {@code config.yml}, as they are in a first-run {@code config.json}. */
    public static final Map<String, Boolean> DEFAULTS = Map.of(ITEM_DUMP, false);

    /** All debug config keys, in display order. */
    public static final String[] DEBUG_CONFIG_KEYS = {
        ITEM_DUMP,
    };

    private DebugConfigManager() {}

    /**
     * Registers default values for every debug config key with the
     * central {@link VetsConfig} store.  Must be called before
     * {@link VetsConfig#load()}.
     */
    public static void init() {
        DEFAULTS.forEach(VetsConfig::registerDefault);
    }

    /**
     * Returns whether {@code key} is one of {@link #DEBUG_CONFIG_KEYS}.
     * {@code /wv debug set <key>} and {@code /wv debug set <key> <value>}
     * use it to reject unknown keys. The match is exact and
     * case-sensitive, unlike the case-insensitive tab completion
     * {@link DebugCommands} offers for the same keys.
     */
    public static boolean isDebugConfigKey(String key) {
        for (String debugKey : DEBUG_CONFIG_KEYS) {
            if (debugKey.equals(key)) return true;
        }
        return false;
    }
}
