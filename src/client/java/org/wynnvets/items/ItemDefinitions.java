package org.wynnvets.items;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.wynnvets.Vetsmod;

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
  private static final List<Pattern> miscPatterns = new ArrayList<>();
  private static final List<Pattern> unenchantedPatterns = new ArrayList<>();
  private static final List<Pattern> notjunkPatterns = new ArrayList<>();

  public static void load() {
    legacyPatterns.clear();
    miscPatterns.clear();
    unenchantedPatterns.clear();
    notjunkPatterns.clear();

    try (InputStream is = ItemDefinitions.class.getResourceAsStream("/definitions.yml")) {
      if (is == null) {
        Vetsmod.LOGGER.warn("definitions.yml not found in resources");
        return;
      }
      parse(is);
      Vetsmod.LOGGER.info(
          "Loaded {} legacy, {} misc, {} unenchanted, and {} notjunk definition(s)",
          legacyPatterns.size(),
          miscPatterns.size(),
          unenchantedPatterns.size(),
          notjunkPatterns.size());
    } catch (IOException e) {
      Vetsmod.LOGGER.error("Failed to load definitions.yml", e);
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

  public static boolean isNotJunk(String itemName) {
    for (Pattern pattern : notjunkPatterns) {
      if (pattern.matcher(itemName).matches()) {
        return true;
      }
    }
    return false;
  }
}
