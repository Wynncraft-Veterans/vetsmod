package org.wynnvets.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;

/**
 * Handlers and suggestion providers for the {@code /wv config} subcommand tree.
 *
 * <p>Each method is package-private so it can be referenced from
 * {@link CommandRegistry} without being part of the public API.</p>
 */
final class ConfigCommands {

  private ConfigCommands() {}

  // ── Suggestion providers ────────────────────────────────────────────

  static final SuggestionProvider<FabricClientCommandSource> SUGGEST_CONFIG_KEYS =
      (ctx, builder) -> {
        String partial = builder.getRemaining().toLowerCase();
        for (String key : VetsConfig.USER_CONFIG_KEYS) {
          if (key.toLowerCase().startsWith(partial)) {
            builder.suggest(key);
          }
        }
        return builder.buildFuture();
      };

  static final SuggestionProvider<FabricClientCommandSource> SUGGEST_CONFIG_VALUES =
      (ctx, builder) -> {
        String partial = builder.getRemaining().toLowerCase();
        try {
          String key = StringArgumentType.getString(ctx, "key");
          if (VetsConfig.isIntKey(key)) {
            if ("reset".startsWith(partial)) builder.suggest("reset");
            for (String v : new String[]{"0", "25", "50", "69", "75", "100"}) {
              if (v.startsWith(partial)) builder.suggest(v);
            }
            return builder.buildFuture();
          }
          if (VetsConfig.isStringKey(key)) {
            // Always suggest "reset" for string keys
            if ("reset".startsWith(partial)) builder.suggest("reset");
            if (key.equals(VetsConfig.LEGACY_ITEM_FOREGROUND_SPRITE)) {
              for (String s : VetsConfig.VALID_SPRITES) {
                if (s.startsWith(partial)) builder.suggest(s);
              }
            } else if (key.equals(VetsConfig.VETS_ANNI_ROLE_STYLE)) {
              for (String s : VetsConfig.VALID_ROLE_STYLES) {
                if (s.startsWith(partial)) builder.suggest(s);
              }
            } else if (key.equals(VetsConfig.VETS_ANNI_MODE)) {
              for (String s : VetsConfig.VALID_ANNI_MODES) {
                if (s.startsWith(partial)) builder.suggest(s);
              }
            } else {
              // Colour name keys (gradient top/bottom, foreground color)
              for (String name : VetsConfig.getColorNames()) {
                if (name.startsWith(partial)) builder.suggest(name);
              }
            }
            return builder.buildFuture();
          }
          if (VetsConfig.isTriStateKey(key) && "default".startsWith(partial)) {
            builder.suggest("default");
          }
        } catch (IllegalArgumentException ignored) {
          // Key argument not yet entered
        }
        if ("true".startsWith(partial)) builder.suggest("true");
        if ("false".startsWith(partial)) builder.suggest("false");
        return builder.buildFuture();
      };

  // ── Command handlers ────────────────────────────────────────────────

  static int configList(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent header = Component.literal("Configuration:")
        .withStyle(ChatFormatting.GOLD);
    ChatUtils.sendLocalMessageNewBlock(header);

    for (String key : VetsConfig.USER_CONFIG_KEYS) {
      if (VetsConfig.isIntKey(key)) {
        long intValue = VetsConfig.getLong(key);
        ChatUtils.sendLocalMessage(
            Component.literal("  " + key + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(intValue))
                    .withStyle(ChatFormatting.AQUA))
        );
      } else if (VetsConfig.isStringKey(key)) {
        String strValue = VetsConfig.getString(key);
        ChatUtils.sendLocalMessage(
            Component.literal("  " + key + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(formatStringConfigValue(key, strValue))
        );
      } else if (VetsConfig.isTriStateKey(key)) {
        Boolean triValue = VetsConfig.getTriState(key);
        String display = triValue == null ? "default" : String.valueOf(triValue);
        ChatFormatting color = triValue == null ? ChatFormatting.YELLOW
            : (triValue ? ChatFormatting.GREEN : ChatFormatting.RED);
        ChatUtils.sendLocalMessage(
            Component.literal("  " + key + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(display).withStyle(color))
        );
      } else {
        boolean value = VetsConfig.get(key);
        ChatUtils.sendLocalMessage(
            Component.literal("  " + key + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(value))
                    .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
        );
      }
    }
    return 1;
  }

  static int configGet(CommandContext<FabricClientCommandSource> ctx) {
    String key = StringArgumentType.getString(ctx, "key");

    if (!VetsConfig.isUserConfigKey(key)) {
      ChatUtils.sendLocalMessage(
          Component.literal("Unknown config key: " + key)
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    if (VetsConfig.isIntKey(key)) {
      long intValue = VetsConfig.getLong(key);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " = ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(String.valueOf(intValue))
                  .withStyle(ChatFormatting.AQUA))
      );
    } else if (VetsConfig.isStringKey(key)) {
      String strValue = VetsConfig.getString(key);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " = ")
              .withStyle(ChatFormatting.GRAY)
              .append(formatStringConfigValue(key, strValue))
      );
    } else if (VetsConfig.isTriStateKey(key)) {
      Boolean triValue = VetsConfig.getTriState(key);
      String display = triValue == null ? "default" : String.valueOf(triValue);
      ChatFormatting color = triValue == null ? ChatFormatting.YELLOW
          : (triValue ? ChatFormatting.GREEN : ChatFormatting.RED);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " = ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(display).withStyle(color))
      );
    } else {
      boolean value = VetsConfig.get(key);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " = ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(String.valueOf(value))
                  .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
      );
    }
    return 1;
  }

  static int configSet(CommandContext<FabricClientCommandSource> ctx) {
    String key = StringArgumentType.getString(ctx, "key");
    String rawValue = StringArgumentType.getString(ctx, "value");

    if (!VetsConfig.isUserConfigKey(key)) {
      ChatUtils.sendLocalMessage(
          Component.literal("Unknown config key: " + key)
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    if (VetsConfig.isIntKey(key)) {
      return handleIntConfigSet(key, rawValue);
    }

    if (VetsConfig.isStringKey(key)) {
      return handleStringConfigSet(key, rawValue);
    }

    if (VetsConfig.isTriStateKey(key)) {
      return handleTriStateConfigSet(key, rawValue);
    }

    if (!"true".equalsIgnoreCase(rawValue) && !"false".equalsIgnoreCase(rawValue)) {
      ChatUtils.sendLocalMessage(
          Component.literal("Value must be 'true' or 'false'.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }

    boolean value = Boolean.parseBoolean(rawValue);
    VetsConfig.set(key, value);

    ChatUtils.sendLocalMessage(
        Component.literal(key + " set to ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(value))
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
    );
    return 1;
  }

  // ── TriState-config helpers ─────────────────────────────────────────

  private static int handleTriStateConfigSet(String key, String rawValue) {
    Boolean triValue;
    if ("default".equalsIgnoreCase(rawValue)) {
      triValue = null;
    } else if ("true".equalsIgnoreCase(rawValue)) {
      triValue = Boolean.TRUE;
    } else if ("false".equalsIgnoreCase(rawValue)) {
      triValue = Boolean.FALSE;
    } else {
      ChatUtils.sendLocalMessage(
          Component.literal("Value must be 'true', 'false', or 'default'.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }
    VetsConfig.setTriState(key, triValue);
    String display = triValue == null ? "default" : String.valueOf(triValue);
    ChatFormatting color = triValue == null ? ChatFormatting.YELLOW
        : (triValue ? ChatFormatting.GREEN : ChatFormatting.RED);
    ChatUtils.sendLocalMessage(
        Component.literal(key + " set to ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(display).withStyle(color))
    );
    return 1;
  }

  // ── String-config helpers ───────────────────────────────────────────

  private static Component formatStringConfigValue(String key, String value) {
    if (key.equals(VetsConfig.LEGACY_ITEM_FOREGROUND_SPRITE)) {
      return Component.literal(value).withStyle(ChatFormatting.AQUA);
    }
    // All other string keys are colour names
    int rgb = VetsConfig.getColorRgb(value);
    return Component.literal(value)
        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
  }

  private static int handleStringConfigSet(String key, String rawValue) {
    // Handle reset for all string keys
    if ("reset".equalsIgnoreCase(rawValue)) {
      String defaultVal = VetsConfig.getStringDefault(key);
      if (defaultVal == null) return 0;
      VetsConfig.setString(key, defaultVal);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " reset to ")
              .withStyle(ChatFormatting.GRAY)
              .append(formatStringConfigValue(key, defaultVal))
      );
      return 1;
    }

    if (key.equals(VetsConfig.LEGACY_ITEM_FOREGROUND_SPRITE)) {
      String lower = rawValue.toLowerCase();
      if (!VetsConfig.isValidSprite(lower)) {
        ChatUtils.sendLocalMessage(
            Component.literal("Unknown sprite. Valid options: "
                + String.join(", ", VetsConfig.VALID_SPRITES))
                .withStyle(ChatFormatting.RED)
        );
        return 0;
      }
      VetsConfig.setString(key, lower);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " set to ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(lower).withStyle(ChatFormatting.AQUA))
      );
      return 1;
    }

    // Colour name keys (gradient top/bottom, foreground color)
    String lower = rawValue.toLowerCase();
    if (!VetsConfig.isValidColor(lower)) {
      ChatUtils.sendLocalMessage(
          Component.literal("Unknown colour name. Tab-complete to see options.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }
    VetsConfig.setString(key, lower);
    ChatUtils.sendLocalMessage(
        Component.literal(key + " set to ")
            .withStyle(ChatFormatting.GRAY)
            .append(formatStringConfigValue(key, lower))
    );
    return 1;
  }

  // ── Int-config helpers ──────────────────────────────────────────────

  private static int handleIntConfigSet(String key, String rawValue) {
    if ("reset".equalsIgnoreCase(rawValue)) {
      Long defaultVal = VetsConfig.getIntDefault(key);
      if (defaultVal == null) return 0;
      VetsConfig.setLong(key, defaultVal);
      ChatUtils.sendLocalMessage(
          Component.literal(key + " reset to ")
              .withStyle(ChatFormatting.GRAY)
              .append(Component.literal(String.valueOf(defaultVal))
                  .withStyle(ChatFormatting.AQUA))
      );
      return 1;
    }

    long parsed;
    try {
      parsed = Long.parseLong(rawValue);
    } catch (NumberFormatException e) {
      ChatUtils.sendLocalMessage(
          Component.literal("Value must be a number (0\u2013100) or 'reset'.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }
    if (parsed < 0 || parsed > 100) {
      ChatUtils.sendLocalMessage(
          Component.literal("Value must be between 0 and 100.")
              .withStyle(ChatFormatting.RED)
      );
      return 0;
    }
    VetsConfig.setLong(key, parsed);
    ChatUtils.sendLocalMessage(
        Component.literal(key + " set to ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(parsed))
                .withStyle(ChatFormatting.AQUA))
    );
    return 1;
  }
}
