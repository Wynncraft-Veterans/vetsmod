package org.wynnvets.debug.anni;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;

/**
 * The highlight half of the {@code /wv debug tree anni} harness: debug hooks for the S4 player
 * highlight overlay, that is {@code AnniOutlineRegistry} and {@code AnniOutlineTicker}'s gate.
 *
 * <p>{@code zone enter|exit} sets or removes the ticker's in-zone override, which only the
 * highlight gate reads. The {@code registry} handlers put one player into the registry, drop one,
 * or drop every entry, through the registry's debug methods and {@code clearAll}; the next
 * snapshot rebuild replaces whatever they leave there.</p>
 *
 * <p>Every handler opens with {@code AnniDebugCommands.requireDebug}.</p>
 */
final class AnniHighlightDebugCommands {

    private AnniHighlightDebugCommands() {}

    /** {@code /wv debug tree anni zone <enter|exit>} — set the in-zone
     *  override on {@link org.wynnvets.mwe.anni.outline.AnniOutlineTicker
     *  AnniOutlineTicker}. {@code enter} makes the zone check pass
     *  regardless of position; {@code exit} removes the override so the
     *  real position check applies again — it does not force the player
     *  out of the zone. The action is case-insensitive; not persisted.
     *
     *  <p>The zone check is one condition of the S4 highlight gate; the
     *  others (a non-silent mode, an outline or nametag toggle, a snapshot
     *  with a stamp, the time window) still apply. Only that ticker reads
     *  the override: every other in-zone check (the boss bar, the T-5m
     *  zone-absence alert, the ghosts prompt) calls
     *  {@link org.wynnvets.mwe.anni.zone.AnniZone#isInZone(double, double)
     *  AnniZone.isInZone} directly and ignores it.</p> */
    static int zone(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;
        String action = StringArgumentType.getString(ctx, "action");
        boolean enter = "enter".equalsIgnoreCase(action);
        boolean exit = "exit".equalsIgnoreCase(action);
        if (!enter && !exit) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni zone: action must be enter or exit")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        org.wynnvets.mwe.anni.outline.AnniOutlineTicker.setForceInZone(enter);
        ChatUtils.sendLocalMessage(
                Component.literal(
                                "anni zone "
                                        + action.toLowerCase()
                                        + ": forceInZone="
                                        + enter
                                        + " (S4 highlights gate)")
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni registry set <username> <role>} — inject
     *  one player into the highlight registry. Picks the OWN_PARTY tier
     *  with the role's spec colour ({@code PRIMARY} = red, {@code TANK} =
     *  aqua, etc.), or the OTHER_VETS_PARTY tier (light grey) for the
     *  {@code other} alias. The injection is wiped by the next snapshot
     *  rebuild — re-run after any {@code snapshot inject} / {@code clear}.
     *  Renders only while the activation gate holds, so we report whether
     *  it does and remind the caller what else to flip if it doesn't. */
    static int registrySet(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;

        String username = StringArgumentType.getString(ctx, "username");
        String role = StringArgumentType.getString(ctx, "role");

        if (username == null || username.isEmpty()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("anni registry set: username cannot be empty")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.Entry entry;
        String normalised = role == null ? "" : role.trim();
        if ("other".equalsIgnoreCase(normalised)) {
            entry = org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.debugOtherPartyEntry();
        } else {
            // null-tolerant — chatFormattingForRole falls back to GRAY on
            // unknown codes, which is also a useful test case.
            entry =
                    org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.debugOwnPartyEntry(
                            normalised);
        }
        org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.debugSet(username, entry);

        boolean gateActive =
                org.wynnvets.mwe.anni.outline.AnniOutlineTicker.isOutlineSuppressionActive();
        ChatFormatting fmt = entry.nametagFormatting();
        String roleLabel =
                entry.role() == null
                        ? entry.tier().name().toLowerCase()
                        : entry.role().toUpperCase();
        Component msg =
                Component.literal("anni registry set: ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(username).withStyle(fmt))
                        .append(
                                Component.literal(" -> " + roleLabel)
                                        .withStyle(ChatFormatting.GREEN));
        if (gateActive) {
            msg =
                    msg.copy()
                            .append(
                                    Component.literal("  [gate active]")
                                            .withStyle(ChatFormatting.GRAY));
        } else {
            msg =
                    msg.copy()
                            .append(
                                    Component.literal(
                                                    "  [gate INACTIVE — flip outlines/nametags on, set a non-silent mode, "
                                                            + "inject a snapshot, and /wv debug tree anni zone enter]")
                                            .withStyle(ChatFormatting.YELLOW));
        }
        ChatUtils.sendLocalMessage(msg);
        return 1;
    }

    /** {@code /wv debug tree anni registry clear <username>} — drop a
     *  single entry, debug-injected or snapshot-derived; a derived one
     *  returns on the next snapshot rebuild if that snapshot still lists
     *  the player. No-op if the username isn't present. */
    static int registryClear(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;
        String username = StringArgumentType.getString(ctx, "username");
        org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.debugRemove(username);
        ChatUtils.sendLocalMessage(
                Component.literal("anni registry clear: " + username)
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** {@code /wv debug tree anni registry clearall} — drop every entry,
     *  including any naturally-derived from the current snapshot. The
     *  next snapshot push (or {@code snapshot refresh}) rebuilds. Today no
     *  {@code anni_state} push reaches vetsmod
     *  ({@code outbound-socket-never-authenticated}); the rebuild comes with
     *  the next cache write, which is a pull that returns a snapshot (a
     *  successful {@code snapshot refresh}, say) or a debug command. */
    static int registryClearAll(CommandContext<FabricClientCommandSource> ctx) {
        int gate = AnniDebugCommands.requireDebug(ctx);
        if (gate == 0) return 0;
        int n = org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.size();
        org.wynnvets.mwe.anni.outline.AnniOutlineRegistry.clearAll();
        ChatUtils.sendLocalMessage(
                Component.literal("anni registry clear all: dropped " + n + " entries")
                        .withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
