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
import org.wynnvets.guild.GuildStateManager;
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
 * {@code /wv debug tree anni …} — simulation hooks for the MWE/anni subsystem.
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
            "member_no_anni",
            "member_no_anni_fill",
            "member_no_anni_unregistered",
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

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_EXTERNAL_MODES =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String m : new String[] {"auto", "true", "false"}) {
                    if (m.startsWith(partial)) {
                        builder.suggest(m);
                    }
                }
                return builder.buildFuture();
            };

    /** Common offsets the user is likely to want when iterating on the
     *  imminent / far-out / past render branches. */
    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_TIME_OFFSETS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String s : new String[] {
                        "60",     // 1m  — within T-2m boss-bar window (S3)
                        "120",    // 2m  — edge of T-2m window
                        "600",    // 10m
                        "1800",   // 30m
                        "3600",   // 1h
                        "7200",   // 2h  — boundary between far-out and imminent
                        "28800",  // 8h  — realistic announced-anni offset
                        "43200",  // 12h — max real announce window
                        "-60",    // 1m ago
                }) {
                    if (s.startsWith(partial)) {
                        builder.suggest(s);
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

    /** S5 — fields {@code AggressiveAlertDispatcher.forceAlert} accepts. */
    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_ALERT_FIELDS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase();
                for (String field : new String[] {
                        "role", "world", "party", "rsvp", "zone", "world_ready"
                }) {
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
                .then(ClientCommandManager.literal("guess")
                        .executes(AnniDebugCommands::guess))
                .then(ClientCommandManager.literal("external")
                        .then(ClientCommandManager.argument("mode",
                                        StringArgumentType.word())
                                .suggests(SUGGEST_EXTERNAL_MODES)
                                .executes(AnniDebugCommands::externalSet)))
                .then(ClientCommandManager.literal("time")
                        .then(ClientCommandManager.argument("seconds",
                                        StringArgumentType.word())
                                .suggests(SUGGEST_TIME_OFFSETS)
                                .executes(AnniDebugCommands::timeSet)))
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
                                        .executes(AnniDebugCommands::modeSet))))
                // S5 — scrollspot host-write. Lives under `/wv debug tree`
                // (rather than `/wv anni`) so it doesn't clutter
                // tab-complete for everyone else; staff hosts using this
                // already need debug logging on (gating below). `set`/
                // `here`/`clear` round-trip the server; `localinject` /
                // `localclear` bypass it for visual testing.
                .then(ClientCommandManager.literal("scrollspot")
                        .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("x",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("y",
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(ClientCommandManager.argument("z",
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes(AnniDebugCommands::scrollspotSet)))))
                        .then(ClientCommandManager.literal("here")
                                .executes(AnniDebugCommands::scrollspotHere))
                        .then(ClientCommandManager.literal("clear")
                                .executes(AnniDebugCommands::scrollspotClear))
                        .then(ClientCommandManager.literal("localinject")
                                .then(ClientCommandManager.argument("x",
                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("y",
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(ClientCommandManager.argument("z",
                                                                com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes(AnniDebugCommands::scrollspotLocalInject)))))
                        .then(ClientCommandManager.literal("localclear")
                                .executes(AnniDebugCommands::scrollspotLocalClear)))
                // S5 — alert debug: fire a synthesised chat alert
                // without contriving a snapshot diff. Bypasses the 5s
                // cooldown so back-to-back invocations work.
                .then(ClientCommandManager.literal("alert")
                        .then(ClientCommandManager.argument("field",
                                        StringArgumentType.word())
                                .suggests(SUGGEST_ALERT_FIELDS)
                                .executes(AnniDebugCommands::alert)))
                // S6 — rsvp debug mirror. Same three subcommands as the
                // main `/wv anni rsvp` tree, gated on requireDebug only
                // (the action only affects the caller's own RSVP, so no
                // staff/organiser perm is required). The main brigadier
                // command is preferred; this exists for symmetry with the
                // scrollspot debug-tree mirror.
                .then(ClientCommandManager.literal("rsvp")
                        .then(ClientCommandManager.literal("hard")
                                .executes(AnniDebugCommands::rsvpHard))
                        .then(ClientCommandManager.literal("soft")
                                .executes(AnniDebugCommands::rsvpSoft))
                        .then(ClientCommandManager.literal("revoke")
                                .executes(AnniDebugCommands::rsvpRevoke)));
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

    /** Permission gate for the scrollspot subcommands: must be either
     *  vetsmod staff OR an organiser of the current anni (i.e. the local
     *  player's UUID appears in {@code snapshot.organisers}, which is
     *  {@code [lead, every party host, ...]}). Other players see a clear
     *  refusal so they don't think the command is broken.
     *
     *  <p>Client-side gate is UX only — vets-anni independently verifies
     *  host status on the server before persisting any scrollspot write,
     *  so a permissive client gate can't be exploited.</p> */
    private static int requireStaffOrOrganiser(CommandContext<FabricClientCommandSource> ctx) {
        if (GuildStateManager.isStaff() || isLocalPlayerOrganiser()) {
            return -1;
        }
        ChatUtils.sendLocalMessage(
                Component.literal("scrollspot is for party hosts / staff only.")
                        .withStyle(ChatFormatting.RED));
        return 0;
    }

    private static boolean isLocalPlayerOrganiser() {
        AnniSnapshot snap = AnniSnapshotCache.latest();
        if (snap == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return false;
        String localUuid = mc.player.getUUID().toString();
        for (String orgUuid : snap.organisers()) {
            if (orgUuid != null && orgUuid.equalsIgnoreCase(localUuid)) {
                return true;
            }
        }
        return false;
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
        int result = parseAndInject(raw, "preset:" + name);
        if (result == 1) {
            // Preset names encode their intended render context — auto-set
            // the renderer's external override so the user doesn't have to
            // remember to flip it before each test. "external_*" presets
            // force external rendering; "member_*" presets force vets;
            // anything else (e.g. "empty") clears the override back to
            // auto. Explicit `/wv debug tree anni external …` after the
            // inject still wins.
            String lower = name.toLowerCase();
            Boolean override;
            String reason;
            if (lower.startsWith("external")) {
                override = Boolean.TRUE;
                reason = "external-tier preset";
            } else if (lower.startsWith("member")) {
                override = Boolean.FALSE;
                reason = "vets-tier preset";
            } else {
                override = null;
                reason = "no tier hint in preset name";
            }
            org.wynnvets.mwe.anni.render.AnniCommandRenderer.setExternalOverride(override);
            String summary = override == null
                    ? "auto (rank signals)"
                    : ("forced " + (override ? "external" : "vets"));
            ChatUtils.sendLocalMessage(
                    Component.literal("(external override → " + summary + " — " + reason + ")")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        return result;
    }

    /** Parse {@code raw} as a snapshot JSON, push into the cache, and
     *  report. Returns 1 on success, 0 on parse error (after surfacing
     *  the error to chat). {@code source} is a short tag like "inline",
     *  "file:foo", or "preset:foo" — included in the success line so the
     *  user can tell which path actually ran. */
    private static int parseAndInject(String raw, String source) {
        String resolved;
        try {
            resolved = substituteNowTokens(raw);
        } catch (NumberFormatException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni snapshot inject (" + source
                            + "): bad NOW token: " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(resolved).getAsJsonObject();
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

    /** Replace {@code "{NOW}"} and {@code "{NOW±<n>(h|m|s)}"} tokens in
     *  a raw snapshot JSON string with the current epoch-seconds.
     *  Lets bundled preset fixtures stay valid across long stretches
     *  of time — without substitution, fixtures bake in absolute
     *  epochs that read poorly months later (e.g. "60664 hours from
     *  now"). Tokens are matched as JSON string literals so post-
     *  substitution the document remains valid JSON with a numeric
     *  value where the placeholder lived.
     *
     *  Inline JSON arguments are unaffected unless they happen to
     *  include the same token syntax. */
    static String substituteNowTokens(String raw) {
        long now = java.time.Instant.now().getEpochSecond();
        java.util.regex.Matcher m = NOW_TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String sign = m.group(1);
            String amount = m.group(2);
            String unit = m.group(3);
            long delta = 0L;
            if (amount != null) {
                double v = Double.parseDouble(amount);
                long perUnit;
                switch (unit) {
                    case "h": perUnit = 3600L; break;
                    case "m": perUnit = 60L; break;
                    case "s": perUnit = 1L; break;
                    default: perUnit = 1L; break;
                }
                delta = (long) (v * perUnit);
                if ("-".equals(sign)) delta = -delta;
            }
            // Matcher.appendReplacement reinterprets $-references; bypass
            // by replacing through Matcher.quoteReplacement.
            m.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(Long.toString(now + delta)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code "{NOW}"} or {@code "{NOW+76h}"} or {@code "{NOW-1.5m}"}.
     *  The whole token (including the JSON quotes) is consumed so the
     *  replacement leaves a bare number. */
    private static final java.util.regex.Pattern NOW_TOKEN =
            java.util.regex.Pattern.compile(
                    "\"\\{NOW(?:([+\\-])(\\d+(?:\\.\\d+)?)([hms]))?\\}\"");

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

    // ───────────────────────────────────── guess (read-only snapshot peek)

    /** {@code /wv debug anni guess} — pull a fresh snapshot from
     *  temp-server and print whatever the prediction / announcement
     *  currently is, in a one-liner. Useful for sanity-checking that
     *  fishbot's {@code \guess} model agrees with what vetsmod sees,
     *  without going through the full {@code /wv anni} renderer.
     *
     *  <p>Always pulls fresh (not from cache) so the user gets the
     *  actual current model output rather than whatever was last pushed.
     *  If the pull fails it falls back to printing what's in cache,
     *  flagged as stale.</p> */
    private static int guess(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;

        ChatUtils.sendLocalMessage(
                Component.literal("anni guess: querying fishbot prediction…")
                        .withStyle(ChatFormatting.GRAY));

        AnniQueryClient.query().whenComplete((snapshot, throwable) -> {
            Minecraft.getInstance().execute(() -> {
                if (throwable != null || snapshot == null) {
                    AnniSnapshot stale = AnniSnapshotCache.latest();
                    if (stale != null) {
                        ChatUtils.sendLocalMessage(
                                Component.literal("anni guess (stale cache — pull failed): ")
                                        .withStyle(ChatFormatting.YELLOW)
                                        .append(formatGuess(stale)));
                    } else {
                        String reason = throwable != null && throwable.getMessage() != null
                                ? throwable.getMessage()
                                : "no snapshot returned";
                        ChatUtils.sendLocalMessage(
                                Component.literal("anni guess: " + reason)
                                        .withStyle(ChatFormatting.RED));
                    }
                    return;
                }
                ChatUtils.sendLocalMessage(
                        Component.literal("anni guess: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(formatGuess(snapshot)));
            });
        });
        return 1;
    }

    /** One-line render of the snapshot's event field — either the
     *  announced stamp + countdown, or the prediction window. Mirrors
     *  what fishbot's {@code \guess} returns in Discord. */
    private static net.minecraft.network.chat.MutableComponent formatGuess(
            AnniSnapshot snapshot) {
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) {
            return Component.literal("no AnniEvent on file (vets-anni has no anchor)")
                    .withStyle(ChatFormatting.GRAY);
        }
        long now = java.time.Instant.now().getEpochSecond();
        Long stamp = event.stampEpoch();
        if (event.announced() && stamp != null && stamp > now) {
            long delta = stamp - now;
            String when = java.time.format.DateTimeFormatter
                    .ofPattern("MMM d '@'HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochSecond(stamp));
            long hours = Math.round(delta / 3600.0);
            return Component.literal("announced ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(hours + "h from now")
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" (" + when + ")")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        AnniSnapshot.Prediction prediction = event.prediction();
        if (prediction == null || prediction.medianEpoch() == null) {
            return Component.literal("not announced; no prediction available")
                    .withStyle(ChatFormatting.GRAY);
        }
        long median = prediction.medianEpoch();
        long delta = median - now;
        long absHours = Math.round(Math.abs(delta) / 3600.0);
        String qualifier = delta <= 0 ? " overdue " : " from now ";
        String when = java.time.format.DateTimeFormatter
                .ofPattern("MMM d '@'HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochSecond(median));
        // σ = window/√12 — see AnniCommandRenderer.predictionLine for why
        // this is what ± should represent (not half-window).
        double sigma = prediction.windowHours() / Math.sqrt(12.0);
        long sigmaRounded = Math.round(sigma);
        String sigmaStr = sigmaRounded < 1
                ? Math.round(sigma * 60) + "min"
                : sigmaRounded + "h";
        return Component.literal("not announced; predicted ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(absHours + "h").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(qualifier).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("| ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(when).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" ±~" + sigmaStr)
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ────────────────────────────── external override (rendering only)

    /** {@code /wv debug anni external <auto|true|false>} — override the
     *  renderer's {@code isExternal} classification, which normally
     *  reads vetsmod's rank signals (Returners guild, waitlist,
     *  honourary). Lets a vets-tier developer test the external render
     *  branch with the {@code external_no_anni} preset without having
     *  to switch accounts. Not persisted; resets on vetsmod reload. */
    private static int externalSet(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
        Boolean override;
        switch (mode) {
            case "auto":  override = null;          break;
            case "true":  override = Boolean.TRUE;  break;
            case "false": override = Boolean.FALSE; break;
            default:
                ChatUtils.sendLocalMessage(
                        Component.literal("anni external: mode must be auto|true|false")
                                .withStyle(ChatFormatting.RED));
                return 0;
        }
        org.wynnvets.mwe.anni.render.AnniCommandRenderer.setExternalOverride(override);
        String summary = override == null
                ? "auto (use rank signals)"
                : ("forced " + (override ? "external" : "vets"));
        ChatUtils.sendLocalMessage(
                Component.literal("anni external override: " + summary)
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // ──────────────────────── time (mutate the cached snapshot's stamp)

    /** {@code /wv debug anni time <seconds>} — mutate the cached
     *  snapshot's {@code event.stamp_epoch} to {@code NOW + seconds},
     *  preserving every other field. Round-trips through JSON because
     *  {@link AnniSnapshot} is immutable.
     *
     *  <p>Positive seconds → future stamp; sets {@code announced=true}
     *  and clears any prediction so the imminent/far-out render branch
     *  fires cleanly. Zero or negative → null stamp + {@code announced=false}
     *  (the snapshot's own prediction, if any, is left untouched).</p>
     *
     *  <p>Useful for nudging the existing injected preset across the
     *  T-2h boundary (far-out ↔ imminent) without re-injecting a whole
     *  new fixture every time.</p> */
    private static int timeSet(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;

        String secondsArg = StringArgumentType.getString(ctx, "seconds");
        long secondsFromNow;
        try {
            secondsFromNow = Long.parseLong(secondsArg);
        } catch (NumberFormatException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni time: expected integer seconds, got '"
                            + secondsArg + "'")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        AnniSnapshot current = AnniSnapshotCache.latest();
        if (current == null) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni time: no snapshot in cache "
                            + "(inject one first with `/wv debug anni snapshot inject preset …`)")
                            .withStyle(ChatFormatting.YELLOW));
            return 0;
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(GSON.toJson(current)).getAsJsonObject();
        } catch (Exception e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni time: failed to serialise current snapshot: "
                            + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        long newStamp = java.time.Instant.now().getEpochSecond() + secondsFromNow;
        JsonObject event = (json.has("event") && !json.get("event").isJsonNull())
                ? json.getAsJsonObject("event")
                : new JsonObject();
        if (secondsFromNow > 0) {
            event.addProperty("stamp_epoch", newStamp);
            event.addProperty("announced", true);
            event.add("prediction", com.google.gson.JsonNull.INSTANCE);
        } else {
            event.add("stamp_epoch", com.google.gson.JsonNull.INSTANCE);
            event.addProperty("announced", false);
            // Leave prediction alone — caller might want to see the
            // not-announced render with whatever prediction was there.
        }
        if (!json.has("event") || json.get("event").isJsonNull()) {
            json.add("event", event);
        }

        AnniSnapshot updated;
        try {
            updated = AnniSnapshot.fromJson(json);
        } catch (Exception e) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni time: failed to deserialise mutated snapshot: "
                            + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        AnniSnapshotCache.update(updated);
        String summary = secondsFromNow > 0
                ? ("stamp_epoch=NOW+" + secondsFromNow + "s (epoch=" + newStamp + ", announced)")
                : ("stamp_epoch=null, announced=false");
        ChatUtils.sendLocalMessage(
                Component.literal("anni time: " + summary)
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // ───────────────────────────────────────── S3 consumers

    private static int zone(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String action = StringArgumentType.getString(ctx, "action");
        boolean enter = "enter".equalsIgnoreCase(action);
        boolean exit  = "exit".equalsIgnoreCase(action);
        if (!enter && !exit) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni zone: action must be enter or exit")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        org.wynnvets.mwe.anni.outline.AnniOutlineTicker.setForceInZone(enter);
        ChatUtils.sendLocalMessage(
                Component.literal("anni zone " + action.toLowerCase()
                        + ": forceInZone=" + enter
                        + " (S4 highlights gate)")
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni flash <field>} — force a FlashTracker
     *  pulse for one of {@code role|party|world|rsvp}. Useful for
     *  visually verifying the {@code &l ↔ &n&l} pulse + sound without
     *  having to ginger a snapshot diff. */
    private static int flash(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String field = StringArgumentType.getString(ctx, "field");
        String normalised = field == null ? "" : field.toLowerCase();
        switch (normalised) {
            case "role":
            case "party":
            case "world":
            case "rsvp":
                org.wynnvets.mwe.anni.bossbar.FlashTracker.forceFlash(normalised);
                ChatUtils.sendLocalMessage(
                        Component.literal("anni flash " + normalised + " triggered")
                                .withStyle(ChatFormatting.GREEN));
                return 1;
            default:
                ChatUtils.sendLocalMessage(
                        Component.literal("anni flash: field must be one of role|party|world|rsvp, got " + field)
                                .withStyle(ChatFormatting.RED));
                return 0;
        }
    }

    /** {@code /wv debug tree anni scrollspot set <x> <y> <z>} — host
     *  write: sends an {@code anni_scrollspot_set} frame to the server.
     *  Delegates to {@link org.wynnvets.mwe.anni.aggressive.AnniScrollspotCommand#set}.
     *  The server verifies the actor is the host of their assigned party
     *  via vets-anni's {@code anni-party-scrollspot} endpoint. */
    private static int scrollspotSet(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.aggressive.AnniScrollspotCommand.set(ctx);
    }

    /** {@code /wv debug tree anni scrollspot here} — host write using the
     *  player's current block-position. */
    private static int scrollspotHere(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.aggressive.AnniScrollspotCommand.here(ctx);
    }

    /** {@code /wv debug tree anni scrollspot clear} — host write that
     *  clears the party's scroll spot. */
    private static int scrollspotClear(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.aggressive.AnniScrollspotCommand.clear(ctx);
    }

    /** {@code /wv debug tree anni scrollspot localinject <x> <y> <z>} —
     *  local-only inject into the marker provider without bothering the
     *  server. For visual testing of the waypoint render without
     *  coordinating a host. */
    private static int scrollspotLocalInject(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        int x = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x");
        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y");
        int z = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z");
        org.wynnvets.mwe.anni.aggressive.ScrollSpotMarkerProvider.get().debugSet(x, y, z);
        ChatUtils.sendLocalMessage(Component.literal(
                "scrollspot local-inject: " + x + " " + y + " " + z)
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni scrollspot localclear} — clear the
     *  local-only injected scroll spot. */
    private static int scrollspotLocalClear(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        org.wynnvets.mwe.anni.aggressive.ScrollSpotMarkerProvider.get().debugClear();
        ChatUtils.sendLocalMessage(Component.literal("scrollspot local cleared")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni rsvp hard} — debug mirror of
     *  {@code /wv anni rsvp hard}. Identical effect; lives here for
     *  symmetry with the scrollspot debug-tree mirror. */
    private static int rsvpHard(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.command.AnniRsvpCommand.hard(ctx);
    }

    /** {@code /wv debug tree anni rsvp soft} — debug mirror. */
    private static int rsvpSoft(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.command.AnniRsvpCommand.soft(ctx);
    }

    /** {@code /wv debug tree anni rsvp revoke} — debug mirror. */
    private static int rsvpRevoke(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.command.AnniRsvpCommand.revoke(ctx);
    }

    /** {@code /wv debug tree anni alert <field>} — synthesise a chat
     *  alert. Verifies cooldown / formatting / bouncing without
     *  contriving a snapshot diff. */
    private static int alert(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String field = StringArgumentType.getString(ctx, "field").toLowerCase();
        org.wynnvets.mwe.anni.aggressive.AggressiveAlertDispatcher.forceAlert(field);
        ChatUtils.sendLocalMessage(Component.literal("anni alert " + field + " triggered")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni mode set <silent|passive|aggressive>} —
     *  unconditional mode transition that bypasses the /stream mutex
     *  for testing rendering during a screen capture. */
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
        org.wynnvets.mwe.anni.mode.AnniModeManager.transitionTo(
                org.wynnvets.mwe.anni.mode.AnniMode.fromString(mode),
                org.wynnvets.mwe.anni.mode.AnniModeManager.Source.DEBUG_BYPASS_MUTEX);
        return 1;
    }
}
