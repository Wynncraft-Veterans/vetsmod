package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RankDisplayMap#displayFor}, the raw-rank-to-label table behind the queued
 * Returner's local echo, {@code EncourageUpdateRewriter}'s sender pill and
 * {@code OutboundDisplayHandler}'s fallback for a relayed line that carries no
 * {@code pill_display}. The wire keeps the raw names.
 */
class RankDisplayMapTest {

    @Test
    void displayFor_collapsesTheStaffRanksToSteward() {
        assertEquals("Steward", RankDisplayMap.displayFor("Strategist"));
        assertEquals("Steward", RankDisplayMap.displayFor("Chief"));
        assertEquals("Steward", RankDisplayMap.displayFor("Owner"));
    }

    @Test
    void displayFor_collapsesCaptainAndRecruiterToReturnerAndKeepsRecruit() {
        assertEquals("Returner", RankDisplayMap.displayFor("Captain"));
        assertEquals("Returner", RankDisplayMap.displayFor("Recruiter"));
        assertEquals("Recruit", RankDisplayMap.displayFor("Recruit"));
    }

    /** The poller reports lowercase names and Wynntils' {@code GuildRank} capitalised ones. */
    @Test
    void displayFor_ignoresCaseAndSurroundingSpace() {
        assertEquals("Steward", RankDisplayMap.displayFor("strategist"));
        assertEquals("Steward", RankDisplayMap.displayFor("OWNER"));
        assertEquals("Returner", RankDisplayMap.displayFor(" captain "));
    }

    @Test
    void displayFor_passesAnUnknownRankAndNullThrough() {
        assertEquals("Moderator", RankDisplayMap.displayFor("Moderator"));
        assertEquals("", RankDisplayMap.displayFor(""));
        assertNull(RankDisplayMap.displayFor(null));
    }
}
