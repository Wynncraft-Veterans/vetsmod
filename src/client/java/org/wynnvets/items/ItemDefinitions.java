package org.wynnvets.items;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.logging.VetsLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads the item lists in {@code definitions.yml} and answers which list a name is on.
 *
 * <p>The file is standard YAML, read with SnakeYAML's safe loader: a mapping from section
 * name to a list of entries, in which a repeated section is a parse error (several
 * {@code ---}-separated documents are also accepted, and add up). Every section except
 * {@code enchant_excluded_items} holds regular expressions that must match the whole name.
 * The file's header describes the format and each section; an empty entry and an unknown
 * section name are ignored.</p>
 */
public final class ItemDefinitions {
    private static final List<Pattern> legacyPatterns = new ArrayList<>();
    private static final List<Pattern> miscPatterns = new ArrayList<>();
    private static final List<Pattern> noLoreLegacyPatterns = new ArrayList<>();
    private static final List<Pattern> newFormatOverridePatterns = new ArrayList<>();
    private static final List<Pattern> unenchantedPatterns = new ArrayList<>();
    private static final List<Pattern> notjunkPatterns = new ArrayList<>();
    private static final List<Pattern> notPedestalPatterns = new ArrayList<>();
    private static final List<Pattern> blockedScreenTitlePatterns = new ArrayList<>();
    private static final List<Pattern> inventorySuppressorPatterns = new ArrayList<>();

    /** {@code enchant_excluded_items}: plain item ids such as {@code minecraft:enchanted_book}. */
    private static final Set<String> enchantExcludedItems = new HashSet<>();

    /** The pattern sections, by their name in the file. */
    private static final Map<String, List<Pattern>> patternSections = new LinkedHashMap<>();

    static {
        patternSections.put("definitions", legacyPatterns);
        patternSections.put("misc_definitions", miscPatterns);
        patternSections.put("no_lore_legacy", noLoreLegacyPatterns);
        patternSections.put("new_format_override", newFormatOverridePatterns);
        patternSections.put("unenchanted", unenchantedPatterns);
        patternSections.put("notjunk", notjunkPatterns);
        patternSections.put("not_pedestal", notPedestalPatterns);
        patternSections.put("blocked_screen_titles", blockedScreenTitlePatterns);
        patternSections.put("inventory_suppressors", inventorySuppressorPatterns);
    }

    private ItemDefinitions() {}

    /** Replaces every list with the contents of the bundled {@code definitions.yml}. */
    public static void load() {
        patternSections.values().forEach(List::clear);
        enchantExcludedItems.clear();

        try (InputStream is = ItemDefinitions.class.getResourceAsStream("/definitions.yml")) {
            if (is == null) {
                VetsLogger.warn("definitions.yml not found in resources");
                return;
            }
            parse(is);
            Map<String, Integer> counts = new LinkedHashMap<>();
            patternSections.forEach((section, patterns) -> counts.put(section, patterns.size()));
            counts.put("enchant_excluded_items", enchantExcludedItems.size());
            VetsLogger.debug("Loaded definitions.yml: {}", counts);
        } catch (IOException | RuntimeException e) {
            // A YAML or regex syntax error is a RuntimeException; log it rather than fail
            // client init. The sections keep whatever loaded before the error.
            VetsLogger.error("Failed to load definitions.yml", e);
        }
    }

    /** Adds the file's entries to the lists, without clearing them first. */
    // Package-private for unit tests. See ItemDefinitionsTest.
    static void parse(InputStream input) throws IOException {
        LoaderOptions options = new LoaderOptions();
        // In one mapping a repeated key would silently replace the earlier section's list.
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        for (Object document : yaml.loadAll(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            // A document that is not a mapping (a bare list after "---", say) names no
            // section, so its entries are dropped.
            if (!(document instanceof Map<?, ?> sections)) continue;
            for (Map.Entry<?, ?> section : sections.entrySet()) {
                if (!(section.getKey() instanceof String name)
                        || !(section.getValue() instanceof List<?> entries)) {
                    continue;
                }
                for (Object entry : entries) {
                    if (entry != null) add(name, String.valueOf(entry));
                }
            }
        }
    }

    private static void add(String section, String entry) {
        if (entry.isEmpty()) return;
        if (section.equals("enchant_excluded_items")) {
            enchantExcludedItems.add(entry);
            return;
        }
        List<Pattern> patterns = patternSections.get(section);
        if (patterns != null) patterns.add(Pattern.compile(entry));
    }

    /** {@code definitions}: legacy by name alone. */
    public static boolean isLegacy(String itemName) {
        return matchesAny(legacyPatterns, itemName);
    }

    /** {@code misc_definitions}: legacy when the lore also has a "Misc. Item" rarity line. */
    public static boolean isMiscLegacy(String itemName) {
        return matchesAny(miscPatterns, itemName);
    }

    /** {@code no_lore_legacy}: legacy when the item has no lore. */
    public static boolean isNoLoreLegacy(String itemName) {
        return matchesAny(noLoreLegacyPatterns, itemName);
    }

    /**
     * {@code new_format_override}: on a new-format item (see
     * {@link LegacyItemHandler#isNewFormatItem}) such a name skips the {@code definitions} and
     * {@code misc_definitions} matches; the other checks still apply.
     */
    public static boolean isNewFormatOverride(String itemName) {
        return matchesAny(newFormatOverridePatterns, itemName);
    }

    /**
     * {@code unenchanted}: a current item whose glint does not make it legacy. A name in
     * {@code definitions} is never unenchanted.
     */
    public static boolean isUnenchanted(String itemName) {
        return !isLegacy(itemName) && matchesAny(unenchantedPatterns, itemName);
    }

    /** {@code enchant_excluded_items}: an item id whose glint is vanilla. */
    public static boolean isEnchantExcludedItem(ItemStack stack) {
        if (enchantExcludedItems.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return enchantExcludedItems.contains(id);
    }

    /** {@code notjunk}: a current item whose "Junk Item" rarity line does not make it legacy. */
    public static boolean isNotJunk(String itemName) {
        return matchesAny(notjunkPatterns, itemName);
    }

    /** {@code not_pedestal}: never treated as a pedestal-wiped item (merchants, NPC icons). */
    public static boolean isNotPedestal(String itemName) {
        return matchesAny(notPedestalPatterns, itemName);
    }

    /** {@code blocked_screen_titles}: a screen in which no item is treated as legacy. */
    public static boolean isBlockedScreenTitle(String screenTitle) {
        return matchesAny(blockedScreenTitlePatterns, screenTitle);
    }

    /**
     * {@code inventory_suppressors}: while an item with such a name is anywhere in the player's
     * inventory, no item is treated as legacy (see
     * {@link LegacyItemHandler#isDetectionSuppressed}).
     */
    public static boolean isInventorySuppressor(String itemName) {
        return matchesAny(inventorySuppressorPatterns, itemName);
    }

    /** Whether any pattern matches the whole of {@code text}; never for {@code null}. */
    private static boolean matchesAny(List<Pattern> patterns, String text) {
        if (text == null) return false;
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).matches()) return true;
        }
        return false;
    }
}
