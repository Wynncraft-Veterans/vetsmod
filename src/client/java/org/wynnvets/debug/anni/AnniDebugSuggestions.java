package org.wynnvets.debug.anni;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * The tab-completion providers of the {@code /wv debug tree anni} harness, with the bundled preset
 * names that one of them offers.
 *
 * <p>Each provider lower-cases what has been typed and offers the candidates that start with it.
 * {@link #SUGGEST_DUMP_FILES} reads its candidates from the dump directory each time it runs, and
 * it and {@link #SUGGEST_REGISTRY_ROLES} lower-case each candidate as well before comparing. The
 * role codes mirror {@code AnniOutlinePalette}'s role table by hand; their Javadoc says where they
 * differ.</p>
 *
 * <p>Initialising this class only builds these fields. It reads no game state and starts no other
 * class in the harness, so it is safe at tree build.</p>
 */
final class AnniDebugSuggestions {

    /** Names (no extension) of every bundled preset. Hard-coded for tab
     *  completion — the jar's classpath isn't trivially enumerable from
     *  inside the running mod, and the set rarely changes. Keep in sync
     *  with the files actually shipped under
     *  {@link AnniSnapshotDebugCommands#PRESET_CLASSPATH_PREFIX}. */
    private static final String[] PRESETS = {
        "empty",
        "external_no_anni",
        "member_announced",
        "member_in_party",
        "member_no_anni",
        "member_no_anni_fill",
        "member_no_anni_unregistered",
    };

    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_MODES =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String mode : new String[] {"silent", "passive", "aggressive"}) {
                    if (mode.startsWith(partial)) {
                        builder.suggest(mode);
                    }
                }
                return builder.buildFuture();
            };

    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_ZONE_ACTIONS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String action : new String[] {"enter", "exit"}) {
                    if (action.startsWith(partial)) {
                        builder.suggest(action);
                    }
                }
                return builder.buildFuture();
            };

    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_DUMP_FILES =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                if (!Files.isDirectory(AnniSnapshotDebugCommands.SNAPSHOTS_DIR)) {
                    return builder.buildFuture();
                }
                try (Stream<Path> stream = Files.list(AnniSnapshotDebugCommands.SNAPSHOTS_DIR)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .map(
                                    p -> {
                                        String n = p.getFileName().toString();
                                        return n.substring(0, n.length() - ".json".length());
                                    })
                            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                            .sorted()
                            .forEach(builder::suggest);
                } catch (IOException ignored) {
                    // Fall through with whatever we've collected so far.
                }
                return builder.buildFuture();
            };

    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_PRESETS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String name : PRESETS) {
                    if (name.startsWith(partial)) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            };

    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_EXTERNAL_MODES =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String m : new String[] {"auto", "true", "false"}) {
                    if (m.startsWith(partial)) {
                        builder.suggest(m);
                    }
                }
                return builder.buildFuture();
            };

    /** Common offsets the user is likely to want when iterating on the
     *  imminent / far-out / not-announced render branches. */
    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_TIME_OFFSETS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String s :
                        new String[] {
                            "60", // 1m  — within T-5m boss-bar window (S3)
                            "120", // 2m  — inside the T-5m window (its edge is 300)
                            "600", // 10m
                            "1800", // 30m
                            "3600", // 1h
                            "7200", // 2h  — boundary between far-out and imminent
                            "28800", // 8h  — realistic announced-anni offset
                            "43200", // 12h — max real announce window
                            "-60", // ≤ 0 → timeSet nulls the stamp (not-announced branch)
                        }) {
                    if (s.startsWith(partial)) {
                        builder.suggest(s);
                    }
                }
                return builder.buildFuture();
            };

    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_FLASH_FIELDS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String field : new String[] {"role", "world", "party", "rsvp"}) {
                    if (field.startsWith(partial)) {
                        builder.suggest(field);
                    }
                }
                return builder.buildFuture();
            };

    /** Role codes suggested for {@code /wv debug tree anni registry set <user> <role>}
     *  (the argument accepts any word; an unrecognised code renders grey).
     *  Mirrors the cases in
     *  {@link org.wynnvets.mwe.anni.outline.AnniOutlinePalette#chatFormattingForRole
     *  AnniOutlinePalette#chatFormattingForRole}
     *  except its {@code HEAL} alias for {@code HEALER},
     *  plus the synthetic {@code other} alias for the {@code OTHER_VETS_PARTY}
     *  tier (light grey, role-agnostic). */
    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_REGISTRY_ROLES =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String role :
                        new String[] {
                            "PRIMARY", // red    (§c) — the canonical "red highlight + red nametag"
                            "SECONDARY", // yellow (§e)
                            "TERTIARY", // purple (§d)
                            "TANK", // aqua   (§b)
                            "HEALER", // green  (§a)
                            "FILL", // white  (§f)
                            "other", // grey   (§7) — OTHER_VETS_PARTY tier
                        }) {
                    if (role.toLowerCase(Locale.ROOT).startsWith(partial)) {
                        builder.suggest(role);
                    }
                }
                return builder.buildFuture();
            };

    /** S5 — fields {@link org.wynnvets.mwe.anni.aggressive.AggressiveAlertDispatcher#forceAlert
     *  AggressiveAlertDispatcher#forceAlert} accepts. */
    static final SuggestionProvider<FabricClientCommandSource> SUGGEST_ALERT_FIELDS =
            (ctx, builder) -> {
                String partial = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String field :
                        new String[] {"role", "world", "party", "rsvp", "zone", "world_ready"}) {
                    if (field.startsWith(partial)) {
                        builder.suggest(field);
                    }
                }
                return builder.buildFuture();
            };

    private AnniDebugSuggestions() {}
}
