package org.wynnvets.mwe.anni.bossbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;
import org.wynnvets.mwe.anni.state.AnniSnapshot;

/**
 * Tests for {@link VetsBossBarContentBuilder}'s seeking variant: the RSVP chip's label and colour
 * for each notice the server can send, in both of its spellings, the won't-be-assigned line, and
 * the assigned variant's TBD bar for a player registered for a specific role but not yet placed.
 *
 * <p>Everything goes through the public {@code build(snapshot, 4800, 0, 0)}: 80 minutes out, so
 * the countdown gate's {@code &&} short-circuits before {@code AnniZone.isInZone} and that class
 * never loads. {@code FlashTracker}'s static state is primitives and nullable boxes, and no field
 * is flashing, so the chips carry their base style. Snapshots come from
 * {@link AnniSnapshot#fromJson}.</p>
 */
class VetsBossBarContentBuilderTest {

    /** 80 minutes before the anni: inside the boss-bar window, outside the countdown. */
    private static final long SECONDS = 4800;

    /** The seeking line, 80 minutes out, for a FILL slot and the given RSVP chip. */
    private static String seeking(String chip) {
        return "anni.wynnvets.org (80mins) | " + chip + " for FILL";
    }

    private static AnniSnapshot snapshot(String json) {
        return AnniSnapshot.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    private static Component bar(String json) {
        return VetsBossBarContentBuilder.build(snapshot(json), SECONDS, 0, 0);
    }

    /** The seeking line's sibling that reads {@code label}: the RSVP chip. */
    private static Component chip(Component bar, String label) {
        for (Component sibling : bar.getSiblings()) {
            if (sibling.getString().equals(label)) {
                return sibling;
            }
        }
        throw new AssertionError("no " + label + " chip in " + bar.getString());
    }

    private static void assertChip(String json, String label, ChatFormatting color) {
        Component bar = bar(json);
        assertEquals(seeking(label), bar.getString());
        Component chip = chip(bar, label);
        assertEquals(TextColor.fromLegacyFormat(color), chip.getStyle().getColor(), label);
        assertTrue(chip.getStyle().isBold(), label + " chip is bold");
    }

    // ----- The four notice states, in both spellings -----

    @Test
    void aHardRsvpIsAquaInBothSpellings() {
        assertChip("{\"rsvp\":{\"notice\":\"hard\"}}", "HrdRSVP", ChatFormatting.AQUA);
        assertChip(
                "{\"attendance\":{\"notice_effective\":\"rsvp_hard\"}}",
                "HrdRSVP",
                ChatFormatting.AQUA);
    }

    @Test
    void aSoftRsvpIsGreenInBothSpellings() {
        assertChip("{\"rsvp\":{\"notice\":\"soft\"}}", "SftRSVP", ChatFormatting.GREEN);
        assertChip(
                "{\"attendance\":{\"notice_effective\":\"rsvp_soft\"}}",
                "SftRSVP",
                ChatFormatting.GREEN);
    }

    @Test
    void walkInsAreYellowOnTimeAndRedLate() {
        assertChip(
                "{\"attendance\":{\"notice_effective\":\"attend_early\"}}",
                "NotRSVP",
                ChatFormatting.YELLOW);
        assertChip(
                "{\"attendance\":{\"notice_effective\":\"attend_late\"}}",
                "NotRSVP",
                ChatFormatting.RED);
    }

    @Test
    void noNoticeFromEitherBlockReadsAsNoRsvp() {
        assertChip("{}", "NotRSVP", ChatFormatting.GRAY);
    }

    @Test
    void aRevokedRsvpFallsBackToTheAttendanceNotice() {
        assertChip(
                "{\"rsvp\":{\"notice\":\"hard\",\"revoked\":true},"
                        + "\"attendance\":{\"notice_effective\":\"attend_late\"}}",
                "NotRSVP",
                ChatFormatting.RED);
    }

    @Test
    void anUnknownNoticePassesThroughUpperCasedInGray() {
        assertChip(
                "{\"attendance\":{\"notice_effective\":\"walk_in\"}}",
                "WALK_IN",
                ChatFormatting.GRAY);
    }

    @Test
    void anUnassignedWalkInStillSeesTheChip() {
        // vets-anni sends a volunteer to sit out as plain "unassigned", so the
        // chip cannot tell them apart and still shows.
        assertChip(
                "{\"board\":{\"state\":\"unassigned\"},"
                        + "\"attendance\":{\"notice_effective\":\"attend_early\"}}",
                "NotRSVP",
                ChatFormatting.YELLOW);
    }

    // ----- Won't be assigned -----

    /** The bar has no room for the reason, so it is left off; there is no chip either. */
    @Test
    void aWontAssignPlayerGetsTheShortLineWithNoReasonAndNoChip() {
        assertEquals(
                "anni.wynnvets.org: You won't be assigned",
                bar("{\"board\":{\"state\":\"wont_assign\",\"wont_reason\":\"Sitting out\"},"
                                + "\"rsvp\":{\"notice\":\"hard\"}}")
                        .getString());
    }

    @Test
    void wontAssignWithNoReasonReadsTheSame() {
        assertEquals(
                "anni.wynnvets.org: You won't be assigned",
                bar("{\"board\":{\"state\":\"wont_assign\"}}").getString());
    }

    // ----- The assigned variant's TBD bar -----

    @Test
    void aSpecificRoleNotYetPlacedShowsTheAssignedVariantAllTbd() {
        String text =
                bar("{\"registration\":{\"roles\":[{\"role\":\"TANK\"}]},"
                                + "\"rsvp\":{\"notice\":\"hard\"}}")
                        .getString();
        assertEquals("anni.wynnvets.org: Role: TBD | Party TBD | World: TBD", text);
        assertFalse(text.contains("HrdRSVP"), "a committed slot never shows the seeking line");
    }

    @Test
    void aFillRegistrationStaysOnTheSeekingLine() {
        assertEquals(
                seeking("NotRSVP"),
                bar("{\"registration\":{\"roles\":[{\"role\":\"FILL\"}]}}").getString());
    }

    // ----- The minutes unit -----

    @Test
    void oneMinuteIsSingularAndAnyOtherCountPlural() {
        assertEquals("min", VetsBossBarContentBuilder.minutesUnit(1));
        assertEquals("mins", VetsBossBarContentBuilder.minutesUnit(2));
        assertEquals("mins", VetsBossBarContentBuilder.minutesUnit(80));
    }
}
