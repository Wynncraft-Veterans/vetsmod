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
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import org.wynnvets.chat.ChatUtils;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

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

    // serializeNulls so dumps are byte-equivalent to the wire format
    // (server-side python json.dumps emits explicit nulls). Lets `inject
    // file` round-trip a dump and lets a human-eye diff against the
    // canonical /api/internal/anni-player/{uuid} response succeed.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    /** Directory under the game dir where {@code snapshot dump} writes
     *  fixtures and {@code snapshot inject file} reads them. Created on
     *  first dump. Sibling of {@code vetsmod/dumps/items/} —
     *  {@code vetsmod/dumps/} is the shared root for every dump
     *  artifact regardless of producer, organised per-type. */
    private static final Path SNAPSHOTS_DIR = FabricLoader.getInstance()
            .getGameDir().resolve("vetsmod/dumps/anni");

    private static final DateTimeFormatter DUMP_TS = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    /** Classpath root for the bundled snapshot fixtures. Resources land here
     *  via {@code src/client/resources/assets/vetsmod/anni_test_snapshots/}
     *  and are loadable through {@link Class#getResourceAsStream(String)}. */
    private static final String PRESET_CLASSPATH_PREFIX =
            "/assets/vetsmod/anni_test_snapshots/";

    /** Names (no extension) of every bundled preset. Hard-coded for tab
     *  completion — the jar's classpath isn't trivially enumerable from
     *  inside the running mod, and the set rarely changes. Keep in sync
     *  with the files actually shipped under
     *  {@link #PRESET_CLASSPATH_PREFIX}. */
    private static final String[] PRESETS = {
            "empty",
            "external_no_anni",
            "member_announced",
            "member_in_party",
    };

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

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_DUMP_FILES =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                if (!Files.isDirectory(SNAPSHOTS_DIR)) {
                    return builder.buildFuture();
                }
                try (Stream<Path> stream = Files.list(SNAPSHOTS_DIR)) {
                    stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> {
                            String n = p.getFileName().toString();
                            return n.substring(0, n.length() - ".json".length());
                        })
                        .filter(n -> n.toLowerCase().startsWith(partial))
                        .sorted()
                        .forEach(builder::suggest);
                } catch (IOException ignored) {
                    // Fall through with whatever we've collected so far.
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_PRESETS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String name : PRESETS) {
                    if (name.startsWith(partial)) {
                        builder.suggest(name);
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
                                // Literal branches are tried before the
                                // greedyString fallback, so `inject file …`
                                // and `inject preset …` resolve cleanly even
                                // though the inline form accepts anything.
                                .then(ClientCommandManager.literal("file")
                                        .then(ClientCommandManager.argument("name",
                                                        StringArgumentType.word())
                                                .suggests(SUGGEST_DUMP_FILES)
                                                .executes(AnniDebugCommands::snapshotInjectFile)))
                                .then(ClientCommandManager.literal("preset")
                                        .then(ClientCommandManager.argument("name",
                                                        StringArgumentType.word())
                                                .suggests(SUGGEST_PRESETS)
                                                .executes(AnniDebugCommands::snapshotInjectPreset)))
                                .then(ClientCommandManager.argument("json",
                                                StringArgumentType.greedyString())
                                        .executes(AnniDebugCommands::snapshotInject)))
                        .then(ClientCommandManager.literal("dump")
                                .executes(AnniDebugCommands::snapshotDump))
                        .then(ClientCommandManager.literal("clear")
                                .executes(AnniDebugCommands::snapshotClear))
                        .then(ClientCommandManager.literal("refresh")
                                .executes(AnniDebugCommands::snapshotRefresh)))
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
        return parseAndInject(raw, "inline");
    }

    private static int snapshotInjectFile(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;

        String name = StringArgumentType.getString(ctx, "name");
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        Path target = SNAPSHOTS_DIR.resolve(name + ".json");
        if (!Files.isRegularFile(target)) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject file: not found: " + target)
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        String raw;
        try {
            raw = Files.readString(target);
        } catch (IOException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject file: read failed: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        return parseAndInject(raw, "file:" + name);
    }

    private static int snapshotInjectPreset(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;

        String name = StringArgumentType.getString(ctx, "name");
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        String resourcePath = PRESET_CLASSPATH_PREFIX + name + ".json";
        String raw;
        try (InputStream in = AnniDebugCommands.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                ChatUtils.sendLocalMessage(
                        Component.literal("anni snapshot inject preset: unknown preset '" + name + "'")
                                .withStyle(ChatFormatting.RED));
                return 0;
            }
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject preset: read failed: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        return parseAndInject(raw, "preset:" + name);
    }

    /** Parse {@code raw} as a snapshot JSON, push into the cache, and
     *  report. Returns 1 on success, 0 on parse error (after surfacing
     *  the error to chat). {@code source} is a short tag like "inline",
     *  "file:foo", or "preset:foo" — included in the success line so the
     *  user can tell which path actually ran. */
    private static int parseAndInject(String raw, String source) {
        JsonObject json;
        try {
            json = JsonParser.parseString(raw).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject (" + source
                            + "): invalid JSON: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        AnniSnapshot snapshot;
        try {
            snapshot = AnniSnapshot.fromJson(json);
        } catch (Exception e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject (" + source
                            + "): parse failed: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        AnniSnapshotCache.update(snapshot);
        ChatUtils.sendLocalMessage(
                Component.literal("anni snapshot injected from " + source
                        + " (schema_version=" + snapshot.schemaVersion()
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

    /** {@code /wv debug anni snapshot clear} — synchronous wipe.
     *
     *  <p>Pushes {@code null} into the cache so subscribed surfaces
     *  re-render in their no-snapshot state. Push frames from temp-server
     *  only fire on a diff against temp-server's own cache, so clearing
     *  the local cache does NOT trigger a re-push — your cache stays
     *  empty until either the actual data changes server-side or you
     *  follow up with {@code refresh}. Use this command to test the
     *  no-snapshot rendering branch; use {@code refresh} to undo
     *  synthetic injects and re-pull real data.</p> */
    private static int snapshotClear(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        AnniSnapshotCache.update(null);
        ChatUtils.sendLocalMessage(
                Component.literal("anni snapshot cleared (cache is null; "
                        + "run `/wv debug anni snapshot refresh` to re-pull, "
                        + "or wait for server-side changes)")
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug anni snapshot refresh} — async re-fetch.
     *
     *  <p>Fires an {@code anni_query} via {@link AnniQueryClient#query()}.
     *  The query client's response handler already pushes successful
     *  results through {@link AnniSnapshotCache#update(AnniSnapshot)}, so
     *  this command only needs to issue the request and report when the
     *  future completes. The completion callback bounces onto the main
     *  thread via {@link Minecraft#execute(Runnable)} — futures from
     *  {@link AnniQueryClient} fire on the WS reader thread, which is not
     *  safe for chat dispatch.</p>
     *
     *  <p>Pairs with the synthetic-injection paths as the "undo" knob:
     *  inject any preset/file/inline snapshot to test rendering, then
     *  {@code refresh} to wipe the synthetic data and re-pull the real
     *  state from the server.</p> */
    private static int snapshotRefresh(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;

        ChatUtils.sendLocalMessage(
                Component.literal("anni snapshot refreshing… (anni_query in flight)")
                        .withStyle(ChatFormatting.GRAY));

        AnniQueryClient.query().whenComplete((snapshot, throwable) -> {
            Minecraft.getInstance().execute(() -> {
                if (throwable != null) {
                    // TimeoutException's getMessage() is null — substitute
                    // the class name so the user can tell what failed.
                    String detail = throwable.getMessage();
                    if (detail == null || detail.isEmpty()) {
                        detail = throwable.getClass().getSimpleName();
                    }
                    ChatUtils.sendLocalMessage(
                            Component.literal("anni snapshot refresh: query failed: "
                                    + detail)
                                    .withStyle(ChatFormatting.RED));
                    return;
                }
                if (snapshot == null) {
                    // Three possible causes: inbound WS down, server
                    // returned snapshot=null (player not in vets-anni DB),
                    // or the 8s deadline elapsed. The QueryClient doesn't
                    // distinguish them in the future's value — they all
                    // come through as null — so the message is generic.
                    ChatUtils.sendLocalMessage(
                            Component.literal("anni snapshot refresh: no snapshot returned "
                                    + "(WS down, player unknown to vets-anni, or timeout)")
                                    .withStyle(ChatFormatting.YELLOW));
                    return;
                }
                ChatUtils.sendLocalMessage(
                        Component.literal("anni snapshot refreshed (mc_uuid="
                                + (snapshot.mcUuid() != null ? snapshot.mcUuid() : "<null>")
                                + ", schema_version=" + snapshot.schemaVersion() + ")")
                                .withStyle(ChatFormatting.GREEN));
            });
        });
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
