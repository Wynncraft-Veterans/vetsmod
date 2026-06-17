package org.wynnvets.mwe.anni.render;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import org.wynnvets.config.VetsConfig;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

import java.time.Instant;
import java.util.Locale;

/**
 * World-join anni-motd line for vets-anni users.
 *
 * <p>Two-line format following a {@code [VETSMOD]} prepend:</p>
 *
 * <pre>
 * Annihilation returns in 7.2h!
 * You aren't yet assigned and are hard RSVP'd!
 * </pre>
 *
 * <p>or, when on a party:</p>
 *
 * <pre>
 * Annihilation returns in 7.2h!
 * You have been assigned to TANK with Party 2 on EU5
 * </pre>
 *
 * <p>The short-form RSVP codes (HRSVP / SRSVP / WALKIN / LATE) and
 * compact party chips were the wrong call here — those abbreviations
 * exist for boss-bar space limits and out of place in chat. Chat has
 * room to spell things out.</p>
 *
 * <p>External users (per
 * {@link AnniCommandRenderer#isExternal(AnniSnapshot)}) fall back to
 * the legacy stamp message — we return {@code null} so the caller's
 * legacy path takes over. Spec §"For external users": "keep the wv
 * anni and anni-motd as is."</p>
 *
 * <p>Time format: hours with one decimal ({@code 7.2h}). For very
 * short remaining time (&lt;6 minutes) the format drops to minutes
 * ({@code 5m}) to avoid {@code 0.1h} reading awkwardly.</p>
 */
public final class AnniMotdRenderer {

    private AnniMotdRenderer() {
    }

    /**
     * Build the motd component, or {@code null} when nothing should
     * auto-display. {@code null} causes the caller to fall back to the
     * legacy stamp text (which itself returns null for past stamps,
     * giving silent no-display behaviour at world-join).
     */
    public static MutableComponent render(AnniSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        // External users fall back to the legacy stamp message — spec
        // §"For external users": "keep the wv anni and anni-motd as is".
        if (AnniCommandRenderer.isExternal(snapshot)) {
            return null;
        }
        AnniSnapshot.Event event = snapshot.event();
        if (event == null) {
            return null;
        }
        Long stamp = event.stampEpoch();
        long now = Instant.now().getEpochSecond();
        if (stamp == null || stamp <= now || !event.announced()) {
            return null;
        }
        long secondsUntil = stamp - now;

        // Wrap in an empty-styled root so line 1's bold doesn't bleed
        // through to line 2 — children inherit unspecified style fields
        // from the parent, and line 2's sub-segments only set color.
        MutableComponent root = Component.literal("");
        root.append(Component.literal(
                        "Annihilation returns in " + formatHours(secondsUntil) + "!")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

        // vetsAnniPromptRsvp acts as the "show line 2" flag — when off,
        // the user gets only the timing line.
        if (!VetsConfig.get(VetsConfig.VETS_ANNI_PROMPT_RSVP)) {
            return root;
        }
        MutableComponent line2 = buildStatusLine(snapshot);
        if (line2 == null) {
            return root;
        }
        return root
                .append(Component.literal("\n"))
                .append(line2);
    }

    /** Line 2 — describes the player's current placement / RSVP state. */
    private static MutableComponent buildStatusLine(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        String state = board == null || board.state() == null
                ? null : board.state().toLowerCase(Locale.ROOT);
        if (state == null) {
            return null;
        }
        switch (state) {
            case "party":   return assignedToPartyLine(snapshot);
            case "unassigned": return unassignedLine(snapshot);
            case "wont_assign":  return wontAssignLine(snapshot);
            case "unplaced":     return unplacedLine(snapshot);
            default:             return null;
        }
    }

    /** "You have been assigned to TANK with Party 2 on EU5". */
    private static MutableComponent assignedToPartyLine(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        AnniSnapshot.Party party = board != null ? board.party() : null;
        if (party == null) {
            return Component.literal("You have been assigned to a party (details pending).")
                    .withStyle(ChatFormatting.GRAY);
        }
        String roleCode = board.role();
        MutableComponent line = Component.literal("You have been assigned to ")
                .withStyle(ChatFormatting.GRAY);
        if (roleCode != null) {
            line.append(Component.literal(AnniHoverBuilder.displayRole(roleCode))
                    .withStyle(AnniHoverBuilder.roleColor(roleCode), ChatFormatting.BOLD));
        } else {
            line.append(Component.literal("a role")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD));
        }
        line.append(Component.literal(" with ").withStyle(ChatFormatting.GRAY));
        line.append(Component.literal("Party " + party.ordinal())
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        if (party.world() != null && !party.world().isEmpty()) {
            line.append(Component.literal(" on ").withStyle(ChatFormatting.GRAY));
            line.append(Component.literal(party.world())
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        }
        return line;
    }

    /** "You aren't yet assigned and are hard RSVP'd!" */
    private static MutableComponent unassignedLine(AnniSnapshot snapshot) {
        return Component.literal("You aren't yet assigned and " + rsvpClause(snapshot) + "!")
                .withStyle(ChatFormatting.GRAY);
    }

    /** "You won't be assigned — <reason>." (when wont_reason is present)
     *  or "You won't be assigned to this anni." */
    private static MutableComponent wontAssignLine(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        String reason = board != null ? board.wontReason() : null;
        if (reason != null && !reason.isEmpty()) {
            return Component.literal("You won't be assigned — " + reason + ".")
                    .withStyle(ChatFormatting.GRAY);
        }
        return Component.literal("You won't be assigned to this anni.")
                .withStyle(ChatFormatting.GRAY);
    }

    /** "You're not yet on the placement queue and " + RSVP clause. */
    private static MutableComponent unplacedLine(AnniSnapshot snapshot) {
        return Component.literal("You're not yet on the placement queue and " + rsvpClause(snapshot) + "!")
                .withStyle(ChatFormatting.GRAY);
    }

    /** "are hard RSVP'd" / "are soft RSVP'd" / "are walking in early"
     *  / "are walking in late" / "have not RSVP'd". */
    private static String rsvpClause(AnniSnapshot snapshot) {
        AnniSnapshot.Rsvp rsvp = snapshot.rsvp();
        AnniSnapshot.Attendance attendance = snapshot.attendance();
        String noticeKey = null;
        if (rsvp != null && rsvp.notice() != null && !rsvp.revoked()) {
            noticeKey = rsvp.notice();
        } else if (attendance != null && attendance.noticeEffective() != null) {
            noticeKey = attendance.noticeEffective();
        }
        if (noticeKey == null) return "have not RSVP'd";
        switch (noticeKey.toLowerCase(Locale.ROOT)) {
            case "hard":
            case "rsvp_hard":    return "are hard RSVP'd";
            case "soft":
            case "rsvp_soft":    return "are soft RSVP'd";
            case "attend_early":
            case "walkin":
            case "walk_in":      return "are walking in early";
            case "attend_late":
            case "late":         return "are walking in late";
            default:             return "have an unrecognised RSVP";
        }
    }

    /** "7.2h" / "0.5h" / "5m" — one-decimal hours, minutes when small
     *  enough that the decimal-hours read would be awkward. */
    private static String formatHours(long seconds) {
        if (seconds <= 0) return "now";
        double hours = seconds / 3600.0;
        if (hours >= 0.1) {
            return String.format(Locale.ROOT, "%.1fh", hours);
        }
        long minutes = Math.max(1L, seconds / 60L);
        return minutes + "m";
    }
}
