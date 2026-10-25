package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.wynnvets.util.Json;

/**
 * Tests for {@link ReturnersRosterProbe}'s roster analysis: membership comes from a walk of the
 * whole payload, and the rank and legacy name from the {@code members.<rank>.<name>} shape.
 *
 * <p>It also covers the response seam both HTTP methods share,
 * {@link ReturnersRosterProbe#membershipFromResponse}: a status other than 200 or a body that is
 * not a JSON object reads as {@code UNKNOWN}, never as absence. The HTTP calls themselves are not
 * covered. The payloads are written by hand after wapi's {@code /v3/guild} shape, and some leave
 * it on purpose.</p>
 */
class ReturnersRosterProbeTest {

    private static final String METRAFISH = "bb171c68-7080-4d02-be7b-cc2d5dcbcd82";
    private static final String METRAFISH_KEY = "bb171c6870804d02be7bcc2d5dcbcd82";
    private static final String HOLIDAZE = "fa8aa700-4538-485f-bf91-325263606995";

    private static ReturnersRosterProbe.ReturnersMembership analyze(String json, String key) {
        return ReturnersRosterProbe.analyzeRoster(Json.GSON.fromJson(json, JsonElement.class), key);
    }

    @Test
    void member_rankIsCapitalisedAndLegacyNameRead() {
        String json =
                """
                {"members": {"total": 2,
                  "owner": {"Metrafish": {"uuid": "%s", "legacyName": "OldMetrafish"}},
                  "chief": {"Holidaze": {"uuid": "%s"}}}}
                """
                        .formatted(METRAFISH, HOLIDAZE);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, METRAFISH_KEY);
        assertTrue(m.inGuild());
        assertEquals("Owner", m.rank());
        assertEquals("OldMetrafish", m.legacyName());
    }

    @Test
    void member_withoutLegacyName() {
        String json =
                "{\"members\": {\"chief\": {\"Metrafish\": {\"uuid\": \"%s\"}}}}"
                        .formatted(METRAFISH);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, METRAFISH_KEY);
        assertTrue(m.inGuild());
        assertEquals("Chief", m.rank());
        assertNull(m.legacyName());
    }

    @Test
    void member_withNullLegacyName() {
        // A JSON null legacyName reads as none; reading it as a string would throw.
        String json =
                "{\"members\": {\"chief\": {\"Metrafish\": {\"uuid\": \"%s\", \"legacyName\": null}}}}"
                        .formatted(METRAFISH);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, METRAFISH_KEY);
        assertTrue(m.inGuild());
        assertEquals("Chief", m.rank());
        assertNull(m.legacyName());
    }

    @Test
    void absent_isNotInGuild() {
        String json =
                "{\"members\": {\"chief\": {\"Holidaze\": {\"uuid\": \"%s\"}}}}"
                        .formatted(HOLIDAZE);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, METRAFISH_KEY);
        assertFalse(m.inGuild());
        assertNull(m.rank());
        assertNull(m.legacyName());
    }

    @Test
    void membershipComesFromTheWholePayload_rankOnlyFromTheMembersShape() {
        // The UUID appears as a key outside members: a member, with no rank or legacy name.
        String json = "{\"elsewhere\": {\"%s\": 1}}".formatted(METRAFISH);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, METRAFISH_KEY);
        assertTrue(m.inGuild());
        assertNull(m.rank());
        assertNull(m.legacyName());
    }

    @Test
    void anObjectValuedTotalBucketIsSkipped() {
        // wapi's total is a number; an object-valued one placed first shows the skip is by name.
        String json =
                """
                {"members": {
                  "total": {"Metrafish": {"uuid": "%s", "legacyName": "FromTotal"}},
                  "recruit": {"Metrafish": {"uuid": "%s", "legacyName": "FromRecruit"}}}}
                """
                        .formatted(METRAFISH, METRAFISH);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, METRAFISH_KEY);
        assertEquals("Recruit", m.rank());
        assertEquals("FromRecruit", m.legacyName());
    }

    @Test
    void nonObjectMembersAreSkipped_andUpperCaseDashedMatchesUndashed() {
        String json =
                "{\"members\": {\"owner\": {\"Broken\": 5, \"Metrafish\": {\"uuid\": \"%s\"}}}}"
                        .formatted(METRAFISH.toUpperCase(Locale.ROOT));
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, METRAFISH_KEY);
        assertTrue(m.inGuild());
        assertEquals("Owner", m.rank());
    }

    @Test
    void nullPayload_isNotInGuild() {
        assertFalse(ReturnersRosterProbe.analyzeRoster(null, METRAFISH_KEY).inGuild());
    }

    // ── membershipFromResponse: the response seam ─────────────────────────

    private static final String ROSTER_WITH_METRAFISH =
            "{\"members\": {\"chief\": {\"Metrafish\": {\"uuid\": \"%s\"}}}}".formatted(METRAFISH);

    @Test
    void membershipFromResponse_non200_isUnknown() {
        for (int status : new int[] {404, 429, 500}) {
            ReturnersRosterProbe.ReturnersMembership m =
                    ReturnersRosterProbe.membershipFromResponse(
                            status, ROSTER_WITH_METRAFISH, METRAFISH_KEY);
            assertEquals(
                    ReturnersRosterProbe.RosterVerdict.UNKNOWN, m.verdict(), "status " + status);
            assertFalse(m.inGuild());
            assertNull(m.rank());
        }
    }

    @Test
    void membershipFromResponse_unparseableBody_isUnknown() {
        assertEquals(
                ReturnersRosterProbe.RosterVerdict.UNKNOWN,
                ReturnersRosterProbe.membershipFromResponse(200, "<html>", METRAFISH_KEY)
                        .verdict());
    }

    @Test
    void membershipFromResponse_emptyBody_isUnknown() {
        // Gson reads an empty body as a null payload.
        assertEquals(
                ReturnersRosterProbe.RosterVerdict.UNKNOWN,
                ReturnersRosterProbe.membershipFromResponse(200, "", METRAFISH_KEY).verdict());
    }

    @Test
    void membershipFromResponse_nonObjectPayload_isUnknown() {
        assertEquals(
                ReturnersRosterProbe.RosterVerdict.UNKNOWN,
                ReturnersRosterProbe.membershipFromResponse(200, "[1, 2]", METRAFISH_KEY)
                        .verdict());
    }

    @Test
    void membershipFromResponse_absent_isNotInRoster() {
        String json =
                "{\"members\": {\"chief\": {\"Holidaze\": {\"uuid\": \"%s\"}}}}"
                        .formatted(HOLIDAZE);
        assertEquals(
                ReturnersRosterProbe.RosterVerdict.NOT_IN_ROSTER,
                ReturnersRosterProbe.membershipFromResponse(200, json, METRAFISH_KEY).verdict());
    }

    @Test
    void membershipFromResponse_present_isInRosterWithRank() {
        ReturnersRosterProbe.ReturnersMembership m =
                ReturnersRosterProbe.membershipFromResponse(
                        200, ROSTER_WITH_METRAFISH, METRAFISH_KEY);
        assertEquals(ReturnersRosterProbe.RosterVerdict.IN_ROSTER, m.verdict());
        assertEquals("Chief", m.rank());
    }

    @Test
    void membershipFromResponse_presentButRankless_isInRosterWithNullRank() {
        // The UUID sits outside members.<rank>: still a member, rank unreadable.
        String json = "{\"elsewhere\": {\"%s\": 1}}".formatted(METRAFISH);
        ReturnersRosterProbe.ReturnersMembership m =
                ReturnersRosterProbe.membershipFromResponse(200, json, METRAFISH_KEY);
        assertEquals(ReturnersRosterProbe.RosterVerdict.IN_ROSTER, m.verdict());
        assertNull(m.rank());
    }

    @Test
    void unknown_isNotInGuild_butDistinguishableFromAbsence() {
        ReturnersRosterProbe.ReturnersMembership unknown =
                ReturnersRosterProbe.ReturnersMembership.unknown();
        assertFalse(unknown.inGuild());
        assertNotEquals(
                ReturnersRosterProbe.ReturnersMembership.notInGuild().verdict(), unknown.verdict());
    }
}
