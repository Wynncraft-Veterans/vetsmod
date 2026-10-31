package org.wynnvets.retro.manifest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.rendering.image.Rect;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The shapes the rule files are written in: readers for one value and walkers over a section's
 * rows. Every method takes a {@code where}, the path its error message names, and throws an
 * {@link IllegalArgumentException} on a value of the wrong shape.
 */
final class ManifestYaml {

    static final String TEXTURES_FILE = "retro_textures.yml";
    static final String INGREDIENTS_FILE = "retro_ingredients.yml";

    /** A colour as the files write it: {@code '#RRGGBB'}. */
    private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");

    /** A range of numbers as the files write it: {@code '45-53'}. */
    private static final Pattern RANGE = Pattern.compile("(\\d+)-(\\d+)");

    /** A table's name: it goes into model and texture ids. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    /** A Wynntils type's name, as its enum writes it: {@code SPEAR}, {@code MASTERY_TOME}. */
    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z_]*");

    private ManifestYaml() {}

    /** Reads one row of a section keyed by pattern. */
    @FunctionalInterface
    interface PatternRow<T> {
        /**
         * @param pattern the row's key, compiled
         * @param value the row's value
         * @param rowWhere the row's path, for error messages
         */
        T read(Pattern pattern, Object value, String rowWhere);
    }

    /** Reads one row of a section keyed by a string. */
    @FunctionalInterface
    interface KeyedRow<V> {
        /**
         * @param key the row's key
         * @param value the row's value
         * @param rowWhere the row's path, for error messages
         */
        V read(String key, Object value, String rowWhere);
    }

    /** Reads one row of a list of mappings. */
    @FunctionalInterface
    interface MappingRow<T> {
        /**
         * @param fields the row, holding only the keys its walker allows
         * @param rowWhere the row's path, for error messages
         */
        T read(Map<?, ?> fields, String rowWhere);
    }

    /** The path of a section of {@code retro_textures.yml}. */
    static String inTextures(String section) {
        return TEXTURES_FILE + ": " + section;
    }

    /** The file's one document, a mapping of sections; an empty file has none. */
    static Map<?, ?> sections(InputStream input, String file, Set<String> known) {
        LoaderOptions options = new LoaderOptions();
        // A repeated key would silently replace the earlier section or row.
        options.setAllowDuplicateKeys(false);
        Object document =
                new Yaml(new SafeConstructor(options))
                        .load(new InputStreamReader(input, StandardCharsets.UTF_8));
        if (document == null) return Map.of();
        if (!(document instanceof Map<?, ?> sections)) {
            throw new IllegalArgumentException(file + ": not a mapping of sections");
        }
        for (Object name : sections.keySet()) {
            if (name == null || !known.contains(name)) {
                VetsLogger.debug("{}: ignored the unknown section {}", file, name);
            }
        }
        return sections;
    }

    // ── Rows ────────────────────────────────────────────────────────────

    /** A mapping section keyed by pattern, in file order; an empty pattern is skipped. */
    static <T> List<T> patternRows(Object section, String where, PatternRow<T> row) {
        List<T> rows = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(section, where).entrySet()) {
            String regex = text(entry.getKey(), where);
            if (regex == null) continue;
            rows.add(
                    row.read(
                            Pattern.compile(regex), entry.getValue(), where + ": '" + regex + "'"));
        }
        return rows;
    }

    /** A mapping section keyed by any text, in file order; an empty key is skipped. */
    static <V> Map<String, V> textRows(Object section, String where, KeyedRow<V> row) {
        Map<String, V> rows = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapping(section, where).entrySet()) {
            String key = text(entry.getKey(), where);
            if (key == null) continue;
            rows.put(key, row.read(key, entry.getValue(), where + ": '" + key + "'"));
        }
        return rows;
    }

    /**
     * A mapping section keyed by literal id, in file order; an empty id is skipped.
     *
     * @param namespace the namespace every id must be in, or {@code null} for any
     */
    static <V> Map<String, V> idRows(
            Object section, String where, String namespace, KeyedRow<V> row) {
        return textRows(
                section,
                where,
                (id, value, rowWhere) -> {
                    literalId(id, namespace, where);
                    return row.read(id, value, rowWhere);
                });
    }

    /**
     * A mapping section keyed by the name of a Wynntils type, in file order; an empty name is
     * skipped.
     *
     * @param example a name for the error message, such as {@code SPEAR}
     */
    static <V> Map<String, V> typeRows(
            Object section, String where, String example, KeyedRow<V> row) {
        return textRows(
                section,
                where,
                (type, value, rowWhere) -> {
                    if (!TYPE.matcher(type).matches()) {
                        throw new IllegalArgumentException(
                                where + ": expected a type such as '" + example + "', not " + type);
                    }
                    return row.read(type, value, rowWhere);
                });
    }

    /**
     * A list whose rows are mappings, in file order.
     *
     * @param keys the keys a row may have
     */
    static <T> List<T> listRows(
            Object section, String where, List<String> keys, MappingRow<T> row) {
        String[] allowed = keys.toArray(String[]::new);
        List<T> rows = new ArrayList<>();
        for (Object entry : list(section, where)) {
            String rowWhere = where + ": " + entry;
            Map<?, ?> fields = mapping(entry, rowWhere);
            onlyKeys(fields, rowWhere, allowed);
            rows.add(row.read(fields, rowWhere));
        }
        return rows;
    }

    /**
     * Refuses a key other than {@code keys}, so a misspelt one fails rather than silently losing
     * what it set.
     */
    static void onlyKeys(Map<?, ?> fields, String where, String... keys) {
        // Arrays.asList tolerates a null key, which List.of's contains does not.
        List<String> known = Arrays.asList(keys);
        for (Object key : fields.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException(where + ": unknown key " + key);
            }
        }
    }

    // ── Values ──────────────────────────────────────────────────────────

    /** A list of patterns; an empty one is skipped. */
    static List<Pattern> patterns(Object section, String where) {
        List<Pattern> patterns = new ArrayList<>();
        for (Object entry : list(section, where)) {
            String regex = text(entry, where);
            if (regex != null) patterns.add(Pattern.compile(regex));
        }
        return patterns;
    }

    /**
     * A list of whole numbers from 0 to {@code max}, each written as a number or as an inclusive
     * range such as {@code '45-53'}; an absent list is empty.
     */
    static Set<Integer> numbers(Object value, int max, String where) {
        Set<Integer> numbers = new LinkedHashSet<>();
        for (Object entry : list(value, where)) {
            Matcher range = entry instanceof String s ? RANGE.matcher(s) : null;
            int from;
            int to;
            if (entry instanceof Integer n) {
                from = n;
                to = n;
            } else if (range != null && range.matches()) {
                from = Integer.parseInt(range.group(1));
                to = Integer.parseInt(range.group(2));
            } else {
                throw new IllegalArgumentException(
                        where + ": expected a number or a range such as '45-53', not " + entry);
            }
            if (from < 0 || to > max || from > to) {
                throw new IllegalArgumentException(
                        where + ": " + entry + " is not within 0-" + max);
            }
            for (int n = from; n <= to; n++) numbers.add(n);
        }
        return numbers;
    }

    /**
     * A replacement id: a full id whose {@code $n} references name only groups that
     * {@code pattern} has. {@code Matcher.appendReplacement} would otherwise throw at decision
     * time, inside a resource reload.
     */
    static String replacement(Object value, Pattern pattern, String where) {
        String replacement = text(value, where);
        if (replacement == null || !hasNamespace(replacement)) {
            throw new IllegalArgumentException(
                    where + ": expected a full id such as 'minecraft:block/stone', not " + value);
        }
        int groups = pattern.matcher("").groupCount();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                // The escaped character is literal; a trailing backslash escapes nothing.
                if (++i == replacement.length()) {
                    throw new IllegalArgumentException(where + ": trailing backslash");
                }
            } else if (c == '$') {
                // appendReplacement always reads the first digit after '$' as the group.
                char next = i + 1 < replacement.length() ? replacement.charAt(i + 1) : ' ';
                if (next < '0' || next > '9' || next - '0' > groups) {
                    throw new IllegalArgumentException(
                            where
                                    + ": '"
                                    + replacement
                                    + "' refers to a group the pattern"
                                    + " lacks (it has "
                                    + groups
                                    + ")");
                }
            }
        }
        return replacement;
    }

    /**
     * A literal id, not a pattern: a full id such as {@code minecraft:gui/container/inventory},
     * in {@code namespace} when that is given.
     *
     * @return the id, or {@code null} for an absent or empty one
     */
    static String literalId(Object value, String namespace, String where) {
        String id = text(value, where);
        if (id == null) return null;
        int colon = id.indexOf(':');
        boolean literal =
                hasNamespace(id) && id.chars().noneMatch(c -> "\\$*+?()[]{}|^".indexOf(c) >= 0);
        if (!literal || (namespace != null && !id.substring(0, colon).equals(namespace))) {
            throw new IllegalArgumentException(
                    where
                            + ": expected a literal id"
                            + (namespace == null ? "" : " in the " + namespace + " namespace")
                            + ", not "
                            + id);
        }
        return id;
    }

    /**
     * A literal id in any namespace, which must be there.
     *
     * @param missing what the error message says when it is absent or empty
     */
    static String requiredId(Object value, String where, String missing) {
        String id = literalId(value, null, where);
        if (id == null) throw new IllegalArgumentException(where + ": " + missing);
        return id;
    }

    /** Whether {@code id} starts with a literal namespace: {@code minecraft:block/stone}. */
    static boolean hasNamespace(String id) {
        int colon = id.indexOf(':');
        return colon > 0 && id.lastIndexOf('$', colon) < 0;
    }

    /** A name in a table: lower-case letters, digits and underscores, which must be there. */
    static String name(Object key, String where) {
        String name = text(key, where);
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(where + ": not a lower-case name: " + key);
        }
        return name;
    }

    /** A {@code '#RRGGBB'} colour as {@code 0xRRGGBB}. */
    static int colour(Object value, String where) {
        if (value instanceof String s && COLOUR.matcher(s).matches()) {
            return Integer.parseInt(s.substring(1), 16);
        }
        throw new IllegalArgumentException(
                where + ": expected a quoted colour such as '#A06540', not " + value);
    }

    /** A list of {@code '#RRGGBB'} colours as {@code 0xRRGGBB}; an absent list is empty. */
    static List<Integer> colours(Object value, String where) {
        List<Integer> colours = new ArrayList<>();
        for (Object entry : list(value, where)) colours.add(colour(entry, where));
        return colours;
    }

    /** A whole number from {@code min} to {@code max}, which must be there. */
    static int boundedInt(Object value, int min, int max, String where) {
        if (!(value instanceof Integer n) || n < min || n > max) {
            throw new IllegalArgumentException(
                    where
                            + ": expected a whole number from "
                            + min
                            + " to "
                            + max
                            + ", not "
                            + value);
        }
        return n;
    }

    /** A list of exactly {@code count} whole numbers, none negative. */
    static int[] ints(Object value, int count, String where) {
        List<?> entries = value instanceof List<?> l ? l : value == null ? null : List.of(value);
        if (entries == null || entries.size() != count) {
            throw new IllegalArgumentException(
                    where + ": expected " + count + " whole numbers, not " + value);
        }
        int[] numbers = new int[count];
        for (int i = 0; i < count; i++) {
            if (!(entries.get(i) instanceof Integer n) || n < 0) {
                throw new IllegalArgumentException(
                        where + ": expected whole numbers of 0 or more, not " + value);
            }
            numbers[i] = n;
        }
        return numbers;
    }

    /** {@code [x, y, width, height]}, with a width and height of at least 1. */
    static Rect rect(Object value, String where) {
        int[] numbers = ints(value, 4, where);
        if (numbers[2] == 0 || numbers[3] == 0) {
            throw new IllegalArgumentException(where + ": an empty rectangle: " + value);
        }
        return new Rect(numbers[0], numbers[1], numbers[2], numbers[3]);
    }

    /** A {@code true} or {@code false}; {@code false} when absent. */
    static boolean flag(Object value, String where) {
        if (value == null) return false;
        if (value instanceof Boolean set) return set;
        throw new IllegalArgumentException(where + ": expected true or false, not " + value);
    }

    /** A list; an absent one is empty. */
    static List<?> list(Object value, String where) {
        if (value == null) return List.of();
        if (value instanceof List<?> entries) return entries;
        throw new IllegalArgumentException(where + ": expected a list, not " + value);
    }

    /** A mapping; an absent one is empty. */
    static Map<?, ?> mapping(Object value, String where) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> rows) return rows;
        throw new IllegalArgumentException(where + ": expected a mapping, not " + value);
    }

    /** A string entry, or {@code null} for an absent or empty one. */
    static String text(Object value, String where) {
        if (value == null) return null;
        if (value instanceof String s) return s.isEmpty() ? null : s;
        throw new IllegalArgumentException(where + ": expected a quoted string, not " + value);
    }

    /**
     * A string entry, which must be there.
     *
     * @param missing what the error message says when it is absent or empty
     */
    static String requiredText(Object value, String where, String missing) {
        String text = text(value, where);
        if (text == null) throw new IllegalArgumentException(where + ": " + missing);
        return text;
    }

    /** An unmodifiable copy that keeps {@code map}'s order, which {@code Map.copyOf} does not. */
    static <K, V> Map<K, V> frozen(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
