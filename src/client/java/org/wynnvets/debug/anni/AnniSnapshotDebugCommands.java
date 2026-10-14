package org.wynnvets.debug.anni;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.mwe.anni.network.AnniQueryClient;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

/**
 * The snapshot half of the {@code /wv debug tree anni} harness: the handlers that put a snapshot
 * into {@link AnniSnapshotCache} by hand, write the cached one to disk, and pull a real one.
 *
 * <p>{@code snapshot inject} (inline JSON, a dump file or a bundled preset), {@code snapshot clear}
 * and {@code time} write the cache directly, and every inject path substitutes the fixture's
 * {@code {NOW…}} tokens first. {@code snapshot refresh} and {@code guess} issue an
 * {@code anni_query} through {@link AnniQueryClient#query()}, whose response handler writes the
 * cache when the reply carries a snapshot. {@code snapshot dump} writes the cached snapshot under
 * the game directory, where {@code inject file} reads it back. Whatever these commands write lasts
 * only until the next pull that returns a snapshot, and inside the boss-bar window the snapshot
 * poller pulls on a schedule ({@code anni-poller-erases-injected-debug-state}).</p>
 *
 * <p>{@code external} and a successful {@code snapshot inject preset} write
 * {@code AnniCommandRenderer}'s external override.</p>
 *
 * <p>The dump's {@link Gson} keeps explicit nulls ({@code serializeNulls}), so a dump keeps the
 * nulls the wire format carries and {@code inject file} can load it back; {@code time}
 * round-trips the cached snapshot through the same instance.</p>
 *
 * <p>Every handler opens with {@code AnniDebugCommands.requireDebug}. {@link #SNAPSHOTS_DIR}
 * resolves the game directory when this class initialises, which happens at a handler's first run
 * or the first dump-file tab completion, never when the tree is built.</p>
 */
final class AnniSnapshotDebugCommands {

    // serializeNulls so dumps keep the explicit nulls the wire format
    // carries (server-side python json.dumps emits them; Gson drops null
    // fields by default). Not byte-equivalent: the dump is pretty-printed.
    // Lets `inject file` round-trip a dump and lets a human-eye diff against
    // the canonical /api/internal/anni-player/{uuid} response succeed.
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    /** Directory under the game dir where {@code snapshot dump} writes
     *  fixtures and {@code snapshot inject file} reads them. Created on
     *  first dump. Sibling of {@code vetsmod/dumps/items/} —
     *  {@code vetsmod/dumps/} is the shared root for every dump
     *  artifact regardless of producer, organised per-type. */
    // Package-private for SUGGEST_DUMP_FILES, which lists it for tab completion.
    static final Path SNAPSHOTS_DIR =
            FabricLoader.getInstance().getGameDir().resolve("vetsmod/dumps/anni");

    private static final DateTimeFormatter DUMP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Classpath root for the bundled snapshot fixtures. Resources land here
     *  via {@code src/client/resources/assets/vetsmod/anni_test_snapshots/}
     *  and are loadable through {@link Class#getResourceAsStream(String)}. */
    private static final String PRESET_CLASSPATH_PREFIX = "/assets/vetsmod/anni_test_snapshots/";

    private AnniSnapshotDebugCommands() {}

    // ─────────────────────────── snapshot inject / dump / clear / refresh

    /** {@code /wv debug tree anni snapshot inject <json>} — inject the rest
     *  of the line as snapshot JSON through {@code parseAndInject}, tagged
     *  {@code inline}. NOW tokens are substituted here too. */
    static int snapshotInject(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;
        String raw = StringArgumentType.getString(ctx, "json");
        return parseAndInject(raw, "inline");
    }

    /** {@code /wv debug tree anni snapshot inject file <name>} — inject
     *  the snapshot in {@code <name>.json} under {@code SNAPSHOTS_DIR},
     *  typically one written by {@code snapshot dump}; a trailing
     *  {@code .json} on the argument is tolerated, and tab completion
     *  offers that directory's {@code .json} files, extension stripped.
     *  Reports and returns 0 if the file is missing or unreadable;
     *  otherwise hands the text to {@code parseAndInject} tagged
     *  {@code "file:<name>"}. */
    static int snapshotInjectFile(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
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

    /** {@code /wv debug tree anni snapshot inject preset <name>} — inject
     *  a bundled fixture, read from the classpath at
     *  {@code PRESET_CLASSPATH_PREFIX + name + ".json"} (a trailing
     *  {@code .json} on the argument is tolerated), through
     *  {@code parseAndInject}, so its NOW tokens are substituted. Any
     *  {@code .json} file directly under that prefix loads; {@code AnniDebugSuggestions.PRESETS}
     *  only drives tab completion. Reports and returns 0 for an unknown or
     *  unreadable preset.
     *
     *  <p>A successful inject also sets the renderer's external override
     *  from the preset name, tested case-insensitively: a name starting
     *  {@code external} forces external rendering, one starting
     *  {@code member} forces vets rendering, and anything else (e.g.
     *  {@code empty}) resets the override to auto (rank signals). This
     *  replaces any override set earlier with
     *  {@code /wv debug tree anni external …}; running that command after
     *  the inject still wins. A failed inject leaves the override
     *  alone.</p> */
    static int snapshotInjectPreset(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
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
                        Component.literal(
                                        "anni snapshot inject preset: unknown preset '"
                                                + name
                                                + "'")
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
            String summary =
                    override == null
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
                    Component.literal(
                                    "anni snapshot inject ("
                                            + source
                                            + "): bad NOW token: "
                                            + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(resolved).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "anni snapshot inject ("
                                            + source
                                            + "): invalid JSON: "
                                            + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        AnniSnapshot snapshot;
        try {
            snapshot = AnniSnapshot.fromJson(json);
        } catch (Exception e) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "anni snapshot inject ("
                                            + source
                                            + "): parse failed: "
                                            + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        AnniSnapshotCache.update(snapshot);
        ChatUtils.sendLocalMessage(
                Component.literal(
                                "anni snapshot injected from "
                                        + source
                                        + " (schema_version="
                                        + snapshot.schemaVersion()
                                        + ", mc_uuid="
                                        + (snapshot.mcUuid() != null ? snapshot.mcUuid() : "<null>")
                                        + ")")
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
                    case "h":
                        perUnit = 3600L;
                        break;
                    case "m":
                        perUnit = 60L;
                        break;
                    case "s":
                        perUnit = 1L;
                        break;
                    default:
                        perUnit = 1L;
                        break;
                }
                delta = (long) (v * perUnit);
                if ("-".equals(sign)) delta = -delta;
            }
            // Matcher.appendReplacement reinterprets $-references; bypass
            // by replacing through Matcher.quoteReplacement.
            m.appendReplacement(
                    out, java.util.regex.Matcher.quoteReplacement(Long.toString(now + delta)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code "{NOW}"} or {@code "{NOW+76h}"} or {@code "{NOW-1.5m}"}.
     *  The whole token (including the JSON quotes) is consumed so the
     *  replacement leaves a bare number. */
    private static final java.util.regex.Pattern NOW_TOKEN =
            java.util.regex.Pattern.compile("\"\\{NOW(?:([+\\-])(\\d+(?:\\.\\d+)?)([hms]))?\\}\"");

    /** {@code /wv debug tree anni snapshot dump} — write the cached
     *  snapshot (real or injected) to
     *  {@code vetsmod/dumps/anni/snapshot-<yyyyMMdd-HHmmss>.json} under the
     *  game directory, the timestamp in UTC, pretty-printed with explicit
     *  nulls. Creates the directory if needed. Two dumps within the same
     *  second get the same name, and the second overwrites the first.
     *  Reports and returns 0 if the cache is empty or the write fails. The
     *  file name without {@code .json} is what
     *  {@code snapshot inject file <name>} takes to load it back. */
    static int snapshotDump(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
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

    /** {@code /wv debug tree anni snapshot clear} — synchronous wipe.
     *
     *  <p>Pushes {@code null} into the cache so subscribed surfaces
     *  re-render in their no-snapshot state. Push frames from temp-server
     *  only fire on a diff against temp-server's own cache, so clearing
     *  the local cache does NOT trigger a re-push. The cache stays empty
     *  until something refills it, such as a {@code refresh} or any other
     *  client-side {@code anni_query} that returns a snapshot (a cold-cache
     *  {@code /wv anni} can issue one). A server-side change was meant to
     *  refill it too, but today no {@code anni_state} push reaches vetsmod
     *  ({@code outbound-socket-never-authenticated}).
     *  Use this command to test the no-snapshot rendering branch; use
     *  {@code refresh} to undo synthetic injects and re-pull real data —
     *  it replaces the cache only when the query returns a snapshot.</p> */
    static int snapshotClear(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;
        AnniSnapshotCache.update(null);
        ChatUtils.sendLocalMessage(
                Component.literal(
                                "anni snapshot cleared (cache is null; "
                                        + "run `/wv debug anni snapshot refresh` to re-pull, "
                                        + "or wait for server-side changes)")
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni snapshot refresh} — async re-fetch.
     *
     *  <p>Fires an {@code anni_query} via {@link AnniQueryClient#query()}.
     *  The query client's response handler already pushes successful
     *  results through {@link AnniSnapshotCache#update(AnniSnapshot)}, so
     *  this command only needs to issue the request and report when the
     *  future completes. The completion callback bounces onto the main
     *  thread via {@link Minecraft#execute(Runnable)} — a response
     *  completes the {@link AnniQueryClient} future on the WS reader
     *  thread, and the 8-second deadline completes it from the JDK's
     *  {@code CompletableFuture} delay scheduler; neither is safe for chat
     *  dispatch.</p>
     *
     *  <p>Pairs with the synthetic-injection paths as the "undo" knob:
     *  inject any preset/file/inline snapshot to test rendering, then
     *  {@code refresh} to wipe the synthetic data and re-pull the real
     *  state from the server. Only a non-null snapshot replaces the cache:
     *  if the query times out, the WS is down, or the response carries no
     *  parseable snapshot, the synthetic one stays until a later pull
     *  replaces it (an {@code anni_state} push would too, but today none
     *  reaches vetsmod: {@code outbound-socket-never-authenticated}), and
     *  the failure line does not say so.</p> */
    static int snapshotRefresh(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;

        ChatUtils.sendLocalMessage(
                Component.literal("anni snapshot refreshing… (anni_query in flight)")
                        .withStyle(ChatFormatting.GRAY));

        AnniQueryClient.query()
                .whenComplete(
                        (snapshot, throwable) -> {
                            Minecraft.getInstance()
                                    .execute(
                                            () -> {
                                                if (throwable != null) {
                                                    // TimeoutException's getMessage() is null —
                                                    // substitute
                                                    // the class name so the user can tell what
                                                    // failed.
                                                    String detail = throwable.getMessage();
                                                    if (detail == null || detail.isEmpty()) {
                                                        detail =
                                                                throwable
                                                                        .getClass()
                                                                        .getSimpleName();
                                                    }
                                                    ChatUtils.sendLocalMessage(
                                                            Component.literal(
                                                                            "anni snapshot refresh: query failed: "
                                                                                    + detail)
                                                                    .withStyle(ChatFormatting.RED));
                                                    return;
                                                }
                                                if (snapshot == null) {
                                                    // Three possible causes: inbound WS down, a
                                                    // response whose snapshot is missing, null
                                                    // (player not in vets-anni DB) or fails to
                                                    // parse, or the 8s deadline elapsed. The
                                                    // QueryClient doesn't distinguish them in the
                                                    // future's value — they all come through as
                                                    // null — so the message is generic.
                                                    ChatUtils.sendLocalMessage(
                                                            Component.literal(
                                                                            "anni snapshot refresh: no snapshot returned "
                                                                                    + "(WS down, player unknown to vets-anni, or timeout)")
                                                                    .withStyle(
                                                                            ChatFormatting.YELLOW));
                                                    return;
                                                }
                                                ChatUtils.sendLocalMessage(
                                                        Component.literal(
                                                                        "anni snapshot refreshed (mc_uuid="
                                                                                + (snapshot.mcUuid()
                                                                                                != null
                                                                                        ? snapshot
                                                                                                .mcUuid()
                                                                                        : "<null>")
                                                                                + ", schema_version="
                                                                                + snapshot
                                                                                        .schemaVersion()
                                                                                + ")")
                                                                .withStyle(ChatFormatting.GREEN));
                                            });
                        });
        return 1;
    }

    // ───────────── guess (snapshot peek; a successful pull replaces the cache)

    /** {@code /wv debug tree anni guess} — pull a fresh snapshot from
     *  temp-server and print whatever the prediction / announcement
     *  currently is, in a one-liner. Useful for sanity-checking that
     *  fishbot's {@code \guess} model agrees with what vetsmod sees,
     *  without going through the full {@code /wv anni} renderer.
     *
     *  <p>Always issues a fresh {@code anni_query} rather than reading
     *  the local cache, so the user does not just see whatever was last
     *  pushed. temp-server may still answer from its own short-lived
     *  cache, or with its cached copy marked stale when it cannot get a
     *  fresh one from vets-anni; vetsmod does not read that stale flag,
     *  so such a reply prints unflagged. If the pull fails (or returns no
     *  snapshot) it falls back to printing what's in the local cache, if
     *  any, flagged as stale.</p> */
    static int guess(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;

        ChatUtils.sendLocalMessage(
                Component.literal("anni guess: querying fishbot prediction…")
                        .withStyle(ChatFormatting.GRAY));

        AnniQueryClient.query()
                .whenComplete(
                        (snapshot, throwable) -> {
                            Minecraft.getInstance()
                                    .execute(
                                            () -> {
                                                if (throwable != null || snapshot == null) {
                                                    AnniSnapshot stale = AnniSnapshotCache.latest();
                                                    if (stale != null) {
                                                        ChatUtils.sendLocalMessage(
                                                                Component.literal(
                                                                                "anni guess (stale cache — pull failed): ")
                                                                        .withStyle(
                                                                                ChatFormatting
                                                                                        .YELLOW)
                                                                        .append(
                                                                                formatGuess(
                                                                                        stale)));
                                                    } else {
                                                        String reason =
                                                                throwable != null
                                                                                && throwable
                                                                                                .getMessage()
                                                                                        != null
                                                                        ? throwable.getMessage()
                                                                        : "no snapshot returned";
                                                        ChatUtils.sendLocalMessage(
                                                                Component.literal(
                                                                                "anni guess: "
                                                                                        + reason)
                                                                        .withStyle(
                                                                                ChatFormatting
                                                                                        .RED));
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
    private static net.minecraft.network.chat.MutableComponent formatGuess(AnniSnapshot snapshot) {
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) {
            return Component.literal("no AnniEvent on file (vets-anni has no anchor)")
                    .withStyle(ChatFormatting.GRAY);
        }
        long now = java.time.Instant.now().getEpochSecond();
        Long stamp = event.stampEpoch();
        if (event.announced() && stamp != null && stamp > now) {
            long delta = stamp - now;
            String when =
                    java.time.format.DateTimeFormatter.ofPattern("MMM d '@'HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.ofEpochSecond(stamp));
            long hours = Math.round(delta / 3600.0);
            return Component.literal("announced ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(hours + "h from now").withStyle(ChatFormatting.WHITE))
                    .append(
                            Component.literal(" (" + when + ")")
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
        String when =
                java.time.format.DateTimeFormatter.ofPattern("MMM d '@'HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.ofEpochSecond(median));
        // σ = window/√12 — see AnniCommandRenderer.predictionLine for why
        // this is what ± should represent (not half-window).
        double sigma = prediction.windowHours() / Math.sqrt(12.0);
        long sigmaRounded = Math.round(sigma);
        String sigmaStr = sigmaRounded < 1 ? Math.round(sigma * 60) + "min" : sigmaRounded + "h";
        return Component.literal("not announced; predicted ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(absHours + "h").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(qualifier).withStyle(ChatFormatting.GOLD))
                .append(
                        Component.literal("| ")
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(when).withStyle(ChatFormatting.YELLOW))
                .append(
                        Component.literal(" ±~" + sigmaStr)
                                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    // ────────────────────────────── external override (rendering only)

    /** {@code /wv debug tree anni external <auto|true|false>} — override the
     *  renderer's {@code isExternal} classification, which normally
     *  reads vetsmod's rank signals (Returners guild, waitlist,
     *  honourary). Lets a vets-tier developer test the external render
     *  branch with the {@code external_no_anni} preset without having
     *  to switch accounts. Not persisted; resets on vetsmod reload. */
    static int externalSet(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;
        String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
        Boolean override;
        switch (mode) {
            case "auto":
                override = null;
                break;
            case "true":
                override = Boolean.TRUE;
                break;
            case "false":
                override = Boolean.FALSE;
                break;
            default:
                ChatUtils.sendLocalMessage(
                        Component.literal("anni external: mode must be auto|true|false")
                                .withStyle(ChatFormatting.RED));
                return 0;
        }
        org.wynnvets.mwe.anni.render.AnniCommandRenderer.setExternalOverride(override);
        String summary =
                override == null
                        ? "auto (use rank signals)"
                        : ("forced " + (override ? "external" : "vets"));
        ChatUtils.sendLocalMessage(
                Component.literal("anni external override: " + summary)
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // ──────────────────────── time (mutate the cached snapshot's stamp)

    /** {@code /wv debug tree anni time <seconds>} — rewrite the cached
     *  snapshot's {@code event.stamp_epoch} and {@code announced}, and for
     *  a future stamp clear its {@code prediction}; the rest of the
     *  snapshot is kept. Round-trips through JSON because
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
    static int timeSet(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;

        String secondsArg = StringArgumentType.getString(ctx, "seconds");
        long secondsFromNow;
        try {
            secondsFromNow = Long.parseLong(secondsArg);
        } catch (NumberFormatException e) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "anni time: expected integer seconds, got '" + secondsArg + "'")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        AnniSnapshot current = AnniSnapshotCache.latest();
        if (current == null) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "anni time: no snapshot in cache "
                                            + "(inject one first with `/wv debug anni snapshot inject preset …`)")
                            .withStyle(ChatFormatting.YELLOW));
            return 0;
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(GSON.toJson(current)).getAsJsonObject();
        } catch (Exception e) {
            ChatUtils.sendLocalMessage(
                    Component.literal(
                                    "anni time: failed to serialise current snapshot: "
                                            + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        long newStamp = java.time.Instant.now().getEpochSecond() + secondsFromNow;
        JsonObject event =
                (json.has("event") && !json.get("event").isJsonNull())
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
                    Component.literal(
                                    "anni time: failed to deserialise mutated snapshot: "
                                            + e.getMessage())
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        AnniSnapshotCache.update(updated);
        String summary =
                secondsFromNow > 0
                        ? ("stamp_epoch=NOW+"
                                + secondsFromNow
                                + "s (epoch="
                                + newStamp
                                + ", announced)")
                        : ("stamp_epoch=null, announced=false");
        ChatUtils.sendLocalMessage(
                Component.literal("anni time: " + summary).withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
