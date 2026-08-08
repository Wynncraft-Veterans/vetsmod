package org.wynnvets.mwe.anni.render;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import org.wynnvets.config.VetsConfig;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

import java.net.URI;
import java.util.Locale;

/**
 * Widget helpers for the rich-text {@code /wv anni} / anni-motd render.
 *
 * <p>Centralises the colour tokens and hover/click decoration shared by
 * {@link AnniCommandRenderer} and {@link AnniMotdRenderer} so that the
 * two surfaces stay visually consistent. Methods return individual
 * {@link MutableComponent} segments — the renderers compose them into
 * full lines.</p>
 *
 * <p>Hover details are gated by
 * {@link VetsConfig#VETS_ANNI_SHOW_HOVER_DETAILS} (per the MWE plan's
 * Hard Rule #7): with the flag off, every helper still emits the
 * coloured text plus any click action, but skips the descriptive
 * tooltip. Click URLs are never suppressed — the spec wires them for
 * navigation, not decoration.</p>
 */
public final class AnniHoverBuilder {

    /** Anchor URL for the anni gameplay docs, used when a role entry
     *  lacks an explicit {@code url}. The role-specific anchors (e.g.
     *  {@code #tank}) come from the snapshot. */
    public static final String DOCS_ANNI_BASE = "https://www.wynnvets.org/docs/guild/anni/";
    public static final String DOCS_PREPARING = DOCS_ANNI_BASE + "#preparing";
    public static final String DOCS_ATTENDING = DOCS_ANNI_BASE + "#attending";
    public static final String DOCS_EVENT_GAMEPLAY = DOCS_ANNI_BASE + "#event-gameplay";

    private AnniHoverBuilder() {
    }

    // ── Colour tokens ───────────────────────────────────────────────────

    /** Per-role colour map from spec §"Player Highlights":
     *  FILL=&f, TANK=&b, HEAL/HEALER=&a, TERTIARY=&d, SECONDARY=&e,
     *  PRIMARY=&c. Unknown roles fall back to gray. */
    public static ChatFormatting roleColor(String role) {
        if (role == null) return ChatFormatting.GRAY;
        switch (role.toUpperCase(Locale.ROOT)) {
            case "FILL":      return ChatFormatting.WHITE;
            case "TANK":      return ChatFormatting.AQUA;
            case "HEAL":
            case "HEALER":    return ChatFormatting.GREEN;
            case "TERTIARY":  return ChatFormatting.LIGHT_PURPLE;
            case "SECONDARY": return ChatFormatting.YELLOW;
            case "PRIMARY":   return ChatFormatting.RED;
            default:          return ChatFormatting.GRAY;
        }
    }

    /** Notice → ChatFormatting:
     *  hard=AQUA (HRSVP), soft=GREEN (SRSVP), any walk-in=YELLOW,
     *  late walk-in=RED. Showing up without an RSVP is always YELLOW
     *  regardless of how early — RED is reserved for showing up late
     *  without an RSVP. */
    public static ChatFormatting noticeColor(String noticeEffective) {
        if (noticeEffective == null) return ChatFormatting.GRAY;
        switch (noticeEffective.toLowerCase(Locale.ROOT)) {
            case "rsvp_hard":
            case "hard":         return ChatFormatting.AQUA;
            case "rsvp_soft":
            case "soft":         return ChatFormatting.GREEN;
            case "attend_early":
            case "walkin":
            case "walk_in":      return ChatFormatting.YELLOW;
            case "attend_late":
            case "late":         return ChatFormatting.RED;
            default:             return ChatFormatting.GRAY;
        }
    }

    /** Attendance band → colour ramp:
     *  band 1 (worst) → DARK_RED, ..., band 6 (best) → GREEN.
     *  Mirrors the dashboard's general-module bottom bar. */
    public static ChatFormatting bandColor(int band) {
        switch (band) {
            case 6: return ChatFormatting.GREEN;
            case 5: return ChatFormatting.DARK_GREEN;
            case 4: return ChatFormatting.YELLOW;
            case 3: return ChatFormatting.GOLD;
            case 2: return ChatFormatting.RED;
            case 1: return ChatFormatting.DARK_RED;
            default: return ChatFormatting.GRAY;
        }
    }

    // ── Component widgets ───────────────────────────────────────────────

    /** Role chip — coloured role label, clickable to its docs anchor,
     *  hover-describing the role title. */
    public static MutableComponent roleChip(AnniSnapshot.RoleEntry role) {
        return roleChip(role.role(), role.title(), role.url());
    }

    /** Lower-level role chip overload — used when the renderer only has
     *  a role code (e.g. from {@code board.role}) and needs to derive
     *  the chip without a full {@link AnniSnapshot.RoleEntry} present. */
    public static MutableComponent roleChip(String roleCode, String title, String url) {
        String display = displayRole(roleCode);
        String href = (url != null && !url.isEmpty()) ? url : DOCS_EVENT_GAMEPLAY;
        Style style = Style.EMPTY
                .withColor(roleColor(roleCode))
                .withBold(true)
                .withClickEvent(safeOpenUrl(href));
        if (showHovers() && title != null && !title.isEmpty()) {
            style = style.withHoverEvent(new HoverEvent.ShowText(
                    Component.literal(title + "\n")
                            .withStyle(ChatFormatting.WHITE)
                            .append(Component.literal("Click to open the role guide.")
                                    .withStyle(ChatFormatting.GRAY))));
        }
        return Component.literal(display).withStyle(style);
    }

    /** Resolve a role code to its display string per the current
     *  {@link VetsConfig#VETS_ANNI_ROLE_STYLE} setting. Returns the
     *  uppercase role code unchanged when the style table doesn't
     *  cover it (forward-compat with new role codes added to the
     *  snapshot before this side is updated). */
    public static String displayRole(String roleCode) {
        if (roleCode == null || roleCode.isEmpty()) return "?";
        String upper = roleCode.toUpperCase(Locale.ROOT);
        String style = VetsConfig.getString(VetsConfig.VETS_ANNI_ROLE_STYLE);
        if (style == null) style = "descriptive";
        switch (style) {
            case "short":
                switch (upper) {
                    case "TANK":      return "TANK";
                    case "HEAL":
                    case "HEALER":    return "HEAL";
                    case "PRIMARY":   return "PRIM";
                    case "SECONDARY": return "SUNK";
                    case "TERTIARY":  return "MOBK";
                    case "FILL":      return "FILL";
                    default:          return upper;
                }
            case "formal":
                switch (upper) {
                    case "TANK":      return "TANK";
                    case "HEAL":
                    case "HEALER":    return "HEALER";
                    case "PRIMARY":   return "PRIMARY";
                    case "SECONDARY": return "SECONDARY";
                    case "TERTIARY":  return "TERTIARY";
                    case "FILL":      return "FILL";
                    default:          return upper;
                }
            case "descriptive":
            default:
                switch (upper) {
                    case "TANK":      return "TANK";
                    case "HEAL":
                    case "HEALER":    return "HEALER";
                    case "PRIMARY":   return "BOSSKILL";
                    case "SECONDARY": return "SUNKILL";
                    case "TERTIARY":  return "MOBKILL";
                    case "FILL":      return "FILL";
                    default:          return upper;
                }
        }
    }

    /** RSVP badge — short coloured pill describing the user's current
     *  RSVP / attendance notice. */
    public static MutableComponent rsvpBadge(AnniSnapshot.Rsvp rsvp,
                                             AnniSnapshot.Attendance attendance) {
        String noticeKey = null;
        if (rsvp != null && rsvp.notice() != null && !rsvp.revoked()) {
            noticeKey = rsvp.notice();
        } else if (attendance != null && attendance.noticeEffective() != null) {
            noticeKey = attendance.noticeEffective();
        }
        // Long form for chat-space callers (this badge). The short forms
        // (HRSVP / SRSVP / WALKIN / LATE) exist for boss-bar style
        // space-constrained UI surfaces and live with whichever
        // future renderer needs them; chat has space to spell it out.
        String label;
        if (noticeKey == null) {
            label = "NO RSVP";
        } else {
            switch (noticeKey.toLowerCase(Locale.ROOT)) {
                case "hard":
                case "rsvp_hard":    label = "HARD RSVP";       break;
                case "soft":
                case "rsvp_soft":    label = "SOFT RSVP";       break;
                case "attend_early":
                case "walkin":
                case "walk_in":      label = "EARLY WALK-IN";   break;
                case "attend_late":
                case "late":         label = "LATE WALK-IN";    break;
                default:             label = noticeKey.toUpperCase(Locale.ROOT);
            }
        }
        ChatFormatting color = noticeKey == null ? ChatFormatting.GRAY : noticeColor(noticeKey);
        Style style = Style.EMPTY.withColor(color).withBold(true);
        if (showHovers()) {
            String hoverBody = noticeKey == null
                    ? "You haven't RSVP'd to this anni.\nClick to /wv anni rsvp."
                    : "Effective notice: " + noticeKey + "\nClick to open the RSVP guide.";
            style = style
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal(hoverBody).withStyle(ChatFormatting.GRAY)))
                    .withClickEvent(safeOpenUrl(DOCS_ATTENDING));
        } else {
            style = style.withClickEvent(safeOpenUrl(DOCS_ATTENDING));
        }
        return Component.literal(label).withStyle(style);
    }

    /** Attendance bar — visual representation of band 1-6 plus the label. */
    public static MutableComponent attendanceBar(AnniSnapshot.Attendance attendance) {
        if (attendance == null) {
            return Component.literal("attendance: unknown").withStyle(ChatFormatting.GRAY);
        }
        int band = attendance.band();
        if (band < 1) band = 1;
        if (band > 6) band = 6;
        ChatFormatting fill = bandColor(band);
        StringBuilder bar = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            bar.append(i <= band ? "█" : "░");
        }
        Style barStyle = Style.EMPTY.withColor(fill);
        if (showHovers()) {
            String hover = "Band " + band + "/6"
                    + (attendance.label() != null ? " — " + attendance.label() : "")
                    + "\nMirrors the anni.wynnvets.org/me attendance bar.";
            barStyle = barStyle.withHoverEvent(new HoverEvent.ShowText(
                    Component.literal(hover).withStyle(ChatFormatting.GRAY)));
        }
        MutableComponent label = Component.literal(
                attendance.label() != null ? attendance.label() : ("band " + band))
                .withStyle(fill);
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(bar.toString()).withStyle(barStyle))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(label);
    }

    /** Coloured world chip — defaults to "TBD" when null. */
    public static MutableComponent partyWorldChip(String world) {
        if (world == null || world.isEmpty()) {
            return Component.literal("TBD").withStyle(ChatFormatting.GRAY);
        }
        Style style = Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true);
        if (showHovers()) {
            style = style.withHoverEvent(new HoverEvent.ShowText(
                    Component.literal("Party world assignment.\nFlashes until you join it.")
                            .withStyle(ChatFormatting.GRAY)));
        }
        return Component.literal(world).withStyle(style);
    }

    /** Party chip — "Party 3" coloured + clickable to the party host hover. */
    public static MutableComponent partyOrdinalChip(AnniSnapshot.Party party) {
        if (party == null) {
            return Component.literal("TBD").withStyle(ChatFormatting.GRAY);
        }
        String label = "Party " + party.ordinal();
        Style style = Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true);
        if (showHovers()) {
            MutableComponent hover = Component.literal(label + "\n").withStyle(ChatFormatting.WHITE);
            if (party.host() != null && party.host().username() != null) {
                hover.append(Component.literal("Host: " + party.host().username() + "\n")
                        .withStyle(ChatFormatting.GRAY));
            }
            if (party.members() != null && !party.members().isEmpty()) {
                hover.append(Component.literal(party.members().size() + " members assigned")
                        .withStyle(ChatFormatting.GRAY));
            }
            style = style.withHoverEvent(new HoverEvent.ShowText(hover));
        }
        return Component.literal(label).withStyle(style);
    }

    /** Clickable link decorated with hover text. */
    public static MutableComponent linkBadge(String text, String url, String hover,
                                             ChatFormatting color) {
        Style style = Style.EMPTY
                .withColor(color)
                .withUnderlined(true)
                .withClickEvent(safeOpenUrl(url));
        if (showHovers() && hover != null) {
            style = style.withHoverEvent(new HoverEvent.ShowText(
                    Component.literal(hover).withStyle(ChatFormatting.GRAY)));
        }
        return Component.literal(text).withStyle(style);
    }

    // ── Internals ───────────────────────────────────────────────────────

    private static boolean showHovers() {
        return VetsConfig.get(VetsConfig.VETS_ANNI_SHOW_HOVER_DETAILS);
    }

    /** Wrap {@code URI.create} so a malformed URL from the snapshot
     *  doesn't blow up the renderer. Falls back to the anni docs root. */
    private static ClickEvent.OpenUrl safeOpenUrl(String raw) {
        try {
            return new ClickEvent.OpenUrl(URI.create(raw));
        } catch (Exception e) {
            return new ClickEvent.OpenUrl(URI.create(DOCS_ANNI_BASE));
        }
    }
}
