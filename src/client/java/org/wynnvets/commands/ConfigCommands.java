package org.wynnvets.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
        if ("true".startsWith(partial)) builder.suggest("true");
        if ("false".startsWith(partial)) builder.suggest("false");
        try {
          String key = StringArgumentType.getString(ctx, "key");
          if (VetsConfig.isTriStateKey(key) && "default".startsWith(partial)) {
            builder.suggest("default");
          }
        } catch (IllegalArgumentException ignored) {
          // Key argument not yet entered
        }
        return builder.buildFuture();
      };

  // ── Command handlers ────────────────────────────────────────────────

  static int configList(CommandContext<FabricClientCommandSource> ctx) {
    MutableComponent header = Component.literal("VetsMod Configuration:")
        .withStyle(ChatFormatting.GOLD);
    ChatUtils.sendLocalMessage(header);

    for (String key : VetsConfig.USER_CONFIG_KEYS) {
      if (VetsConfig.isTriStateKey(key)) {
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

    if (VetsConfig.isTriStateKey(key)) {
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

    if (VetsConfig.isTriStateKey(key)) {
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
}
