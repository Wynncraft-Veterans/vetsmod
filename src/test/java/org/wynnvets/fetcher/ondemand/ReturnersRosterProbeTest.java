package org.wynnvets.fetcher.ondemand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>{@link ReturnersRosterProbe#returnersMembership} and
 * {@link ReturnersRosterProbe#confirmArbitraryGuildRank} are not covered; only the analysis they
 * share is. The payloads are written by hand after wapi's {@code /v3/guild} shape, and some leave
 * it on purpose.</p>
 */
class ReturnersRosterProbeTest {

    private static final String ALICE = "aaaaaaaa-1111-2222-3333-444444444444";
    private static final String ALICE_KEY = "aaaaaaaa111122223333444444444444";
    private static final String BOB = "bbbbbbbb-1111-2222-3333-444444444444";

    private static ReturnersRosterProbe.ReturnersMembership analyze(String json, String key) {
        return ReturnersRosterProbe.analyzeRoster(Json.GSON.fromJson(json, JsonElement.class), key);
    }

    @Test
    void member_rankIsCapitalisedAndLegacyNameRead() {
        String json =
                """
                {"members": {"total": 2,
                  "owner": {"Alice": {"uuid": "%s", "legacyName": "OldAlice"}},
                  "chief": {"Bob": {"uuid": "%s"}}}}
                """
                        .formatted(ALICE, BOB);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, ALICE_KEY);
        assertTrue(m.inGuild());
        assertEquals("Owner", m.rank());
        assertEquals("OldAlice", m.legacyName());
    }

    @Test
    void member_withoutLegacyName() {
        String json =
                "{\"members\": {\"chief\": {\"Alice\": {\"uuid\": \"%s\"}}}}".formatted(ALICE);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, ALICE_KEY);
        assertTrue(m.inGuild());
        assertEquals("Chief", m.rank());
        assertNull(m.legacyName());
    }

    @Test
    void absent_isNotInGuild() {
        String json = "{\"members\": {\"chief\": {\"Bob\": {\"uuid\": \"%s\"}}}}".formatted(BOB);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, ALICE_KEY);
        assertFalse(m.inGuild());
        assertNull(m.rank());
        assertNull(m.legacyName());
    }

    @Test
    void membershipComesFromTheWholePayload_rankOnlyFromTheMembersShape() {
        // The UUID appears as a key outside members: a member, with no rank or legacy name.
        String json = "{\"elsewhere\": {\"%s\": 1}}".formatted(ALICE);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, ALICE_KEY);
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
                  "total": {"Alice": {"uuid": "%s", "legacyName": "FromTotal"}},
                  "recruit": {"Alice": {"uuid": "%s", "legacyName": "FromRecruit"}}}}
                """
                        .formatted(ALICE, ALICE);
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, ALICE_KEY);
        assertEquals("Recruit", m.rank());
        assertEquals("FromRecruit", m.legacyName());
    }

    @Test
    void nonObjectMembersAreSkipped_andUpperCaseDashedMatchesUndashed() {
        String json =
                "{\"members\": {\"owner\": {\"Broken\": 5, \"Alice\": {\"uuid\": \"%s\"}}}}"
                        .formatted(ALICE.toUpperCase(Locale.ROOT));
        ReturnersRosterProbe.ReturnersMembership m = analyze(json, ALICE_KEY);
        assertTrue(m.inGuild());
        assertEquals("Owner", m.rank());
    }

    @Test
    void nullPayload_isNotInGuild() {
        assertFalse(ReturnersRosterProbe.analyzeRoster(null, ALICE_KEY).inGuild());
    }
}
