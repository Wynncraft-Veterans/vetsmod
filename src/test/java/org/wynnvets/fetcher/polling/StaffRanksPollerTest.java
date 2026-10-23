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

    @AfterEach
    void emptyThePollCache() {
        StaffRanksPoller.parseStaffRanks("[]");
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
}
