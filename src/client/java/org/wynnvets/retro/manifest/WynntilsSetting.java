package org.wynnvets.retro.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A {@code wynntils_config} entry, in the words {@code /wynntils config set} takes. A feature or
 * setting name that is not one word, or a feature with no settings, fails the parse.
 *
 * @param feature the feature's short name, such as {@code ItemHighlight}
 * @param config the setting's field name, such as {@code highlightTexture}
 * @param value the value as the command would read it, such as {@code boxTransparent}
 */
public record WynntilsSetting(String feature, String config, String value) {

    static final String SECTION = "wynntils_config";

    /** A Wynntils feature or setting name, one word as {@code /wynntils config set} takes it. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");

    static List<WynntilsSetting> parse(Object section) {
        String where = ManifestYaml.inTextures(SECTION);
        List<WynntilsSetting> settings = new ArrayList<>();
        for (Map.Entry<?, ?> feature : ManifestYaml.mapping(section, where).entrySet()) {
            String name = name(feature.getKey(), where);
            String featureWhere = where + ": " + name;
            Map<?, ?> configs = ManifestYaml.mapping(feature.getValue(), featureWhere);
            if (configs.isEmpty()) {
                throw new IllegalArgumentException(featureWhere + ": no settings");
            }
            for (Map.Entry<?, ?> config : configs.entrySet()) {
                String field = name(config.getKey(), featureWhere);
                String value =
                        ManifestYaml.requiredText(
                                config.getValue(),
                                featureWhere + ": " + field,
                                "expected a quoted value");
                settings.add(new WynntilsSetting(name, field, value));
            }
        }
        return settings;
    }

    private static String name(Object value, String where) {
        String name = ManifestYaml.text(value, where);
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    where + ": expected a Wynntils name such as 'ItemHighlight', not " + value);
        }
        return name;
    }
}
