package org.wynnvets.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.debug.diagnostics.DiagnosticsHandler;

/**
 * Builds the entire {@code /wv debug} command subtree.
 *
 * <p>This class owns all debug-related subcommands so that production code
 * in {@link org.wynnvets.VetsmodClient} only needs a single integration
 * point: {@link #buildCommandTree()}.</p>
 *
 * <h3>Subcommands</h3>
 * <ul>
 *   <li>{@code /wv debug} — diagnostics dump (delegated to {@link DiagnosticsHandler})</li>
 *   <li>{@code /wv debug true|false} — toggle debug logging</li>
 *   <li>{@code /wv debug set} — list debug config keys</li>
 *   <li>{@code /wv debug set <key>} — get value of a debug config key</li>
 *   <li>{@code /wv debug set <key> <value>} — set a debug config key</li>
 * </ul>
 */
public final class DebugCommands {

    private DebugCommands() {}

    /** Tab-completion provider that suggests debug-configurable key names. */
    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_DEBUG_CONFIG_KEYS =
        (ctx, builder) -> {
            String partial = builder.getRemaining().toLowerCase();
            for (String key : DebugConfigManager.DEBUG_CONFIG_KEYS) {
                if (key.toLowerCase().startsWith(partial)) {
                    builder.suggest(key);
                }
            }
            return builder.buildFuture();
        };

    /** Tab-completion provider that suggests "true" / "false". */
    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_BOOLEAN_VALUES =
        (ctx, builder) -> {
            String partial = builder.getRemaining().toLowerCase();
            if ("true".startsWith(partial)) builder.suggest("true");
            if ("false".startsWith(partial)) builder.suggest("false");
            return builder.buildFuture();
        };

    /**
     * Builds and returns the {@code debug} literal node that should be
     * appended to the {@code /wv} command tree via
     * {@code .then(DebugCommands.buildCommandTree())}.
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("debug")
            .executes(ctx -> { DiagnosticsHandler.execute(null); return 1; })
            .then(ClientCommandManager.argument("enabled", StringArgumentType.word())
                .executes(ctx -> {
                    DiagnosticsHandler.execute(StringArgumentType.getString(ctx, "enabled"));
                    return 1;
                })
            )
            .then(ClientCommandManager.literal("set")
                .executes(DebugCommands::debugConfigList)
                .then(ClientCommandManager.argument("key", StringArgumentType.word())
                    .suggests(SUGGEST_DEBUG_CONFIG_KEYS)
                    .executes(DebugCommands::debugConfigGet)
                    .then(ClientCommandManager.argument("value", StringArgumentType.word())
                        .suggests(SUGGEST_BOOLEAN_VALUES)
                        .executes(DebugCommands::debugConfigSet)
                    )
                )
            );
    }

    // ── /wv debug set handlers ──────────────────────────────────────

    /**
     * {@code /wv debug set} — lists all debug config keys and their current values.
     */
    private static int debugConfigList(CommandContext<FabricClientCommandSource> ctx) {
        MutableComponent header = Component.literal("VetsMod Debug Configuration:")
            .withStyle(ChatFormatting.GOLD);
        ChatUtils.sendLocalMessage(header);

        for (String key : DebugConfigManager.DEBUG_CONFIG_KEYS) {
            boolean value = VetsConfig.get(key);
            ChatUtils.sendLocalMessage(
                Component.literal("  " + key + " = ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(value))
                        .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
            );
        }
        return 1;
    }

    /**
     * {@code /wv debug set <key>} — displays the current value of a debug config key.
     */
    private static int debugConfigGet(CommandContext<FabricClientCommandSource> ctx) {
        String key = StringArgumentType.getString(ctx, "key");

        if (!DebugConfigManager.isDebugConfigKey(key)) {
            ChatUtils.sendLocalMessage(
                Component.literal("Unknown debug config key: " + key)
                    .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        boolean value = VetsConfig.get(key);
        ChatUtils.sendLocalMessage(
            Component.literal(key + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(value))
                    .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
        );
        return 1;
    }

    /**
     * {@code /wv debug set <key> <value>} — sets a debug boolean config key.
     */
    private static int debugConfigSet(CommandContext<FabricClientCommandSource> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String rawValue = StringArgumentType.getString(ctx, "value");

        if (!DebugConfigManager.isDebugConfigKey(key)) {
            ChatUtils.sendLocalMessage(
                Component.literal("Unknown debug config key: " + key)
                    .withStyle(ChatFormatting.RED)
            );
            return 0;
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
