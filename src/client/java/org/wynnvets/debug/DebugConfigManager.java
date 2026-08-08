package org.wynnvets.debug;

import org.wynnvets.config.VetsConfig;

/**
 * Manages debug-only configuration keys, separate from user-facing config.
 *
 * <p>Debug keys are toggled via {@code /wv debug set <key> <value>} and are
 * intentionally kept out of the main {@link VetsConfig} constants so that
 * debug tooling does not clutter production code.</p>
 *
 * <p>Call {@link #init()} <em>before</em> {@link VetsConfig#load()} so that
 * persisted values are picked up on startup.</p>
 */
public final class DebugConfigManager {

    /** When true, pressing numpad {@code +} while hovering an item dumps its full component tree. */
    public static final String ITEM_DUMP = "itemDump";

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
        VetsConfig.registerDefault(ITEM_DUMP, false);
    }

    public static boolean isDebugConfigKey(String key) {
        for (String debugKey : DEBUG_CONFIG_KEYS) {
            if (debugKey.equals(key)) return true;
        }
        return false;
    }
}
