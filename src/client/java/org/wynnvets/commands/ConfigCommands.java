package org.wynnvets.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.LegacyItemStyle;
import org.wynnvets.config.ShippedConfig;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.config.VetsConfigKeys;
import org.wynnvets.config.VetsConfigSchema;
import org.wynnvets.util.ConfigValueText;
import org.wynnvets.util.ConfigValueText.Form;
import org.wynnvets.util.ConfigValueText.Verb;

/**
 * Handlers and suggestion providers for the {@code /wv config} subcommand tree.
 *
 * <p>The command handlers and suggestion providers that {@link CommandRegistry}
 * wires into the tree are package-private, so it can reference them without
 * their being part of the public API; everything else here, the
 * {@code handle*ConfigSet} helpers included, is private.</p>
 */
final class ConfigCommands {

    private ConfigCommands() {}

    // ── Suggestion providers ────────────────────────────────────────────

    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_CONFIG_KEYS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String key : VetsConfigSchema.USER_CONFIG_KEYS) {
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
                    if (VetsConfigSchema.isIntKey(key)) {
                        // default, and reset as its older alias
                        if ("default".startsWith(partial)) builder.suggest("default");
                        if ("reset".startsWith(partial)) builder.suggest("reset");
                        for (String v : new String[] {"0", "25", "50", "69", "75", "100"}) {
                            if (v.startsWith(partial)) builder.suggest(v);
                        }
                        return builder.buildFuture();
                    }
                    if (VetsConfigSchema.isStringKey(key)) {
                        // default, and reset as its older alias
                        if ("default".startsWith(partial)) builder.suggest("default");
                        if ("reset".startsWith(partial)) builder.suggest("reset");
                        String[] fixed = VetsConfigSchema.fixedValuesOf(key);
                        if (key.equals(VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE)) {
                            for (String s : VetsConfigSchema.VALID_SPRITES) {
                                if (s.startsWith(partial)) builder.suggest(s);
                            }
                        } else if (fixed != null) {
                            // Role style and flash intensity
                            for (String s : fixed) {
                                if (s.startsWith(partial)) builder.suggest(s);
                            }
                        } else {
                            // Colour name keys (gradient top/bottom, foreground color)
                            for (String name : LegacyItemStyle.getColorNames()) {
                                if (name.startsWith(partial)) builder.suggest(name);
                            }
                        }
                        return builder.buildFuture();
                    }
                    // Boolean and tri-state keys take default too
                    if (VetsConfigSchema.isUserConfigKey(key) && "default".startsWith(partial)) {
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

    /**
     * Which of {@link VetsConfig}'s four typed accessors a key is read and
     * written through.
     *
     * <p>The ladder below was written three times — once to list every key, once
     * to read one back, once to dispatch a write. Its <em>order</em> is the
     * behaviour: the predicates are asked in sequence and the first match wins,
     * so a key answering to more than one would resolve differently under a
     * different order. Writing it once is what stops the three drifting apart.</p>
     */
    private enum Kind {
        INT,
        STRING,
        TRI_STATE,
        BOOLEAN;

        static Kind of(String key) {
            if (VetsConfigSchema.isIntKey(key)) return INT;
            if (VetsConfigSchema.isStringKey(key)) return STRING;
            if (VetsConfigSchema.isTriStateKey(key)) return TRI_STATE;
            return BOOLEAN;
        }
    }

    /**
     * The {@code key = value} line for a key's current value, read through
     * whichever accessor its {@link Kind} calls for.
     *
     * <p>{@code form} is the only difference between the listing and the
     * read-back: {@link Form#LIST} indents, {@link Form#SINGLE} does not.</p>
     */
    private static Component currentValueLine(Form form, String key) {
        return switch (Kind.of(key)) {
            case INT -> ConfigValueText.intLine(form, key, Verb.EQUALS, VetsConfig.getLong(key));
            case STRING ->
                    ConfigValueText.stringLine(
                            form,
                            key,
                            Verb.EQUALS,
                            formatStringConfigValue(key, VetsConfig.getString(key)));
            case TRI_STATE ->
                    ConfigValueText.triStateLine(
                            form, key, Verb.EQUALS, VetsConfig.getTriState(key));
            case BOOLEAN ->
                    ConfigValueText.booleanLine(form, key, Verb.EQUALS, VetsConfig.get(key));
        };
    }

    static int configList(CommandContext<FabricClientCommandSource> ctx) {
        MutableComponent header =
                Component.literal("Configuration:").withStyle(ChatFormatting.GOLD);
        ChatUtils.sendLocalMessageNewBlock(header);

        // USER_CONFIG_KEYS' order is the order these print in, which
        // .claude/vetsmod_config.md states as fact. Do not sort here.
        for (String key : VetsConfigSchema.USER_CONFIG_KEYS) {
            ChatUtils.sendLocalMessage(currentValueLine(Form.LIST, key));
        }
        return 1;
    }

    static int configGet(CommandContext<FabricClientCommandSource> ctx) {
        String key = StringArgumentType.getString(ctx, "key");

        if (!VetsConfigSchema.isUserConfigKey(key)) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Unknown config key: " + key).withStyle(ChatFormatting.RED));
            return 0;
        }

        ChatUtils.sendLocalMessage(currentValueLine(Form.SINGLE, key));
        return 1;
    }

    static int configSet(CommandContext<FabricClientCommandSource> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String rawValue = StringArgumentType.getString(ctx, "value");

        if (!VetsConfigSchema.isUserConfigKey(key)) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Unknown config key: " + key).withStyle(ChatFormatting.RED));
            return 0;
        }

        return switch (Kind.of(key)) {
            case INT -> handleIntConfigSet(key, rawValue);
            case STRING -> handleStringConfigSet(key, rawValue);
            case TRI_STATE -> handleTriStateConfigSet(key, rawValue);
            case BOOLEAN -> handleBooleanConfigSet(key, rawValue);
        };
    }

    // ── Boolean-config helpers ──────────────────────────────────────────

    private static int handleBooleanConfigSet(String key, String rawValue) {
        if ("default".equalsIgnoreCase(rawValue)) {
            Boolean defaultVal = ShippedConfig.bool(key);
            if (defaultVal == null) return noShippedDefault(key);
            VetsConfig.set(key, defaultVal);
            ChatUtils.sendLocalMessage(
                    ConfigValueText.booleanLine(Form.SINGLE, key, Verb.RESET_TO, defaultVal));
            return 1;
        }
        if (!"true".equalsIgnoreCase(rawValue) && !"false".equalsIgnoreCase(rawValue)) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Value must be 'true', 'false', or 'default'.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean value = Boolean.parseBoolean(rawValue);
        VetsConfig.set(key, value);

        ChatUtils.sendLocalMessage(
                ConfigValueText.booleanLine(Form.SINGLE, key, Verb.SET_TO, value));
        return 1;
    }

    /**
     * The refusal for {@code default} on a key that vetsmod's shipped {@code config.yml} has no
     * value of the key's kind for. That happens only when the file is missing or unreadable, or a
     * key was added without a default.
     */
    private static int noShippedDefault(String key) {
        ChatUtils.sendLocalMessage(
                Component.literal("There is no shipped default for " + key + ".")
                        .withStyle(ChatFormatting.RED));
        return 0;
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
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        VetsConfig.setTriState(key, triValue);
        ChatUtils.sendLocalMessage(
                ConfigValueText.triStateLine(Form.SINGLE, key, Verb.SET_TO, triValue));
        return 1;
    }

    // ── String-config helpers ───────────────────────────────────────────

    private static Component formatStringConfigValue(String key, String value) {
        if (key.equals(VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE)
                || VetsConfigSchema.fixedValuesOf(key) != null) {
            return Component.literal(value).withStyle(ChatFormatting.AQUA);
        }
        // The other string keys are colour names, shown in their own colour.
        int rgb = LegacyItemStyle.getColorRgb(value);
        return Component.literal(value).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    private static int handleStringConfigSet(String key, String rawValue) {
        // default restores the shipped value for every string key; reset is its older alias.
        if ("default".equalsIgnoreCase(rawValue) || "reset".equalsIgnoreCase(rawValue)) {
            String defaultVal = ShippedConfig.string(key);
            if (defaultVal == null) return noShippedDefault(key);
            VetsConfig.setString(key, defaultVal);
            ChatUtils.sendLocalMessage(
                    ConfigValueText.stringLine(
                            Form.SINGLE,
                            key,
                            Verb.RESET_TO,
                            formatStringConfigValue(key, defaultVal)));
            return 1;
        }

        if (key.equals(VetsConfigKeys.LEGACY_ITEM_FOREGROUND_SPRITE)) {
            String lower = rawValue.toLowerCase();
            if (!LegacyItemStyle.isValidSprite(lower)) {
                ChatUtils.sendLocalMessage(
                        Component.literal(
                                        "Unknown sprite. Valid options: "
                                                + String.join(", ", VetsConfigSchema.VALID_SPRITES))
                                .withStyle(ChatFormatting.RED));
                return 0;
            }
            VetsConfig.setString(key, lower);
            ChatUtils.sendLocalMessage(
                    ConfigValueText.stringLine(
                            Form.SINGLE, key, Verb.SET_TO, formatStringConfigValue(key, lower)));
            return 1;
        }

        String[] fixed = VetsConfigSchema.fixedValuesOf(key);
        String lower;
        if (fixed != null) {
            // Role style and flash intensity. Locale.ROOT, so a Turkish locale still folds
            // DESCRIPTIVE to descriptive.
            lower = rawValue.toLowerCase(Locale.ROOT);
            if (!VetsConfigSchema.isFixedValue(key, lower)) {
                ChatUtils.sendLocalMessage(
                        Component.literal(
                                        "Unknown value for "
                                                + key
                                                + ". Valid options: "
                                                + String.join(", ", fixed))
                                .withStyle(ChatFormatting.RED));
                return 0;
            }
        } else {
            // Colour name keys (gradient top/bottom, foreground color)
            lower = rawValue.toLowerCase();
            if (!LegacyItemStyle.isValidColor(lower)) {
                ChatUtils.sendLocalMessage(
                        Component.literal("Unknown colour name. Tab-complete to see options.")
                                .withStyle(ChatFormatting.RED));
                return 0;
            }
        }
        VetsConfig.setString(key, lower);
        ChatUtils.sendLocalMessage(
                ConfigValueText.stringLine(
                        Form.SINGLE, key, Verb.SET_TO, formatStringConfigValue(key, lower)));
        return 1;
    }

    // ── Int-config helpers ──────────────────────────────────────────────

    private static int handleIntConfigSet(String key, String rawValue) {
        // default restores the shipped value; reset is its older alias.
        if ("default".equalsIgnoreCase(rawValue) || "reset".equalsIgnoreCase(rawValue)) {
            Long defaultVal = ShippedConfig.number(key);
            if (defaultVal == null) return noShippedDefault(key);
            VetsConfig.setLong(key, defaultVal);
            ChatUtils.sendLocalMessage(
                    ConfigValueText.intLine(Form.SINGLE, key, Verb.RESET_TO, defaultVal));
            return 1;
        }

        long parsed;
        try {
            parsed = Long.parseLong(rawValue);
        } catch (NumberFormatException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Value must be a number (0\u2013100) or 'default'.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (parsed < 0 || parsed > 100) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Value must be between 0 and 100.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        VetsConfig.setLong(key, parsed);
        ChatUtils.sendLocalMessage(ConfigValueText.intLine(Form.SINGLE, key, Verb.SET_TO, parsed));
        return 1;
    }
}
