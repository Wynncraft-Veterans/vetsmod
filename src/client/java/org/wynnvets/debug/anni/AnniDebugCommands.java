package org.wynnvets.debug.anni;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.wynntils.utils.mc.McUtils;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.logging.VetsLogger;
import org.wynnvets.mwe.anni.state.AnniSnapshot;
import org.wynnvets.mwe.anni.state.AnniSnapshotCache;

/**
 * {@code /wv debug tree anni …} — simulation hooks for the MWE/anni subsystem.
 *
 * <p>Anni is infrequent and hard to test against live events; these commands
 * let a developer reproduce every render branch without waiting for a real
 * anni cycle. Gated by {@link VetsLogger#isDebugEnabled()} (not
 * {@code vetsAnniEnabled}, which gates only the snapshot-driven render paths in
 * {@link org.wynnvets.fetcher.ondemand.StampFetcher StampFetcher}) so the whole subsystem stays
 * testable while still being accidentally-safe (debug logging is off by
 * default, is opt-in via {@code /wv debug true}, and is not restored at
 * startup once three days have passed since it was switched on — that check
 * runs only at client init, so a session left running keeps it on).</p>
 *
 * <p>Two gates, and only one of them restricts who may call. All 24 handlers
 * open with {@code requireDebug}, which tests
 * {@link VetsLogger#isDebugEnabled()} — a flag any player sets with
 * {@code /wv debug true}, a node that carries no permission check of its own
 * — so it is a mode flag that refuses execution, not an
 * authorisation gate. It hides nothing: no node in this tree carries a
 * brigadier {@code requires(…)}, so the paths tab-complete whether or
 * not debug is on. The five {@code scrollspot} handlers add
 * {@code requireStaffOrOrganiser} (vetsmod staff, or the local player's UUID
 * in the cached snapshot's {@code organisers}); that is the one permission
 * check here, and it too is UX only — for the three that write to the
 * server, vets-anni makes the real decision.</p>
 *
 * <p>This class builds the tree and holds both gates, with the {@code flash},
 * {@code scrollspot}, {@code rsvp}, {@code alert} and {@code mode set} handlers.
 * The {@code snapshot}, {@code guess}, {@code external} and {@code time} handlers
 * are in {@link AnniSnapshotDebugCommands}, the {@code zone} and {@code registry}
 * handlers in {@link AnniHighlightDebugCommands}, and the tab-completion providers
 * in {@link AnniDebugSuggestions}. {@code rsvp} mirrors {@code /wv anni rsvp};
 * {@code scrollspot} mirrors nothing, because this tree is its only entry
 * point.</p>
 */
public final class AnniDebugCommands {

    private AnniDebugCommands() {}

    /** Build the {@code anni} literal, which the caller mounts under
     *  {@code /wv debug tree} — so every path here is
     *  {@code /wv debug tree anni …}. 11 top-level literals, 24 executable
     *  leaves, one handler per leaf, in this class or a sibling handler class.
     *  Caller is {@link org.wynnvets.debug.DebugCommands#buildCommandTree()
     *  DebugCommands#buildCommandTree()}. */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildCommandTree() {
        return ClientCommandManager.literal("anni")
                .then(
                        ClientCommandManager.literal("snapshot")
                                .then(
                                        ClientCommandManager.literal("inject")
                                                // Literal branches are tried before the
                                                // greedyString fallback, so `inject file …`
                                                // and `inject preset …` resolve cleanly even
                                                // though the inline form accepts anything.
                                                .then(
                                                        ClientCommandManager.literal("file")
                                                                .then(
                                                                        ClientCommandManager
                                                                                .argument(
                                                                                        "name",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        AnniDebugSuggestions
                                                                                                .SUGGEST_DUMP_FILES)
                                                                                .executes(
                                                                                        AnniSnapshotDebugCommands
                                                                                                ::snapshotInjectFile)))
                                                .then(
                                                        ClientCommandManager.literal("preset")
                                                                .then(
                                                                        ClientCommandManager
                                                                                .argument(
                                                                                        "name",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        AnniDebugSuggestions
                                                                                                .SUGGEST_PRESETS)
                                                                                .executes(
                                                                                        AnniSnapshotDebugCommands
                                                                                                ::snapshotInjectPreset)))
                                                .then(
                                                        ClientCommandManager.argument(
                                                                        "json",
                                                                        StringArgumentType
                                                                                .greedyString())
                                                                .executes(
                                                                        AnniSnapshotDebugCommands
                                                                                ::snapshotInject)))
                                .then(
                                        ClientCommandManager.literal("dump")
                                                .executes(AnniSnapshotDebugCommands::snapshotDump))
                                .then(
                                        ClientCommandManager.literal("clear")
                                                .executes(AnniSnapshotDebugCommands::snapshotClear))
                                .then(
                                        ClientCommandManager.literal("refresh")
                                                .executes(
                                                        AnniSnapshotDebugCommands
                                                                ::snapshotRefresh)))
                .then(
                        ClientCommandManager.literal("guess")
                                .executes(AnniSnapshotDebugCommands::guess))
                .then(
                        ClientCommandManager.literal("external")
                                .then(
                                        ClientCommandManager.argument(
                                                        "mode", StringArgumentType.word())
                                                .suggests(
                                                        AnniDebugSuggestions.SUGGEST_EXTERNAL_MODES)
                                                .executes(AnniSnapshotDebugCommands::externalSet)))
                .then(
                        ClientCommandManager.literal("time")
                                .then(
                                        ClientCommandManager.argument(
                                                        "seconds", StringArgumentType.word())
                                                .suggests(AnniDebugSuggestions.SUGGEST_TIME_OFFSETS)
                                                .executes(AnniSnapshotDebugCommands::timeSet)))
                .then(
                        ClientCommandManager.literal("zone")
                                .then(
                                        ClientCommandManager.argument(
                                                        "action", StringArgumentType.word())
                                                .suggests(AnniDebugSuggestions.SUGGEST_ZONE_ACTIONS)
                                                .executes(AnniHighlightDebugCommands::zone)))
                .then(
                        ClientCommandManager.literal("flash")
                                .then(
                                        ClientCommandManager.argument(
                                                        "field", StringArgumentType.word())
                                                .suggests(AnniDebugSuggestions.SUGGEST_FLASH_FIELDS)
                                                .executes(AnniDebugCommands::flash)))
                .then(
                        ClientCommandManager.literal("mode")
                                .then(
                                        ClientCommandManager.literal("set")
                                                .then(
                                                        ClientCommandManager.argument(
                                                                        "mode",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        AnniDebugSuggestions
                                                                                .SUGGEST_MODES)
                                                                .executes(
                                                                        AnniDebugCommands
                                                                                ::modeSet))))
                // S5 — scrollspot host-write. Lives under `/wv debug tree`
                // (rather than `/wv anni`) so it doesn't clutter
                // tab-complete for everyone else; staff hosts using this
                // already need debug logging on (gating below). `set`/
                // `here`/`clear` round-trip the server; `localinject` /
                // `localclear` bypass it for visual testing.
                .then(
                        ClientCommandManager.literal("scrollspot")
                                .then(
                                        ClientCommandManager.literal("set")
                                                .then(
                                                        ClientCommandManager.argument(
                                                                        "x",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .IntegerArgumentType
                                                                                .integer())
                                                                .then(
                                                                        ClientCommandManager
                                                                                .argument(
                                                                                        "y",
                                                                                        com.mojang
                                                                                                .brigadier
                                                                                                .arguments
                                                                                                .IntegerArgumentType
                                                                                                .integer())
                                                                                .then(
                                                                                        ClientCommandManager
                                                                                                .argument(
                                                                                                        "z",
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .integer())
                                                                                                .executes(
                                                                                                        AnniDebugCommands
                                                                                                                ::scrollspotSet)))))
                                .then(
                                        ClientCommandManager.literal("here")
                                                .executes(AnniDebugCommands::scrollspotHere))
                                .then(
                                        ClientCommandManager.literal("clear")
                                                .executes(AnniDebugCommands::scrollspotClear))
                                .then(
                                        ClientCommandManager.literal("localinject")
                                                .then(
                                                        ClientCommandManager.argument(
                                                                        "x",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .IntegerArgumentType
                                                                                .integer())
                                                                .then(
                                                                        ClientCommandManager
                                                                                .argument(
                                                                                        "y",
                                                                                        com.mojang
                                                                                                .brigadier
                                                                                                .arguments
                                                                                                .IntegerArgumentType
                                                                                                .integer())
                                                                                .then(
                                                                                        ClientCommandManager
                                                                                                .argument(
                                                                                                        "z",
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .integer())
                                                                                                .executes(
                                                                                                        AnniDebugCommands
                                                                                                                ::scrollspotLocalInject)))))
                                .then(
                                        ClientCommandManager.literal("localclear")
                                                .executes(AnniDebugCommands::scrollspotLocalClear)))
                // S5 — alert debug: fire a synthesised chat alert
                // without contriving a snapshot diff. Bypasses the 5s
                // cooldown so back-to-back invocations work.
                .then(
                        ClientCommandManager.literal("alert")
                                .then(
                                        ClientCommandManager.argument(
                                                        "field", StringArgumentType.word())
                                                .suggests(AnniDebugSuggestions.SUGGEST_ALERT_FIELDS)
                                                .executes(AnniDebugCommands::alert)))
                // S6 — rsvp debug mirror. Same three subcommands as the
                // main `/wv anni rsvp` tree, gated on requireDebug only
                // (the action only affects the caller's own RSVP, so no
                // staff/organiser perm is required). The main brigadier
                // command is preferred. This is the mirror: scrollspot,
                // above, has no main-tree registration and lives only
                // here, while rsvp is registered in both trees.
                .then(
                        ClientCommandManager.literal("rsvp")
                                .then(
                                        ClientCommandManager.literal("hard")
                                                .executes(AnniDebugCommands::rsvpHard))
                                .then(
                                        ClientCommandManager.literal("soft")
                                                .executes(AnniDebugCommands::rsvpSoft))
                                .then(
                                        ClientCommandManager.literal("revoke")
                                                .executes(AnniDebugCommands::rsvpRevoke)))
                // Arbitrary registry injection for visually verifying the
                // S4 highlight + nametag branches without coordinating a
                // real anni party. Wiped on the next snapshot rebuild —
                // re-inject after `snapshot inject` / `snapshot clear`.
                .then(
                        ClientCommandManager.literal("registry")
                                .then(
                                        ClientCommandManager.literal("set")
                                                .then(
                                                        ClientCommandManager.argument(
                                                                        "username",
                                                                        StringArgumentType.word())
                                                                .then(
                                                                        ClientCommandManager
                                                                                .argument(
                                                                                        "role",
                                                                                        StringArgumentType
                                                                                                .word())
                                                                                .suggests(
                                                                                        AnniDebugSuggestions
                                                                                                .SUGGEST_REGISTRY_ROLES)
                                                                                .executes(
                                                                                        AnniHighlightDebugCommands
                                                                                                ::registrySet))))
                                .then(
                                        ClientCommandManager.literal("clear")
                                                .then(
                                                        ClientCommandManager.argument(
                                                                        "username",
                                                                        StringArgumentType.word())
                                                                .executes(
                                                                        AnniHighlightDebugCommands
                                                                                ::registryClear)))
                                .then(
                                        ClientCommandManager.literal("clearall")
                                                .executes(
                                                        AnniHighlightDebugCommands
                                                                ::registryClearAll)));
    }

    // ──────────────────────────────────────────────────────────── gating

    /** Print a "debug logging required" notice and return 0; used by every
     *  command in this tree to short-circuit when debug logging is off. */
    // Package-private for the sibling handler classes, which open every handler with it.
    static int requireDebug(CommandContext<FabricClientCommandSource> ctx) {
        if (VetsLogger.isDebugEnabled()) {
            return -1; // sentinel: caller proceeds
        }
        ChatUtils.sendLocalMessage(
                Component.literal(
                                "anni debug commands require debug logging - run /wv debug true first")
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

    /** Whether the local player's UUID appears, case-insensitively, in the
     *  cached snapshot's {@code organisers} — the organiser half of
     *  {@code requireStaffOrOrganiser}. {@code false} if the cache is
     *  empty or there is no local player. Reads
     *  {@link AnniSnapshotCache#latest()}, so an injected snapshot counts
     *  the same as a real one — one more reason that gate is UX only. */
    private static boolean isLocalPlayerOrganiser() {
        AnniSnapshot snap = AnniSnapshotCache.latest();
        if (snap == null) return false;
        Minecraft mc = McUtils.mc();
        if (mc == null || mc.player == null) return false;
        String localUuid = mc.player.getUUID().toString();
        for (String orgUuid : snap.organisers()) {
            if (orgUuid != null && orgUuid.equalsIgnoreCase(localUuid)) {
                return true;
            }
        }
        return false;
    }

    // ───────────────────────────────────────── S3+ consumers

    /** {@code /wv debug tree anni flash <field>} — force a FlashTracker
     *  pulse for one of {@code role|party|world|rsvp}. Useful for
     *  visually verifying the {@code &l ↔ &n&l} pulse + sound without
     *  having to ginger a snapshot diff. */
    private static int flash(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String field = StringArgumentType.getString(ctx, "field");
        String normalised = field == null ? "" : field.toLowerCase(Locale.ROOT);
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
                        Component.literal(
                                        "anni flash: field must be one of role|party|world|rsvp, got "
                                                + field)
                                .withStyle(ChatFormatting.RED));
                return 0;
        }
    }

    /** {@code /wv debug tree anni scrollspot set <x> <y> <z>} — host
     *  write: sends an {@code anni_scrollspot_set} frame to the server.
     *  Delegates to {@link org.wynnvets.mwe.anni.command.AnniScrollspotCommand#set}.
     *  The server verifies the actor is the host of their assigned party
     *  via vets-anni's {@code anni-party-scrollspot} endpoint. */
    private static int scrollspotSet(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.command.AnniScrollspotCommand.set(ctx);
    }

    /** {@code /wv debug tree anni scrollspot here} — host write using the
     *  player's current block-position. */
    private static int scrollspotHere(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.command.AnniScrollspotCommand.here(ctx);
    }

    /** {@code /wv debug tree anni scrollspot clear} — host write that
     *  clears the party's scroll spot. */
    private static int scrollspotClear(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        return org.wynnvets.mwe.anni.command.AnniScrollspotCommand.clear(ctx);
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
        org.wynnvets.mwe.anni.ScrollSpotMarkerProvider.get().debugSet(x, y, z);
        ChatUtils.sendLocalMessage(
                Component.literal("scrollspot local-inject: " + x + " " + y + " " + z)
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni scrollspot localclear} — clear the
     *  local marker, whether it came from {@code localinject} or from the
     *  latest snapshot (the party's scroll spot or the in-party default);
     *  the next snapshot update recomputes it. Never touches the server. */
    private static int scrollspotLocalClear(CommandContext<FabricClientCommandSource> ctx) {
        if (requireDebug(ctx) == 0) return 0;
        if (requireStaffOrOrganiser(ctx) == 0) return 0;
        org.wynnvets.mwe.anni.ScrollSpotMarkerProvider.get().debugClear();
        ChatUtils.sendLocalMessage(
                Component.literal("scrollspot local cleared").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni rsvp hard} — debug mirror of
     *  {@code /wv anni rsvp hard}. Identical effect: both paths call
     *  {@link org.wynnvets.mwe.anni.command.AnniRsvpCommand#hard AnniRsvpCommand#hard},
     *  and this one adds only the {@code requireDebug} check. {@code rsvp}
     *  is a true mirror of a main-tree command; {@code scrollspot} is not
     *  a mirror at all — it has no main-tree registration, and this tree is
     *  its only entry point. */
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
     *  alert. Verifies formatting / bouncing without contriving a
     *  snapshot diff. It cannot verify the cooldown:
     *  {@link org.wynnvets.mwe.anni.aggressive.AggressiveAlertDispatcher#forceAlert
     *  AggressiveAlertDispatcher#forceAlert} calls the alert emitters directly,
     *  bypassing both the 5s per-field cooldown and the aggressive-mode /
     *  chat-alerts gate. */
    private static int alert(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String field = StringArgumentType.getString(ctx, "field").toLowerCase(Locale.ROOT);
        org.wynnvets.mwe.anni.aggressive.AggressiveAlertDispatcher.forceAlert(field);
        ChatUtils.sendLocalMessage(
                Component.literal("anni alert " + field + " triggered")
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni mode set <silent|passive|aggressive>} —
     *  unconditional mode transition that bypasses the /stream mutex
     *  for testing rendering during a screen capture. */
    private static int modeSet(CommandContext<FabricClientCommandSource> ctx) {
        int gate = requireDebug(ctx);
        if (gate == 0) return 0;
        String mode = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
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
