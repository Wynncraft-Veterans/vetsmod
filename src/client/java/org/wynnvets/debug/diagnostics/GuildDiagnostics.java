package org.wynnvets.debug.diagnostics;

import com.wynntils.core.components.Models;
import com.wynntils.models.guild.type.GuildRank;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.guild.GuildChecker;
import org.wynnvets.guild.GuildStateManager;
import org.wynnvets.guild.WorldJoinAnnouncer;
import org.wynnvets.logging.VetsLogger;

/**
 * The {@code /wv debug trigger forceChecks} readout: what Wynntils and vetsmod each hold about the
 * player's guild and staff rank, printed to chat, then a fresh staff-rank refresh and a fresh
 * {@code /gu stats} check, each skipped if one is already in flight.
 *
 * <p>It re-requests both checks but clears no cached result. {@code GuildChecker}'s stored result
 * stays in place until the new {@code /gu stats} reply replaces it, unlike after a Wynntils guild
 * event, or a post-world-join recheck poll once Wynntils reports a guild, which clear it.</p>
 */
public final class GuildDiagnostics {

    private GuildDiagnostics() {}

    /**
     * Reads guild membership and rank from Wynntils' {@code Models.Guild}
     * and reports them to chat alongside vetsmod's own guild and staff
     * state.  If Wynntils reports a guild, re-evaluates dependent state
     * directly; it deliberately does not call {@link
     * org.wynnvets.guild.GuildLifecycle#onGuildInfoUpdated() GuildLifecycle#onGuildInfoUpdated()},
     * which would clear {@code GuildChecker}.  Then forces a staff rank
     * refresh and starts our own {@code /gu stats} guild check, each
     * skipped if one is already in flight.
     *
     * <p>Intended for use from {@code /wv debug trigger forceChecks}.</p>
     */
    public static void forceGuildRecheck() {
        if (!GuildStateManager.isWynntilsReady()) {
            ChatUtils.sendLocalMessage(
                    Component.literal("Wynntils is not ready yet - cannot check guild state.")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        String guildName = Models.Guild.getGuildName();
        boolean inGuild = Models.Guild.isInGuild();
        GuildRank rank = Models.Guild.getGuildRank();

        ChatUtils.sendLocalMessage(
                Component.literal("Force Guild Check Results:").withStyle(ChatFormatting.GOLD));

        sendDiagLine(
                "Wynntils Guild Name",
                guildName.isEmpty() ? "(empty)" : guildName,
                guildName.isEmpty() ? ChatFormatting.RED : ChatFormatting.GREEN);
        sendDiagLine(
                "Wynntils isInGuild",
                String.valueOf(inGuild),
                inGuild ? ChatFormatting.GREEN : ChatFormatting.RED);
        sendDiagLine(
                "Wynntils Guild Rank",
                rank == null ? "(null)" : rank.name(),
                rank == null ? ChatFormatting.RED : ChatFormatting.GREEN);

        // GuildChecker state
        GuildChecker.GuildCheckResult gcResult = GuildChecker.getResult();
        boolean gcValid = GuildChecker.hasValidResult();
        long gcAge =
                GuildChecker.getLastCheckTime() > 0
                        ? (System.currentTimeMillis() - GuildChecker.getLastCheckTime()) / 1000
                        : -1;

        sendDiagLine(
                "GuildChecker result",
                gcResult.name() + (gcValid ? "" : " (expired)"),
                gcValid ? ChatFormatting.GREEN : ChatFormatting.RED);
        if (gcAge >= 0) {
            String ageStr =
                    gcAge < 3600
                            ? gcAge + "s"
                            : gcAge < 86400 ? (gcAge / 3600) + "h" : (gcAge / 86400) + "d";
            sendDiagLine("GuildChecker age", ageStr, ChatFormatting.AQUA);
        }

        sendDiagLine(
                "VetsMod isReturners",
                String.valueOf(GuildStateManager.isReturners()),
                GuildStateManager.isReturners() ? ChatFormatting.GREEN : ChatFormatting.RED);
        sendDiagLine(
                "VetsMod isGuildless",
                String.valueOf(GuildStateManager.isGuildless()),
                GuildStateManager.isGuildless() ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        sendDiagLine(
                "VetsMod isStaff",
                String.valueOf(GuildStateManager.isStaff()),
                GuildStateManager.isStaff() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        sendDiagLine(
                "VetsMod selfStaffRank",
                GuildStateManager.selfStaffRank().isEmpty()
                        ? "(none)"
                        : GuildStateManager.selfStaffRank(),
                ChatFormatting.AQUA);

        // Trigger guild info update path without clearing GuildChecker: only
        // onGuildInfoUpdated() clears it (Wynntils' guild events and the
        // post-world-join recheck poll), and a forced recheck is neither.
        if (inGuild) {
            VetsLogger.info("forceGuildRecheck: Wynntils has guild info, re-evaluating");
            // Don't call onGuildInfoUpdated() here as it clears GuildChecker.
            // Just re-evaluate dependent state directly.
            if (GuildStateManager.isReturners()) {
                WorldJoinAnnouncer.fetchAndDisplayStampMessage();
            }
            GuildStateManager.sendRegistrationIfReady();
        }

        // Force staff rank refresh regardless of cooldown
        boolean staffRefreshStarted = GuildStateManager.refreshStaffStatusIfNeeded(true);
        sendDiagLine(
                "Staff rank refresh",
                staffRefreshStarted ? "started" : "already in progress",
                staffRefreshStarted ? ChatFormatting.GREEN : ChatFormatting.YELLOW);

        // Run our own /gu stats check whatever moreReliableGuildCheck says
        // (skipped if one is already in flight)
        boolean guildCheckStarted = GuildChecker.refreshGuildStatus();
        sendDiagLine(
                "Guild check (/gu stats)",
                guildCheckStarted ? "started" : "already in progress",
                guildCheckStarted ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    private static void sendDiagLine(String label, String value, ChatFormatting color) {
        ChatUtils.sendLocalMessage(
                Component.literal("  " + label + ": ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(value).withStyle(color)));
    }
}
