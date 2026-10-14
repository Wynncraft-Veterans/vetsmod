package org.wynnvets.mwe.anni.render;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * The status lines of the announced {@code /wv anni} body: the eligible or assigned role, the RSVP
 * type and its upgrade prompt, the attendance chance and the party assignment.
 *
 * <p>The {@code [Hard]}/{@code [Soft]} upgrade prompt shows only while the player has no RSVP and
 * the anni is more than {@link AnniCommandRenderer#TWO_HOURS_SECONDS} away, so inside two hours
 * the same lines render without it.</p>
 */
final class AnniStatusSections {

    private AnniStatusSections() {}

    /** "Assigned Role: TANK" — used in {@link AnniCommandRenderer#renderFarOut} when the
     *  user is in a party. Replaces the registration line; the same
     *  role chip rendering as elsewhere (so the style/colour/click
     *  semantics match the role chips on the dashboard). */
    static MutableComponent assignedRoleSection(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        String roleCode = board != null ? board.role() : null;
        if (roleCode == null && board != null && board.party() != null) {
            roleCode = findOwnRole(board.party(), snapshot.mcUuid());
        }
        MutableComponent line = AnniHoverBuilder.label("Assigned Role", ChatFormatting.GRAY);
        if (roleCode != null) {
            line.append(roleChipFor(snapshot, roleCode));
        } else {
            line.append(Component.literal("TBD").withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    /** The "Eligible Roles" line: the role chips, a fill-only note (a shape vets-anni does not
     *  send today), or a register-now nudge. */
    static MutableComponent registrationSection(AnniSnapshot snapshot) {
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg == null || !reg.registered()) {
            return AnniHoverBuilder.label("Eligible Roles", ChatFormatting.GRAY)
                    .append(Component.literal("not registered  ").withStyle(ChatFormatting.YELLOW))
                    .append(
                            AnniHoverBuilder.linkBadge(
                                    "[register]",
                                    AnniHoverBuilder.DOCS_PREPARING,
                                    "Open anni.wynnvets.org/me preparation guide",
                                    ChatFormatting.AQUA));
        }
        if (reg.roles().isEmpty()) {
            return AnniHoverBuilder.label("Eligible Roles", ChatFormatting.GRAY)
                    .append(
                            Component.literal(AnniHoverBuilder.displayRole("FILL") + " only")
                                    .withStyle(ChatFormatting.WHITE))
                    .append(
                            Component.literal(" — fill role assumed; specialise on ")
                                    .withStyle(ChatFormatting.GRAY))
                    .append(
                            AnniHoverBuilder.linkBadge(
                                    "anni.wynnvets.org/me",
                                    "https://anni.wynnvets.org/me",
                                    "Open your roles dashboard",
                                    ChatFormatting.AQUA));
        }
        MutableComponent line = AnniHoverBuilder.label("Eligible Roles", ChatFormatting.GRAY);
        boolean first = true;
        for (AnniSnapshot.RoleEntry role : reg.roles()) {
            if (!first) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(AnniHoverBuilder.roleChip(role));
            first = false;
        }
        return line;
    }

    /** RSVP badge + (when far-out + un-RSVP'd) a parenthetical with
     *  click-to-suggest [Hard] [Soft] upgrade buttons.
     *
     *  <p>The upgrade prompt is gated on {@code secondsUntil > 2h}: once
     *  inside the imminent window, the RSVP slot cutoffs have already
     *  passed and surfacing the buttons would only be misleading. The
     *  user-facing rule: "the prompt only shows up after RSVP type if
     *  it is t-2h+".</p> */
    static MutableComponent rsvpSection(AnniSnapshot snapshot, long secondsUntil) {
        MutableComponent line =
                AnniHoverBuilder.label("RSVP Type", ChatFormatting.GRAY)
                        .append(AnniHoverBuilder.rsvpBadge(snapshot.rsvp(), snapshot.attendance()));

        boolean rsvped =
                snapshot.rsvp() != null
                        && snapshot.rsvp().notice() != null
                        && !snapshot.rsvp().revoked();
        boolean farOut = secondsUntil > AnniCommandRenderer.TWO_HOURS_SECONDS;
        if (!rsvped && farOut) {
            line.append(Component.literal(" "));
            line.append(rsvpUpgradePrompt());
        }
        return line;
    }

    /** Build the parenthetical upgrade prompt:
     *  {@code &7(Buttons to upgrade your \rsvp: &3[&bHard&3] &2[&aSoft&2]&7)}.
     *
     *  <p>Each button's bracket+label is its own click-to-suggest
     *  surface; the {@code \rsvp} word inside the parenthetical is
     *  hoverable (no click) to explain the Discord equivalent.</p> */
    // Package-private for unit tests. See AnniStatusSectionsTest.
    static MutableComponent rsvpUpgradePrompt() {
        Style gray = Style.EMPTY.withColor(ChatFormatting.GRAY);

        Component rsvpWord =
                Component.literal("\\rsvp")
                        .withStyle(
                                Style.EMPTY
                                        .withColor(ChatFormatting.GRAY)
                                        .withHoverEvent(
                                                new HoverEvent.ShowText(
                                                        Component.literal(
                                                                "\\rsvp is a Discord command in vetsfish's"
                                                                        + " #bot-commands channel.\n"
                                                                        + "It does the same thing as /wv anni rsvp"
                                                                        + " — entirely optional from in-game."))));

        MutableComponent out = Component.literal("").withStyle(gray);
        out.append(Component.literal("(Buttons to upgrade your ").withStyle(gray));
        out.append(rsvpWord);
        out.append(Component.literal(": ").withStyle(gray));
        out.append(
                rsvpUpgradeButton(
                        "Hard",
                        ChatFormatting.DARK_AQUA,
                        ChatFormatting.AQUA,
                        "/wv anni rsvp hard",
                        "HRSVP — confirms you WILL be there.\n"
                                + "Requirement: arrive at least 20 mins before anni"
                                + " starts, or your slot may be reassigned to a walk-in.\n"
                                + "Click to suggest /wv anni rsvp hard."));
        out.append(Component.literal(" ").withStyle(gray));
        out.append(
                rsvpUpgradeButton(
                        "Soft",
                        ChatFormatting.DARK_GREEN,
                        ChatFormatting.GREEN,
                        "/wv anni rsvp soft",
                        "SRSVP — confirms you'll PROBABLY be there.\n"
                                + "Requirement: arrive at least 40 mins before anni"
                                + " starts, or your slot may be reassigned.\n"
                                + "Click to suggest /wv anni rsvp soft."));
        out.append(Component.literal(")").withStyle(gray));
        return out;
    }

    /** One {@code [Label]} button — bracket colour, label colour, shared
     *  click + hover. Built as a parent literal with the click/hover on
     *  the parent so the click fires whether the user lands on the
     *  bracket or the label. */
    private static MutableComponent rsvpUpgradeButton(
            String label,
            ChatFormatting bracketColor,
            ChatFormatting labelColor,
            String command,
            String hoverText) {
        Style parentStyle =
                Style.EMPTY
                        .withClickEvent(new ClickEvent.SuggestCommand(command))
                        .withHoverEvent(
                                new HoverEvent.ShowText(
                                        Component.literal(hoverText)
                                                .withStyle(ChatFormatting.GRAY)));
        MutableComponent btn = Component.literal("").withStyle(parentStyle);
        btn.append(Component.literal("[").withStyle(bracketColor));
        btn.append(Component.literal(label).withStyle(labelColor));
        btn.append(Component.literal("]").withStyle(bracketColor));
        return btn;
    }

    /** Attendance chance line — only meaningful when the user is in
     *  the unassigned bucket (where their attendance is genuinely
     *  uncertain). Two special cases substitute a definitive label:
     *  party → "ASSIGNED" (light purple), wont_assign → "COULD NOT
     *  ASSIGN" (red). Other states (unplaced, unknown) return
     *  {@code null} so the caller drops the line entirely. */
    static MutableComponent attendanceSection(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        String state = board == null || board.state() == null ? null : board.state().toLowerCase();
        if (state == null) return null;
        switch (state) {
            case "party":
                return AnniHoverBuilder.label("Attendance Chance", ChatFormatting.GRAY)
                        .append(
                                Component.literal("ASSIGNED")
                                        .withStyle(
                                                ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
            case "wont_assign":
                return AnniHoverBuilder.label("Attendance Chance", ChatFormatting.GRAY)
                        .append(
                                Component.literal("COULD NOT ASSIGN")
                                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            case "unassigned":
                return AnniHoverBuilder.label("Attendance Chance", ChatFormatting.GRAY)
                        .append(AnniHoverBuilder.attendanceBar(snapshot.attendance()));
            default:
                return null;
        }
    }

    /** Board state — different forms for "unplaced", "unassigned",
     *  "wont_assign", "party". */
    static MutableComponent boardSection(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        if (board == null || board.state() == null) {
            return Component.literal("");
        }
        String state = board.state().toLowerCase();
        switch (state) {
            case "unplaced":
                return AnniHoverBuilder.label("Party Assignment", ChatFormatting.GRAY)
                        .append(Component.literal("unplaced").withStyle(ChatFormatting.YELLOW))
                        .append(
                                Component.literal(" — register & RSVP to join the placement queue")
                                        .withStyle(ChatFormatting.GRAY));
            case "wont_assign":
                MutableComponent wont =
                        AnniHoverBuilder.label("Party Assignment", ChatFormatting.GRAY)
                                .append(
                                        Component.literal("won't assign")
                                                .withStyle(
                                                        ChatFormatting.RED, ChatFormatting.BOLD));
                if (board.wontReason() != null && !board.wontReason().isEmpty()) {
                    wont.append(
                            Component.literal(" — " + board.wontReason())
                                    .withStyle(ChatFormatting.GRAY));
                }
                return wont;
            case "unassigned":
                // Don't restate the RSVP — the RSVP Type line above
                // (past Attendance Chance, when that shows) already shows
                // it. Just say what board state means for placement.
                return AnniHoverBuilder.label("Party Assignment", ChatFormatting.GRAY)
                        .append(Component.literal("unassigned").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(
                                Component.literal("Awaiting placement")
                                        .withStyle(ChatFormatting.GRAY));
            case "party":
                return partyAssignedBlock(snapshot);
            default:
                return AnniHoverBuilder.label("Party Assignment", ChatFormatting.GRAY)
                        .append(Component.literal(state).withStyle(ChatFormatting.GRAY));
        }
    }

    /** Multi-line party assignment block. */
    private static MutableComponent partyAssignedBlock(AnniSnapshot snapshot) {
        AnniSnapshot.Board board = snapshot.board();
        AnniSnapshot.Party party = board == null ? null : board.party();
        if (party == null) {
            return AnniHoverBuilder.label("Party Assignment", ChatFormatting.GRAY)
                    .append(
                            Component.literal("party (details pending)")
                                    .withStyle(ChatFormatting.GRAY));
        }
        // Role lives on the "Assigned Role" line above this block —
        // see AnniCommandRenderer.renderFarOut. Don't repeat it here.
        MutableComponent line =
                AnniHoverBuilder.label("Party", ChatFormatting.GRAY)
                        .append(AnniHoverBuilder.partyOrdinalChip(party))
                        .append(Component.literal("  "));

        line.append(AnniHoverBuilder.label("World", ChatFormatting.GRAY))
                .append(AnniHoverBuilder.partyWorldChip(party.world()));

        if (party.host() != null && party.host().username() != null) {
            line.append(Component.literal("\n"));
            line.append(AnniHoverBuilder.label("Host", ChatFormatting.GRAY))
                    .append(
                            Component.literal(party.host().username())
                                    .withStyle(ChatFormatting.AQUA));
        }
        return line;
    }

    /** Resolve the role-code for the local player from the party member
     *  list when {@code board.role} is missing. */
    // Package-private for unit tests. See AnniStatusSectionsTest.
    static String findOwnRole(AnniSnapshot.Party party, String mcUuid) {
        if (mcUuid == null || party.members() == null) return null;
        for (AnniSnapshot.MemberRef m : party.members()) {
            if (mcUuid.equalsIgnoreCase(m.uuid())) {
                return m.role();
            }
        }
        return null;
    }

    /** Build a role chip for a given role-code, preferring the snapshot's
     *  registration entry (which carries the docs URL + hover title) when
     *  available; otherwise falls back to the role code + generic
     *  event-gameplay anchor. */
    private static MutableComponent roleChipFor(AnniSnapshot snapshot, String roleCode) {
        AnniSnapshot.Registration reg = snapshot.registration();
        if (reg != null) {
            for (AnniSnapshot.RoleEntry r : reg.roles()) {
                if (r.role() != null && r.role().equalsIgnoreCase(roleCode)) {
                    return AnniHoverBuilder.roleChip(r);
                }
            }
        }
        return AnniHoverBuilder.roleChip(roleCode, null, null);
    }
}
