package org.wynnvets.items;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.wynnvets.logging.VetsLogger;

/**
 * Loads and evaluates item name patterns from {@code definitions.yml}.
 *
 * <p>Patterns are grouped into four categories: legacy items, miscellaneous legacy,
 * unenchanted items, and not-junk items. Each category's regex patterns are loaded
 * once at startup and matched against item display names at runtime to determine
 * how items should be highlighted or filtered in tooltips.</p>
 */
public class ItemDefinitions {
  private static final List<Pattern> legacyPatterns = new ArrayList<>();
  private static final List<Pattern> noLoreExcludedPatterns = new ArrayList<>();
  private static final List<Pattern> vanillaStatlessPatterns = new ArrayList<>();
  private static final List<Pattern> miscPatterns = new ArrayList<>();
  private static final List<Pattern> unenchantedPatterns = new ArrayList<>();
  private static final List<Pattern> notjunkPatterns = new ArrayList<>();
  private static final List<Pattern> newFormatOverridePatterns = new ArrayList<>();
  private static final Set<String> enchantExcludedItems = new HashSet<>();

  public static void load() {
    legacyPatterns.clear();
    noLoreExcludedPatterns.clear();
    vanillaStatlessPatterns.clear();
    miscPatterns.clear();
    unenchantedPatterns.clear();
    notjunkPatterns.clear();
    newFormatOverridePatterns.clear();
    enchantExcludedItems.clear();

    try (InputStream is = ItemDefinitions.class.getResourceAsStream("/definitions.yml")) {
      if (is == null) {
        VetsLogger.warn("definitions.yml not found in resources");
        return;
      }
      parse(is);
      VetsLogger.debug(
          "Loaded {} legacy, {} no-lore-excluded, {} vanilla-statless, {} misc, {} unenchanted, {} notjunk, {} new-format-override, and {} enchant-excluded definition(s)",
          legacyPatterns.size(),
          noLoreExcludedPatterns.size(),
          vanillaStatlessPatterns.size(),
          miscPatterns.size(),
          unenchantedPatterns.size(),
          notjunkPatterns.size(),
          newFormatOverridePatterns.size(),
          enchantExcludedItems.size());
    } catch (IOException e) {
      VetsLogger.error("Failed to load definitions.yml", e);
    }
  }

  private static void parse(InputStream input) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String currentSection = null;
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

        if (trimmed.equals("---")) {
          currentSection = null;
          continue;
        }

        // Detect section headers (no leading whitespace, ends with colon)
        if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.endsWith(":")) {
          currentSection = trimmed.substring(0, trimmed.length() - 1);
          continue;
        }

        if (currentSection == null) continue;

        if ("definitions".equals(currentSection) && trimmed.startsWith("- ")) {
          String pattern = extractQuotedString(trimmed.substring(2).trim());
          legacyPatterns.add(Pattern.compile(pattern));
        } else if ("no_lore_excluded".equals(currentSection) && trimmed.startsWith("- ")) {
          String pattern = extractQuotedString(trimmed.substring(2).trim());
          noLoreExcludedPatterns.add(Pattern.compile(pattern));
        } else if ("vanilla_statless".equals(currentSection) && trimmed.startsWith("- ")) {
          String pattern = extractQuotedString(trimmed.substring(2).trim());
          vanillaStatlessPatterns.add(Pattern.compile(pattern));
        } else if ("misc_definitions".equals(currentSection) && trimmed.startsWith("- ")) {
          String pattern = extractQuotedString(trimmed.substring(2).trim());
          miscPatterns.add(Pattern.compile(pattern));
        } else if ("unenchanted".equals(currentSection) && trimmed.startsWith("- ")) {
          String pattern = extractQuotedString(trimmed.substring(2).trim());
          unenchantedPatterns.add(Pattern.compile(pattern));
        } else if ("notjunk".equals(currentSection) && trimmed.startsWith("- ")) {
          String pattern = extractQuotedString(trimmed.substring(2).trim());
          if (!pattern.isEmpty()) {
            notjunkPatterns.add(Pattern.compile(pattern));
          }
        } else if ("new_format_override".equals(currentSection) && trimmed.startsWith("- ")) {
          String pattern = extractQuotedString(trimmed.substring(2).trim());
          if (!pattern.isEmpty()) {
            newFormatOverridePatterns.add(Pattern.compile(pattern));
          }
        } else if ("enchant_excluded_items".equals(currentSection) && trimmed.startsWith("- ")) {
          String itemId = extractQuotedString(trimmed.substring(2).trim());
          if (!itemId.isEmpty()) {
            enchantExcludedItems.add(itemId);
          }
        }
      }
    }
  }

  private static String extractQuotedString(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  public static boolean isLegacy(String itemName) {
    for (Pattern pattern : legacyPatterns) {
      if (pattern.matcher(itemName).matches()) {
        return true;
      }
    }
    return false;
  }

  public static boolean isNoLoreExcluded(String itemName) {
    for (Pattern pattern : noLoreExcludedPatterns) {
      if (pattern.matcher(itemName).matches()) {
        return true;
      }
    }
    return false;
  }

  public static boolean isVanillaStatless(String itemName) {
    for (Pattern pattern : vanillaStatlessPatterns) {
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
   * Items matching these patterns should not be treated as legacy when they appear
   * in the new display format (PUA-encoded custom name).
   */
  public static boolean isNewFormatOverride(String itemName) {
    for (Pattern pattern : newFormatOverridePatterns) {
      if (pattern.matcher(itemName).matches()) {
        return true;
      }
    }
    return false;
  }
}
