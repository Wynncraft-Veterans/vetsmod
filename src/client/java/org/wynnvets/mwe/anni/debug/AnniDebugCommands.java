package org.wynnvets.mwe.anni.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * {@code /wv debug anni …} — simulation hooks for the MWE/anni subsystem.
 *
 * <p>Anni is infrequent and hard to test against live events; these commands
 * let a developer reproduce every render branch without waiting for a real
 * anni cycle. Gated by {@link VetsLogger#isDebugEnabled()} (not the
 * {@code vetsAnniEnabled} master toggle) so the whole subsystem stays
 * testable while still being accidentally-safe (debug logging is off by
 * default, expires after 3 days, and is opt-in via {@code /wv debug true}).</p>
 *
 * <p>S1 implements the cache-facing subcommands ({@code snapshot inject},
 * {@code snapshot dump}). The boss-bar / outline / mode / zone hooks
 * referenced by the plan ({@code fastforward}, {@code zone},
 * {@code flash}, {@code mode set}) are scaffolded as placeholder branches
 * so the command tree's tab completion is complete from S1 onward — they
 * print "(S3+ — not yet wired)" and become functional as later stages
 * land their consumers.</p>
 */
public final class AnniDebugCommands {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Directory under the game dir where {@code snapshot dump} writes
     *  fixtures (and {@code snapshot inject} can load them from in a
     *  future iteration). Created on first dump. */
    private static final Path SNAPSHOTS_DIR = FabricLoader.getInstance()
            .getGameDir().resolve("vetsmod/storage/anni_test_snapshots");

    private static final DateTimeFormatter DUMP_TS = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_MODES =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String mode : new String[] {"silent", "passive", "aggressive"}) {
                    if (mode.startsWith(partial)) {
                        builder.suggest(mode);
                    }
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_ZONE_ACTIONS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String action : new String[] {"enter", "exit"}) {
                    if (action.startsWith(partial)) {
                        builder.suggest(action);
                    }
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_FLASH_FIELDS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String field : new String[] {"role", "world", "party", "rsvp"}) {
                    if (field.startsWith(partial)) {
                        builder.suggest(field);
                    }
                }
                return builder.buildFuture();
            };

    private AnniDebugCommands() {
    }

    /** Append the {@code anni} literal to the {@code /wv debug} tree.
     *  Caller is {@code DebugCommands#buildCommandTree()}. */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("anni")
                .then(ClientCommandManager.literal("snapshot")
                        .then(ClientCommandManager.literal("inject")
                                .then(ClientCommandManager.argument("json",
                                                StringArgumentType.greedyString())
                                        .executes(AnniDebugCommands::snapshotInject)))
                        .then(ClientCommandManager.literal("dump")
                                .executes(AnniDebugCommands::snapshotDump)))
                .then(ClientCommandManager.literal("fastforward")
                        .then(ClientCommandManager.argument("seconds",
                                        StringArgumentType.word())
                                .executes(AnniDebugCommands::fastforward)))
                .then(ClientCommandManager.literal("zone")
                        .then(ClientCommandManager.argument("action",
                                        StringArgumentType.word())
                                .suggests(SUGGEST_ZONE_ACTIONS)
                                .executes(AnniDebugCommands::zone)))
                .then(ClientCommandManager.literal("flash")
                        .then(ClientCommandManager.argument("field",
                                        StringArgumentType.word())
                                .suggests(SUGGEST_FLASH_FIELDS)
                                .executes(AnniDebugCommands::flash)))
                .then(ClientCommandManager.literal("mode")
                        .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("mode",
                                                StringArgumentType.word())
                                        .suggests(SUGGEST_MODES)
                                        .executes(AnniDebugCommands::modeSet))));
    }

    // ──────────────────────────────────────────────────────────── gating

    /** Print a "debug logging required" notice and return 0; used by every
     *  command in this tree to short-circuit when debug logging is off. */
    private static int requireDebug(CommandContext<FabricClientCommandSource> ctx) {
        if (VetsLogger.isDebugEnabled()) {
            return -1;  // sentinel: caller proceeds
        }
        ChatUtils.sendLocalMessage(
                Component.literal("anni debug commands require debug logging — run /wv debug true first")
                        .withStyle(ChatFormatting.RED));
        return 0;
    }

    // ───────────────────────────────────────── snapshot inject / dump

    private static int snapshotInject(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;

        String raw = StringArgumentType.getString(ctx, "json");
        JsonObject json;
        try {
            json = JsonParser.parseString(raw).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject: invalid JSON: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        AnniSnapshot snapshot;
        try {
            snapshot = AnniSnapshot.fromJson(json);
        } catch (Exception e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject: parse failed: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        AnniSnapshotCache.update(snapshot);
        ChatUtils.sendLocalMessage(
                Component.literal("anni snapshot injected (schema_version="
                        + snapshot.schemaVersion()
                        + ", mc_uuid=" + (snapshot.mcUuid() != null ? snapshot.mcUuid() : "<null>") + ")")
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int snapshotDump(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;

        AnniSnapshot snapshot = AnniSnapshotCache.latest();
        if (snapshot == null) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot dump: cache is empty")
                            .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        try {
            Files.createDirectories(SNAPSHOTS_DIR);
        } catch (IOException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot dump: mkdir failed: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        String name = "snapshot-" + DUMP_TS.format(Instant.now()) + ".json";
        Path target = SNAPSHOTS_DIR.resolve(name);
        try {
            Files.writeString(target, GSON.toJson(snapshot));
        } catch (IOException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot dump: write failed: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        ChatUtils.sendLocalMessage(
                Component.literal("anni snapshot dumped to " + target)
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // ───────────────────────────────────────── S3+ placeholders

    private static int fastforward(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String seconds = StringArgumentType.getString(ctx, "seconds");
        ChatUtils.sendLocalMessage(
                Component.literal("anni fastforward " + seconds
                        + ": registered intent (S3+ — boss bar consumer not yet wired)")
                        .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int zone(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String action = StringArgumentType.getString(ctx, "action");
        if (!"enter".equalsIgnoreCase(action) && !"exit".equalsIgnoreCase(action)) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni zone: action must be enter or exit")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        ChatUtils.sendLocalMessage(
                Component.literal("anni zone " + action.toLowerCase()
                        + ": registered intent (S4+ — zone consumer not yet wired)")
                        .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int flash(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String field = StringArgumentType.getString(ctx, "field");
        ChatUtils.sendLocalMessage(
                Component.literal("anni flash " + field
                        + ": registered intent (S3+ — FlashTracker not yet wired)")
                        .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int modeSet(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
        if (!"silent".equals(mode) && !"passive".equals(mode) && !"aggressive".equals(mode)) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni mode set: mode must be silent|passive|aggressive")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        ChatUtils.sendLocalMessage(
                Component.literal("anni mode set " + mode
                        + ": registered intent (S3+ — AnniModeManager not yet wired)")
                        .withStyle(ChatFormatting.YELLOW));
        return 1;
    }
}
