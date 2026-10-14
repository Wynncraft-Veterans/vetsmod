package org.wynnvets.mwe.anni.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * Goldens for the arms of {@link AnniStatusSections} that a test can reach: the assigned-role line
 * with no role to show, the attendance and party-assignment lines, the RSVP upgrade prompt, and
 * {@code findOwnRole}.
 *
 * <p>Each golden is the line's {@code Component.toString()}, which spells out every segment's
 * text, colour, flags (an explicit {@code false} included), click and hover. That departs from
 * {@code HelpCommandsTest}'s walk over sibling text, because here the click and hover targets are
 * part of what the render produces. The string form is Minecraft 1.21.11's, so a Minecraft update
 * that changes {@code Component.toString()} will need the goldens regenerated.</p>
 *
 * <p>Not covered here, because each reaches {@code VetsConfig}, and {@code VetsConfig} cannot
 * initialise in a test: {@code registrationSection} (its links ask whether hover details are on,
 * and its fill-only note and role chips read the role style through
 * {@code AnniHoverBuilder.displayRole}); {@code assignedRoleSection} once it has a role code (its
 * chip comes from {@code AnniHoverBuilder.roleChip}); {@code rsvpSection}, and so its two-hour
 * gate on the upgrade prompt; the attendance bar of the unassigned arm when an attendance is
 * present; and the party block when the board has a party (the RSVP badge, the bar and the party
 * chip each ask whether hover details are on). The bytecode proof and the in-game pass cover
 * them.</p>
 */
class AnniStatusSectionsTest {

    private static AnniSnapshot snapshot(String json) {
        return AnniSnapshot.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static AnniSnapshot board(String boardJson) {
        return snapshot("{\"board\": " + boardJson + "}");
    }

    // ── assignedRoleSection ───────────────────────────────────────────

    @Test
    void assignedRole_withNoRoleToShow_isTbd() {
        AnniSnapshot notInTheParty =
                snapshot(
                        "{\"mc_uuid\": \"cccc-3\", \"board\": {\"state\": \"party\", \"party\":"
                                + " {\"members\": [{\"uuid\": \"aaaa-1\", \"role\": \"TANK\"}]}}}");

        assertEquals(
                "literal{Assigned Role: }[style={color=gray}, "
                        + "siblings=[literal{TBD}[style={color=gray}]]]",
                AnniStatusSections.assignedRoleSection(snapshot("{}")).toString());
        assertEquals(
                "literal{Assigned Role: }[style={color=gray}, "
                        + "siblings=[literal{TBD}[style={color=gray}]]]",
                AnniStatusSections.assignedRoleSection(notInTheParty).toString());
    }

    // ── attendanceSection ─────────────────────────────────────────────

    @Test
    void attendance_inAParty_isAssigned() {
        assertEquals(
                "literal{Attendance Chance: }[style={color=gray}, "
                        + "siblings=[literal{ASSIGNED}[style={color=light_purple,bold}]]]",
                AnniStatusSections.attendanceSection(board("{\"state\": \"party\"}")).toString());
    }

    @Test
    void attendance_wontAssign_isCouldNotAssign() {
        assertEquals(
                "literal{Attendance Chance: }[style={color=gray}, "
                        + "siblings=[literal{COULD NOT ASSIGN}[style={color=red,bold}]]]",
                AnniStatusSections.attendanceSection(board("{\"state\": \"wont_assign\"}"))
                        .toString());
    }

    @Test
    void attendance_unassignedWithNoAttendance_isTheUnknownBar() {
        assertEquals(
                "literal{Attendance Chance: }[style={color=gray}, "
                        + "siblings=[literal{attendance: unknown}[style={color=gray}]]]",
                AnniStatusSections.attendanceSection(board("{\"state\": \"unassigned\"}"))
                        .toString());
    }

    @Test
    void attendance_isDroppedForAnyOtherState() {
        assertNull(AnniStatusSections.attendanceSection(board("{\"state\": \"unplaced\"}")));
        assertNull(AnniStatusSections.attendanceSection(board("{}")));
        assertNull(AnniStatusSections.attendanceSection(snapshot("{}")));
    }

    // ── boardSection ──────────────────────────────────────────────────

    /** {@code boardSection} as {@code renderFarOut} calls it. */
    private static String boardLine(AnniSnapshot snapshot) {
        return AnniStatusSections.boardSection(snapshot, true).toString();
    }

    @Test
    void board_withNoBoard_isEmpty() {
        assertEquals("empty", boardLine(snapshot("{}")));
        assertEquals("empty", boardLine(board("{}")));
    }

    @Test
    void board_unplaced() {
        assertEquals(
                "literal{Party Assignment: }[style={color=gray}, "
                        + "siblings=[literal{unplaced}[style={color=yellow}], "
                        + "literal{ — register & RSVP to join the placement "
                        + "queue}[style={color=gray}]]]",
                boardLine(board("{\"state\": \"unplaced\"}")));
    }

    @Test
    void board_unassigned() {
        assertEquals(
                "literal{Party Assignment: }[style={color=gray}, "
                        + "siblings=[literal{unassigned}[style={color=gold}], "
                        + "literal{ — }[style={color=dark_gray}], "
                        + "literal{Awaiting placement}[style={color=gray}]]]",
                boardLine(board("{\"state\": \"unassigned\"}")));
    }

    @Test
    void board_wontAssign_withAReason() {
        assertEquals(
                "literal{Party Assignment: }[style={color=gray}, "
                        + "siblings=[literal{won't assign}[style={color=red,bold}], "
                        + "literal{ — no healer slots}[style={color=gray}]]]",
                boardLine(
                        board(
                                "{\"state\": \"wont_assign\","
                                        + " \"wont_reason\": \"no healer slots\"}")));
    }

    @Test
    void board_wontAssign_withNoOrAnEmptyReason() {
        assertEquals(
                "literal{Party Assignment: }[style={color=gray}, "
                        + "siblings=[literal{won't assign}[style={color=red,bold}]]]",
                boardLine(board("{\"state\": \"wont_assign\"}")));
        assertEquals(
                "literal{Party Assignment: }[style={color=gray}, "
                        + "siblings=[literal{won't assign}[style={color=red,bold}]]]",
                boardLine(board("{\"state\": \"wont_assign\", \"wont_reason\": \"\"}")));
    }

    @Test
    void board_anUnknownState_isPrintedLowerCased() {
        assertEquals(
                "literal{Party Assignment: }[style={color=gray}, "
                        + "siblings=[literal{mystery}[style={color=gray}]]]",
                boardLine(board("{\"state\": \"Mystery\"}")));
    }

    /** The party arm with no party yet, the one arm of the party block a test can reach: the
     *  multi-line block's GRAY "party (details pending)". */
    @Test
    void board_inAPartyWithNoPartyYet_isDetailsPending() {
        assertEquals(
                "literal{Party Assignment: }[style={color=gray}, "
                        + "siblings=[literal{party (details pending)}[style={color=gray}]]]",
                boardLine(board("{\"state\": \"party\"}")));
    }

    // ── rsvpUpgradePrompt ─────────────────────────────────────────────

    @Test
    void upgradePrompt_offersHardThenSoft() {
        assertEquals(
                "empty[style={color=gray}, siblings=[literal{(Buttons to upgrade your "
                        + "}[style={color=gray}], literal{\\rsvp}[style={color=gray,"
                        + "hoverEvent=ShowText[value=literal{\\rsvp is a Discord command in "
                        + "vetsfish's #bot-commands channel.\nIt does the same thing as /wv anni "
                        + "rsvp — entirely optional from in-game.}]}], "
                        + "literal{: }[style={color=gray}], "
                        + "empty[style={clickEvent=SuggestCommand[command=/wv anni rsvp hard],"
                        + "hoverEvent=ShowText[value=literal{HRSVP — confirms you WILL be "
                        + "there.\nRequirement: arrive at least 20 mins before anni starts, "
                        + "or your slot may be reassigned to a walk-in.\nClick to suggest /wv "
                        + "anni rsvp hard.}[style={color=gray}]]}, "
                        + "siblings=[literal{[}[style={color=dark_aqua}], "
                        + "literal{Hard}[style={color=aqua}], "
                        + "literal{]}[style={color=dark_aqua}]]], literal{ }[style={color=gray}], "
                        + "empty[style={clickEvent=SuggestCommand[command=/wv anni rsvp soft],"
                        + "hoverEvent=ShowText[value=literal{SRSVP — confirms you'll PROBABLY be "
                        + "there.\nRequirement: arrive at least 40 mins before anni starts, "
                        + "or your slot may be reassigned.\nClick to suggest /wv anni rsvp "
                        + "soft.}[style={color=gray}]]}, "
                        + "siblings=[literal{[}[style={color=dark_green}], "
                        + "literal{Soft}[style={color=green}], "
                        + "literal{]}[style={color=dark_green}]]], "
                        + "literal{)}[style={color=gray}]]]",
                AnniStatusSections.rsvpUpgradePrompt().toString());
    }

    // ── findOwnRole ───────────────────────────────────────────────────

    private static AnniSnapshot.Party party(String membersJson) {
        return board("{\"state\": \"party\", \"party\": {\"members\": " + membersJson + "}}")
                .board()
                .party();
    }

    @Test
    void findOwnRole_matchesTheUuidIgnoringCase() {
        AnniSnapshot.Party p =
                party(
                        "[{\"uuid\": \"AAAA-1\", \"role\": \"TANK\"},"
                                + " {\"uuid\": \"bbbb-2\", \"role\": \"FILL\"}]");

        assertEquals("TANK", AnniStatusSections.findOwnRole(p, "aaaa-1"));
        assertEquals("FILL", AnniStatusSections.findOwnRole(p, "BBBB-2"));
    }

    @Test
    void findOwnRole_isNullWhenAbsentOrUnknown() {
        AnniSnapshot.Party p = party("[{\"uuid\": \"aaaa-1\", \"role\": \"TANK\"}]");

        assertNull(AnniStatusSections.findOwnRole(p, "cccc-3"));
        assertNull(AnniStatusSections.findOwnRole(p, null));
        assertNull(AnniStatusSections.findOwnRole(party("null"), "aaaa-1"));
    }
}
