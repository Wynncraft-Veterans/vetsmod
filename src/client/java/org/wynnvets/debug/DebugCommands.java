package org.wynnvets.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.config.VetsConfig;
import org.wynnvets.debug.diagnostics.DiagnosticsHandler;
import org.wynnvets.debug.dump.TabDumpHandler;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.mwe.anni.debug.AnniDebugCommands;

/**
 * Builds the entire {@code /wv debug} command subtree.
 *
 * <p>This class owns all debug-related subcommands so that production code
 * in {@link org.wynnvets.VetsmodClient} only needs a single integration
 * point: {@link #buildCommandTree()}.</p>
 *
 * <h3>Top-level shape</h3>
 * The top level is intentionally generic — only the universally-applicable
 * verbs ({@code set} for toggles, {@code trigger} for one-shots, plus the
 * diagnostics-dump default) live there. Feature-specific subsystem trees
 * (e.g. the MWE/anni harness) nest under {@code /wv debug tree …} so they
 * stay discoverable as siblings but don't crowd the top level.
 *
 * <h3>Subcommands</h3>
 * <ul>
 *   <li>{@code /wv debug} — diagnostics dump (delegated to {@link DiagnosticsHandler})</li>
 *   <li>{@code /wv debug true|false} — toggle debug logging</li>
 *   <li>{@code /wv debug set} — list debug config keys</li>
 *   <li>{@code /wv debug set <key>} — get value of a debug config key</li>
 *   <li>{@code /wv debug set <key> <value>} — set a debug config key</li>
 *   <li>{@code /wv debug trigger charDump} — render PUA icon characters</li>
 *   <li>{@code /wv debug trigger forceChecks} — force re-check guild membership, rank, and staff status</li>
 *   <li>{@code /wv debug trigger bossBarsDump} — dump current {@code BossHealthOverlay#events} state (iteration order, per-bar UUID/name/color/overlay/progress, ours marker)</li>
 *   <li>{@code /wv debug trigger nametagsDump} — dump per-player render-state (forces a re-extract; logs gate, registry hit, and the resulting {@code state.nameTag} component as built by {@code NametagMixin})</li>
 *   <li>{@code /wv debug tree anni …} — MWE/anni subsystem debug tree</li>
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
            )
            .then(ClientCommandManager.literal("trigger")
                .then(ClientCommandManager.literal("charDump")
                    .executes(DebugCommands::triggerCharDump)
                )
                .then(ClientCommandManager.literal("forceChecks")
                    .executes(DebugCommands::triggerForceChecks)
                )
                .then(ClientCommandManager.literal("tabDump")
                    .executes(ctx -> { TabDumpHandler.execute(); return 1; })
                )
                .then(ClientCommandManager.literal("bossBarsDump")
                    .executes(DebugCommands::triggerBossBarsDump)
                )
                .then(ClientCommandManager.literal("nametagsDump")
                    .executes(DebugCommands::triggerNametagsDump)
                )
                .then(ClientCommandManager.literal("ghostsPromptDump")
                    .executes(ctx -> {
                        org.wynnvets.mwe.anni.aggressive.GhostsPromptHandler.debugDump();
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("zoneLinesDump")
                    .executes(ctx -> {
                        DebugCommands.triggerZoneLinesDump();
                        return 1;
                    })
                )
            )
            // Subsystem trees nest under `tree` — they're neither toggles
            // nor triggers, so they don't belong at the top level alongside
            // `set` / `trigger`.
            .then(ClientCommandManager.literal("tree")
                .then(AnniDebugCommands.buildCommandTree())
            );
    }

    // ── /wv debug set handlers ──────────────────────────────────────

    /**
     * {@code /wv debug set} — lists all debug config keys and their current values.
     */
    private static int debugConfigList(CommandContext<FabricClientCommandSource> ctx) {
        MutableComponent header = Component.literal("Debug Configuration:")
            .withStyle(ChatFormatting.GOLD);
        ChatUtils.sendLocalMessageNewBlock(header);

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

    // ── /wv debug trigger handlers ──────────────────────────────────

    /**
     * Surrogate pair prefix used by Wynncraft's resource pack to render
     * PUA icon characters in the {@code chat/prefix} font.
     */
    private static final String PREFIX = "\uDAFF\uDFFC";
    private static final String SUFFIX = "\uDAFF\uDFFF\uE002\uDAFF\uDFFE";

    private static final Style CHAT_PREFIX_FONT = Style.EMPTY
            .withFont(new FontDescription.Resource(Identifier.parse("chat/prefix")))
            .withoutShadow();

    /**
     * {@code /wv debug trigger charDump} — renders PUA characters U+E001
     * through U+E040 in the resource pack's {@code chat/prefix} font,
     * displayed as badge-style sequences so their glyphs are visible.
     */
    private static int triggerCharDump(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtils.sendLocalMessage(
            Component.literal("PUA Icon Character Dump (U+E001 – U+E040)")
                .withStyle(ChatFormatting.GOLD)
        );

        // Print 8 characters per line
        for (int row = 0xE001; row <= 0xE040; row += 8) {
            MutableComponent line = Component.empty();
            int end = Math.min(row + 8, 0xE041);

            for (int cp = row; cp < end; cp++) {
                if (cp > row) {
                    line.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY));
                }

                // Label: "E001" etc.
                String label = String.format("E%03X", cp & 0xFFF);
                line.append(Component.literal(label + " ")
                    .withStyle(ChatFormatting.GRAY));

                // Render the icon in the resource pack font using the
                // full badge sequence: PREFIX + icon char + SUFFIX
                String iconSeq = PREFIX + (char) cp + SUFFIX;
                line.append(Component.literal(iconSeq)
                    .setStyle(CHAT_PREFIX_FONT));
            }

            ChatUtils.sendLocalMessage(line);
        }

        ChatUtils.sendLocalMessage(
            Component.literal("End of dump. Characters without glyphs will appear blank.")
                .withStyle(ChatFormatting.GRAY)
        );
        return 1;
    }

    /**
     * {@code /wv debug trigger forceChecks} — forces a re-read of guild
     * membership, rank, and staff status from the Wynntils API, reporting
     * all current values to chat.
     */
    private static int triggerForceChecks(CommandContext<FabricClientCommandSource> ctx) {
        GuildStateManager.forceGuildRecheck();
        return 1;
    }

    /**
     * {@code /wv debug trigger nametagsDump} — per-player diagnostic for the
     * S4 nametag pipeline. For each nearby {@code AbstractClientPlayer},
     * reports:
     * <ul>
     *   <li>their gameProfile username;</li>
     *   <li>the live {@code AnniOutlineTicker.isOutlineSuppressionActive()} flag;</li>
     *   <li>whether {@link org.wynnvets.mwe.anni.outline.AnniOutlineRegistry} has
     *       an entry for them and the resolved tier / nametag {@link net.minecraft.ChatFormatting};</li>
     *   <li>what colour the {@code NametagMixin} TAIL-of-{@code extractRenderState}
     *       branch <em>would</em> resolve to for them right now.</li>
     * </ul>
     * Used to verify whether the registry rebuild has actually picked up the
     * injected snapshot — e.g. if a player you expect to be coloured returns
     * {@code registry=miss}, the snapshot didn't land them in the active party.
     */
    private static int triggerNametagsDump(CommandContext<FabricClientCommandSource> ctx) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            ChatUtils.sendLocalMessage(Component.literal(
                    "nametagsDump: no client level (not in-game?)")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean gate = org.wynnvets.mwe.anni.outline.AnniOutlineTicker.isOutlineSuppressionActive();
        boolean nametagsEnabled = VetsConfig.get(VetsConfig.VETS_ANNI_NAMETAGS_ENABLED);
        boolean outlinesEnabled = VetsConfig.get(VetsConfig.VETS_ANNI_OUTLINES_ENABLED);

        ChatUtils.sendLocalMessageNewBlock(Component.literal(
                "S4 nametag dump — outlineSuppressionActive=" + gate
                        + ", vetsAnniNametagsEnabled=" + nametagsEnabled
                        + ", vetsAnniOutlinesEnabled=" + outlinesEnabled)
                .withStyle(ChatFormatting.GOLD));

        java.util.List<? extends net.minecraft.client.player.AbstractClientPlayer> players =
                mc.level.players();
        ChatUtils.sendLocalMessage(Component.literal(
                "  " + players.size() + " player(s) in level:")
                .withStyle(ChatFormatting.DARK_GRAY));

        for (net.minecraft.client.player.AbstractClientPlayer p : players) {
            String username = p.getGameProfile().name();
            if (username == null) continue;

            org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.Entry entry =
                    org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.getEntry(username);
            String tier = (entry != null) ? entry.tier().name() : "—";
            String role = (entry != null && entry.role() != null) ? entry.role() : "—";
            net.minecraft.ChatFormatting fmt;
            String fmtSource;
            if (gate && nametagsEnabled) {
                if (entry != null) {
                    fmt = entry.nametagFormatting();
                    fmtSource = "registry";
                } else {
                    fmt = net.minecraft.ChatFormatting.DARK_GRAY;
                    fmtSource = "outsider";
                }
            } else {
                fmt = null;
                fmtSource = "(gate off — no override)";
            }

            MutableComponent line = Component.literal("  ")
                    .append(Component.literal(username).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  registry=" + (entry != null ? "hit" : "miss"))
                            .withStyle(entry != null ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))
                    .append(Component.literal("  tier=" + tier).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("  role=" + role).withStyle(ChatFormatting.GRAY));
            if (fmt != null) {
                line.append(Component.literal("  → ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(username).withStyle(fmt))
                        .append(Component.literal("  (" + fmt.getName() + " via " + fmtSource + ")")
                                .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                line.append(Component.literal("  → ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(fmtSource).withStyle(ChatFormatting.DARK_GRAY));
            }
            ChatUtils.sendLocalMessage(line);
        }
        return 1;
    }

    /**
     * {@code /wv debug trigger bossBarsDump} — snapshot of vanilla's
     * {@code BossHealthOverlay#events} map.
     *
     * <p>Reaches into the private map via
     * {@link org.wynnvets.mixin.client.accessors.BossHealthOverlayAccessor}
     * (the same accessor the synthetic anni bar uses) and renders one chat
     * line per entry plus a header summarising our own state. Each line
     * shows the iteration index, a truncated UUID, the bar's {@code color}
     * and {@code overlay}, its current lerped {@code progress}, the
     * darken/music/fog flags, the raw name component (so PUA glyphs are
     * visible), and an {@code ← OURS} marker on the synthetic anni bar.
     *
     * <p>Goal: diagnose the "first-slot bar visual disappears" Wynncraft
     * quirk. The dump shows whether ours is at iteration index 0
     * (suppressed) or a later index (visible), and what colour/overlay
     * combinations the Wynncraft-issued first-slot bars are using.
     */
    private static int triggerBossBarsDump(CommandContext<FabricClientCommandSource> ctx) {
        java.util.UUID ourUuid = org.wynnvets.mwe.anni.bossbar.VetsBossBarManager.barUuid();
        boolean ourActive = org.wynnvets.mwe.anni.bossbar.VetsBossBarManager.isActive();

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.gui.components.BossHealthOverlay overlay =
                (mc != null && mc.gui != null) ? mc.gui.getBossOverlay() : null;
        if (overlay == null) {
            ChatUtils.sendLocalMessage(Component.literal(
                    "bossBarsDump: no BossHealthOverlay (mc.gui not initialised)")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        java.util.Map<java.util.UUID, net.minecraft.client.gui.components.LerpingBossEvent> events;
        try {
            events = ((org.wynnvets.mixin.client.accessors.BossHealthOverlayAccessor)
                    (Object) overlay).getEvents();
        } catch (ClassCastException e) {
            ChatUtils.sendLocalMessage(Component.literal(
                    "bossBarsDump: BossHealthOverlayAccessor mixin not applied (" + e.getMessage() + ")")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        ChatUtils.sendLocalMessageNewBlock(Component.literal(
                "BossHealthOverlay events dump — " + events.size() + " entr"
                        + (events.size() == 1 ? "y" : "ies")
                        + ", VetsBossBarManager.isActive=" + ourActive)
                .withStyle(ChatFormatting.GOLD));

        if (events.isEmpty()) {
            ChatUtils.sendLocalMessage(Component.literal(
                    "  (no boss bars currently tracked)")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return 1;
        }

        int idx = 0;
        for (java.util.Map.Entry<java.util.UUID, net.minecraft.client.gui.components.LerpingBossEvent> entry
                : events.entrySet()) {
            net.minecraft.client.gui.components.LerpingBossEvent ev = entry.getValue();
            java.util.UUID uuid = entry.getKey();
            String uuidShort = uuid.toString().substring(0, 8);
            boolean isOurs = uuid.equals(ourUuid);
            String slotMark = (idx == 0) ? " [SLOT-1]" : "";
            String oursMark = isOurs ? "  ← OURS" : "";

            // Header line: index + UUID + flags
            MutableComponent header = Component.literal("#" + idx + slotMark)
                    .withStyle(isOurs ? ChatFormatting.AQUA : ChatFormatting.WHITE)
                    .append(Component.literal(" uuid=" + uuidShort)
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(oursMark)
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            ChatUtils.sendLocalMessage(header);

            // Properties line: color, overlay, progress, flags
            ChatUtils.sendLocalMessage(
                    Component.literal("    color=" + ev.getColor().name()
                            + "  overlay=" + ev.getOverlay().name()
                            + "  progress=" + String.format(java.util.Locale.ROOT, "%.4f", ev.getProgress())
                            + "  darken=" + ev.shouldDarkenScreen()
                            + "  music=" + ev.shouldPlayBossMusic()
                            + "  fog=" + ev.shouldCreateWorldFog())
                            .withStyle(ChatFormatting.GRAY));

            // Name as raw component (so PUA / negative-width glyphs render
            // verbatim — if Wynncraft is using bar-suppressing glyphs in
            // the name, they show up here).
            ChatUtils.sendLocalMessage(
                    Component.literal("    name= ").withStyle(ChatFormatting.DARK_GRAY)
                            .append(ev.getName().copy()));

            idx++;
        }
        return 1;
    }

    /**
     * {@code /wv debug trigger zoneLinesDump} — diagnostic for the S5 zone-line
     * renderer. Dumps the aggressive gate, every cached {@link
     * org.wynnvets.mwe.anni.zone.AnniZone.Disc disc}, and the squared
     * distance to the player so a "why aren't lines rendering" investigation
     * can rule in/out each component (gate, cold cache, distance culling).
     */
    private static void triggerZoneLinesDump() {
        boolean aggro = org.wynnvets.mwe.anni.aggressive.AnniAggressiveTicker.isAggressiveActive();
        boolean toggle = org.wynnvets.config.VetsConfig.get(
                org.wynnvets.config.VetsConfig.VETS_ANNI_ZONE_LINES);
        boolean cold = org.wynnvets.mwe.anni.zone.AnniZone.isCold();
        java.util.List<org.wynnvets.mwe.anni.zone.AnniZone.Disc> discs =
                org.wynnvets.mwe.anni.zone.AnniZone.getDiscs();

        ChatUtils.sendLocalMessageNewBlock(Component.literal(
                "AnniZoneLineRenderer dump — aggressive_active=" + aggro
                        + " toggle_on=" + toggle
                        + " zone_cold=" + cold
                        + " discs=" + discs.size())
                .withStyle(ChatFormatting.GOLD));

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer player = mc != null ? mc.player : null;
        double px = player != null ? player.getX() : 0.0;
        double pz = player != null ? player.getZ() : 0.0;

        if (discs.isEmpty()) {
            ChatUtils.sendLocalMessage(Component.literal(
                    "  (no discs cached — anni event missing from world-events API?)")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        int i = 0;
        for (org.wynnvets.mwe.anni.zone.AnniZone.Disc disc : discs) {
            double dx = px - disc.x();
            double dz = pz - disc.z();
            double distSq = dx * dx + dz * dz;
            ChatUtils.sendLocalMessage(Component.literal(
                    String.format("  [%d] centre=(%.0f,%.0f) r=%.0f distSq=%.0f",
                            i, disc.x(), disc.z(), disc.radius(), distSq))
                    .withStyle(ChatFormatting.GRAY));
            i++;
        }
    }
}
