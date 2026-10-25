package org.wynnvets.fetcher.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The poll half of {@link StaffRanksPoller}'s cache: what one parsed {@code /v1/outbound/staff}
 * response leaves behind. Each response replaces the previous one, so a name the new response
 * lacks is dropped and one it repeats is kept. The swap's atomicity (no reader ever sees a
 * repeated name missing) is not something a unit test can show reliably and is not pinned here.
 */
class StaffRanksPollerTest {

    private static final String UUID = "bb171c68-7080-4d02-be7b-cc2d5dcbcd82";

    @AfterEach
    void emptyThePollCache() {
        StaffRanksPoller.parseStaffRanks("[]");
        StaffRanksPoller.clearLiveOverlay();
    }

    @Test
    void aNameTheNextResponseLacksIsDroppedAndARepeatedOneKept() {
        StaffRanksPoller.parseStaffRanks(
                "[{\"username\": \"Metrafish\", \"rank\": \"Chief\"},"
                        + " {\"username\": \"Holidaze\", \"rank\": \"strategist\"}]");
        assertEquals(Optional.of("strategist"), StaffRanksPoller.confirmedRankFor("holidaze"));

        StaffRanksPoller.parseStaffRanks("[{\"username\": \"Metrafish\", \"rank\": \"chief\"}]");
        assertEquals(Optional.of("chief"), StaffRanksPoller.confirmedRankFor("METRAFISH"));
        assertEquals(Optional.empty(), StaffRanksPoller.confirmedRankFor("Holidaze"));
    }

    @Test
    void anUnparseableBodyLeavesThePreviousResponseInPlace() {
        StaffRanksPoller.parseStaffRanks("[{\"username\": \"Metrafish\", \"rank\": \"owner\"}]");
        StaffRanksPoller.parseStaffRanks("not json at all");
        assertEquals(Optional.of("owner"), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void aRetiredCaptainIsNotKept() {
        StaffRanksPoller.parseStaffRanks("[{\"username\": \"Wenweia\", \"rank\": \"captain\"}]");
        assertEquals(Optional.empty(), StaffRanksPoller.confirmedRankFor("Wenweia"));
    }

    // ----- the live overlay -----

    @Test
    void anOnlineFrameStillWinsOverAStalePollEntry() {
        StaffRanksPoller.parseStaffRanks(
                "[{\"username\": \"Metrafish\", \"rank\": \"strategist\"}]");
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", UUID, "chief", true);
        assertEquals(Optional.of("chief"), StaffRanksPoller.confirmedRankFor("metrafish"));
    }

    @Test
    void aLiveEntryAppliedBeforeTheFetchAndAbsentFromItIsDropped() {
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", UUID, "chief", true);
        long started = System.nanoTime();
        StaffRanksPoller.parseStaffRanks("[]", started);
        assertEquals(Optional.empty(), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void aLiveEntryAppliedAfterTheFetchStartedSurvivesIt() {
        long started = System.nanoTime();
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", UUID, "chief", true);
        StaffRanksPoller.parseStaffRanks("[]", started);
        assertEquals(Optional.of("chief"), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void aLiveEntryTheResponseStillListsSurvives() {
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", UUID, "chief", true);
        long started = System.nanoTime();
        StaffRanksPoller.parseStaffRanks(
                "[{\"username\": \"Metrafish\", \"rank\": \"strategist\"}]", started);
        assertEquals(Optional.of("chief"), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void aNamelessOfflineRemovesTheEntryByUuid() {
        StaffRanksPoller.parseStaffRanks("[{\"username\": \"Metrafish\", \"rank\": \"chief\"}]");
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", UUID, "chief", true);
        StaffRanksPoller.applyLiveStaffEvent(
                null, UUID.toUpperCase(java.util.Locale.ROOT), null, false);
        assertEquals(Optional.empty(), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void aNamelessOfflineWithAnUnknownUuidChangesNothing() {
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", UUID, "chief", true);
        StaffRanksPoller.applyLiveStaffEvent(
                "", "00000000-0000-0000-0000-000000000000", null, false);
        assertEquals(Optional.of("chief"), StaffRanksPoller.confirmedRankFor("Metrafish"));
    }

    @Test
    void clearLiveOverlayEmptiesOnlyTheOverlay() {
        StaffRanksPoller.parseStaffRanks(
                "[{\"username\": \"Holidaze\", \"rank\": \"strategist\"}]");
        StaffRanksPoller.applyLiveStaffEvent("Metrafish", UUID, "chief", true);
        StaffRanksPoller.clearLiveOverlay();
        assertEquals(Optional.empty(), StaffRanksPoller.confirmedRankFor("Metrafish"));
        assertEquals(Optional.of("strategist"), StaffRanksPoller.confirmedRankFor("Holidaze"));
    }
}
