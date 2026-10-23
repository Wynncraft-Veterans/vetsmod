package org.wynnvets.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.wynnvets.logging.VetsLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The defaults vetsmod ships as {@code config.yml} in its jar, which the build writes from
 * {@link VetsConfigDefaults}. Its one reader is {@code /wv config <key> default}, and the
 * {@code reset} that string and int keys keep as an alias. The file is read once, from vetsmod's
 * own mod container rather than the shared classpath, so another mod's {@code config.yml} cannot
 * stand in for it. A missing or unreadable file reads as having no defaults.
 */
public final class ShippedConfig {

    private static final String RESOURCE = "config.yml";

    // Package-private for unit tests. See ShippedConfigTest.
    static volatile Map<String, Object> values;

    private ShippedConfig() {}

    /** The shipped default of a boolean key, or {@code null} when it has none. */
    public static Boolean bool(String key) {
        return get(key) instanceof Boolean b ? b : null;
    }

    /** The shipped default of an int key, or {@code null} when it has none. */
    public static Long number(String key) {
        return get(key) instanceof Number n ? n.longValue() : null;
    }

    /** The shipped default of a string key, or {@code null} when it has none. */
    public static String string(String key) {
        return get(key) instanceof String s ? s : null;
    }

    private static Object get(String key) {
        Map<String, Object> loaded = values;
        if (loaded == null) {
            loaded = load();
            values = loaded;
        }
        return loaded.get(key);
    }

    private static Map<String, Object> load() {
        Optional<Path> path =
                FabricLoader.getInstance()
                        .getModContainer("vetsmod")
                        .flatMap(container -> container.findPath(RESOURCE));
        if (path.isEmpty()) {
            VetsLogger.warn(
                    "vetsmod's jar has no {}; /wv config default has nothing to restore", RESOURCE);
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(path.get())) {
            return parse(in);
        } catch (IOException | RuntimeException e) {
            VetsLogger.warn("Could not read vetsmod's {}: {}", RESOURCE, e.toString());
            return Map.of();
        }
    }

    // Package-private for unit tests. See ShippedConfigTest.
    static Map<String, Object> parse(InputStream in) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        Map<String, Object> parsed = new LinkedHashMap<>();
        if (root instanceof Map<?, ?> map) {
            map.forEach(
                    (key, value) -> {
                        if (key instanceof String name && value != null) parsed.put(name, value);
                    });
        }
        return parsed;
    }
}
