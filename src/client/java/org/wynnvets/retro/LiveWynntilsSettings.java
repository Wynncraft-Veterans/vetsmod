package org.wynnvets.retro;

import com.wynntils.core.components.Managers;
import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.persisted.config.Config;
import com.wynntils.utils.EnumUtils;
import java.util.Objects;
import java.util.Optional;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.logging.VetsLogger;

/**
 * {@link WynntilsSettings} over Wynntils itself and {@link VetsConfigKeys#RETRO_WYNNTILS_SNAPSHOT}.
 * A setting is found through Wynntils' own API, and a value is read with the setting's
 * {@code tryParseStringValue}. Only the methods reach Wynntils, so its one instance touches nothing
 * as it loads.
 */
final class LiveWynntilsSettings implements WynntilsSettings {

    static final WynntilsSettings INSTANCE = new LiveWynntilsSettings();

    private LiveWynntilsSettings() {}

    @Override
    public boolean loaded() {
        try {
            return !Managers.Feature.getFeatures().isEmpty();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public Setting find(String featureName, String configName) {
        Optional<Feature> feature = Managers.Feature.getFeatureFromString(featureName);
        Optional<Config<?>> config =
                feature.flatMap(found -> found.getConfigOptionFromString(configName));
        if (config.isEmpty()) {
            VetsLogger.warn(
                    "Retro: Wynntils has no setting {} {}; skipped it", featureName, configName);
            return null;
        }
        return new LiveSetting<>(config.get());
    }

    @Override
    public void save() {
        try {
            Managers.Config.saveConfig();
        } catch (RuntimeException | LinkageError e) {
            VetsLogger.warn("Retro: could not save Wynntils' settings", e);
        }
    }

    @Override
    public String snapshot() {
        return VetsConfig.getString(VetsConfigKeys.RETRO_WYNNTILS_SNAPSHOT);
    }

    @Override
    public void writeSnapshot(String text) {
        VetsConfig.setString(VetsConfigKeys.RETRO_WYNNTILS_SNAPSHOT, text);
    }

    /** One {@link Config}. */
    private record LiveSetting<T>(Config<T> config) implements Setting {

        @Override
        public boolean reads(String value) {
            return config.tryParseStringValue(value) != null;
        }

        @Override
        public boolean holds(String value) {
            return Objects.equals(config.get(), config.tryParseStringValue(value));
        }

        @Override
        public String valueText() {
            T current = config.get();
            String text = current instanceof Enum<?> e ? EnumUtils.toJsonFormat(e) : null;
            return text == null ? String.valueOf(current) : text;
        }

        @Override
        public boolean userEdited() {
            return config.userEdited();
        }

        @Override
        public void set(String value) {
            config.setValue(config.tryParseStringValue(value));
        }

        @Override
        public void reset() {
            config.reset();
        }
    }
}
