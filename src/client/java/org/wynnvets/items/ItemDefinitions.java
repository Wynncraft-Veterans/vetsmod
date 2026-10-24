package org.wynnvets.items;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Loads and evaluates item name patterns from {@code definitions.yml}.
 *
 * <p>Patterns are grouped into categories that control how items are highlighted
 * or filtered in tooltips.  Each category's regex patterns are loaded once at
 * startup and matched against item display names at runtime.</p>
 *
 * <p>The file is standard YAML, read with SnakeYAML's safe loader: a stream of
 * documents separated by {@code ---}, each a mapping from category name to a
 * list of entries. A pattern holding a regex backslash goes in single quotes,
 * where YAML keeps the backslash; inside double quotes only YAML's own escapes
 * are legal.</p>
 *
 * <h2>Pattern categories</h2>
 * <ul>
 *   <li><b>definitions</b> — exact legacy item name patterns (matched regardless of lore).</li>
 *   <li><b>no_lore_legacy</b> — items that are legacy ONLY when they also have
 *       no lore lines (e.g. old keys, vanilla materials, holiday items).</li>
 *   <li><b>misc_definitions</b> — items requiring a "Misc. Item" rarity line.</li>
 *   <li><b>unenchanted</b> — items excluded from enchanted/foil detection.</li>
 *   <li><b>not_pedestal</b> — names excluded from the pedestal-wiped branch
 *       (NPCs that render as coloured-name armour shells with no lore).</li>
 *   <li><b>notjunk</b> — modern junk-tier items that should NOT be highlighted.</li>
 *   <li><b>new_format_override</b> — names that collide between old and new formats.</li>
 *   <li><b>enchant_excluded_items</b> — Minecraft item IDs excluded from foil detection.</li>
 *   <li><b>blocked_screen_titles</b> — screen titles (not item names) where legacy
 *       processing is skipped entirely.</li>
 *   <li><b>inventory_suppressors</b> — names of items (the Block Bank) whose presence in
 *       the player's inventory skips legacy processing entirely.</li>
 * </ul>
 */
public final class ItemDefinitions {
    private static final List<Pattern> legacyPatterns = new ArrayList<>();
    private static final List<Pattern> noLoreLegacyPatterns = new ArrayList<>();
    private static final List<Pattern> miscPatterns = new ArrayList<>();
    private static final List<Pattern> unenchantedPatterns = new ArrayList<>();
    private static final List<Pattern> notPedestalPatterns = new ArrayList<>();
    private static final List<Pattern> notjunkPatterns = new ArrayList<>();
    private static final List<Pattern> newFormatOverridePatterns = new ArrayList<>();
    private static final Set<String> enchantExcludedItems = new HashSet<>();
    private static final List<Pattern> blockedScreenTitlePatterns = new ArrayList<>();
    private static final List<Pattern> inventorySuppressorPatterns = new ArrayList<>();

    private ItemDefinitions() {}

    public static void load() {
        legacyPatterns.clear();
        noLoreLegacyPatterns.clear();
        miscPatterns.clear();
        unenchantedPatterns.clear();
        notPedestalPatterns.clear();
        notjunkPatterns.clear();
        newFormatOverridePatterns.clear();
        enchantExcludedItems.clear();
        blockedScreenTitlePatterns.clear();
        inventorySuppressorPatterns.clear();

        try (InputStream is = ItemDefinitions.class.getResourceAsStream("/definitions.yml")) {
            if (is == null) {
                VetsLogger.warn("definitions.yml not found in resources");
                return;
            }
            parse(is);
            VetsLogger.debug(
                    "Loaded {} legacy, {} no-lore-legacy, {} misc, {} unenchanted, {} not-pedestal, {} notjunk, {} new-format-override, {} enchant-excluded, {} blocked-screen-title, and {} inventory-suppressor definition(s)",
                    legacyPatterns.size(),
                    noLoreLegacyPatterns.size(),
                    miscPatterns.size(),
                    unenchantedPatterns.size(),
                    notPedestalPatterns.size(),
                    notjunkPatterns.size(),
                    newFormatOverridePatterns.size(),
                    enchantExcludedItems.size(),
                    blockedScreenTitlePatterns.size(),
                    inventorySuppressorPatterns.size());
        } catch (IOException | RuntimeException e) {
            // A YAML or regex syntax error is a RuntimeException; log it rather than fail
            // client init. The categories keep whatever loaded before the error.
            VetsLogger.error("Failed to load definitions.yml", e);
        }
    }

    // Package-private for unit tests. See ItemDefinitionsTest.
    static void parse(InputStream input) throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        for (Object document : yaml.loadAll(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            // A document that is not a mapping (a bare list after "---", say) holds no
            // category, so its entries are dropped.
            if (!(document instanceof Map<?, ?> categories)) continue;
            for (Map.Entry<?, ?> category : categories.entrySet()) {
                if (!(category.getKey() instanceof String name)
                        || !(category.getValue() instanceof List<?> entries)) {
                    continue;
                }
                for (Object entry : entries) {
                    if (entry != null) add(name, String.valueOf(entry));
                }
            }
        }
    }

    /** Adds one entry to its category. An unknown category is ignored. */
    private static void add(String category, String value) {
        switch (category) {
            case "definitions" -> legacyPatterns.add(Pattern.compile(value));
            case "no_lore_legacy" -> noLoreLegacyPatterns.add(Pattern.compile(value));
            case "misc_definitions" -> miscPatterns.add(Pattern.compile(value));
            case "unenchanted" -> unenchantedPatterns.add(Pattern.compile(value));
            // The other six drop an empty entry, which the four above compile;
            // ItemDefinitionsTest pins the asymmetry.
            case "not_pedestal" -> {
                if (!value.isEmpty()) notPedestalPatterns.add(Pattern.compile(value));
            }
            case "notjunk" -> {
                if (!value.isEmpty()) notjunkPatterns.add(Pattern.compile(value));
            }
            case "new_format_override" -> {
                if (!value.isEmpty()) newFormatOverridePatterns.add(Pattern.compile(value));
            }
            case "enchant_excluded_items" -> {
                if (!value.isEmpty()) enchantExcludedItems.add(value);
            }
            case "blocked_screen_titles" -> {
                if (!value.isEmpty()) blockedScreenTitlePatterns.add(Pattern.compile(value));
            }
            case "inventory_suppressors" -> {
                if (!value.isEmpty()) inventorySuppressorPatterns.add(Pattern.compile(value));
            }
            default -> {}
        }
    }

    public static boolean isLegacy(String itemName) {
        for (Pattern pattern : legacyPatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNoLoreLegacy(String itemName) {
        for (Pattern pattern : noLoreLegacyPatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMiscLegacy(String itemName) {
        for (Pattern pattern : miscPatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isUnenchanted(String itemName) {
        if (isLegacy(itemName)) return false;
        for (Pattern pattern : unenchantedPatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEnchantExcludedItem(ItemStack stack) {
        if (enchantExcludedItems.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return enchantExcludedItems.contains(id);
    }

    /**
     * Returns {@code true} if the given name matches a not-pedestal blacklist
     * pattern.  Items matching these patterns must never be routed through the
     * pedestal-wiped detection branch (typically NPCs that render as bare
     * coloured-name armour shells).
     */
    public static boolean isNotPedestal(String itemName) {
        for (Pattern pattern : notPedestalPatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNotJunk(String itemName) {
        for (Pattern pattern : notjunkPatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the given name matches a new-format override pattern.
     * On a new-format item (see {@link LegacyItemHandler#isNewFormatItem}), such a
     * name skips the {@code definitions} and {@code misc_definitions} name matches;
     * the other legacy checks, {@code no_lore_legacy} included, still apply.
     */
    public static boolean isNewFormatOverride(String itemName) {
        for (Pattern pattern : newFormatOverridePatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the given screen title matches a blocked-screen
     * pattern.  Items inside these screens (housing UI panels, song pickers,
     * permission editors, etc.) reuse glints and armour shells as UI affordances
     * and must never be routed through any legacy-detection branch.
     */
    public static boolean isBlockedScreenTitle(String screenTitle) {
        if (screenTitle == null) return false;
        for (Pattern pattern : blockedScreenTitlePatterns) {
            if (pattern.matcher(screenTitle).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the given item name matches an inventory-suppressor
     * pattern. While an item with such a name is anywhere in the player's inventory,
     * nothing is treated as a legacy item (see
     * {@link LegacyItemHandler#isDetectionSuppressed}).
     */
    public static boolean isInventorySuppressor(String itemName) {
        for (Pattern pattern : inventorySuppressorPatterns) {
            if (pattern.matcher(itemName).matches()) {
                return true;
            }
        }
        return false;
    }
}
