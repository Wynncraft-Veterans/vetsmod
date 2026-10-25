package org.wynnvets.guild;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GuildChecker#ownedByOtherAccount}, the rule that decides whether a stored
 * {@code /gu stats} result came from another Minecraft account than the one that just joined a
 * world, and {@link GuildChecker#worldJoinShouldClear}, the rule for clearing a stored result on
 * Wynntils' word at world join. The result and its owner are stored per install, so an alt would
 * otherwise inherit the main's result (and its {@code guild} registration).
 *
 * <p>Only those pure rules are exercised: {@link GuildChecker}'s other methods reach
 * {@code VetsConfig} and the player. Calling it initializes {@code GuildChecker}, whose static
 * state is a few primitives and an enum, with no Minecraft or Fabric dependency.</p>
 */
class GuildCheckerOwnerTest {

    @Test
    void ownedByOtherAccount_emptyOwner_isALegacyResultAndIsAdopted() {
        assertFalse(GuildChecker.ownedByOtherAccount("", "Wenweia"));
        assertFalse(GuildChecker.ownedByOtherAccount("", ""));
    }

    @Test
    void ownedByOtherAccount_sameName_ignoringCase_isNotAnotherAccount() {
        assertFalse(GuildChecker.ownedByOtherAccount("Wenweia", "Wenweia"));
        assertFalse(GuildChecker.ownedByOtherAccount("Wenweia", "wenweia"));
        assertFalse(GuildChecker.ownedByOtherAccount("WENWEIA", "wenWEia"));
    }

    @Test
    void worldJoinShouldClear_onlyAReturnersResultThatWynntilsNamesAnotherGuildFor() {
        GuildChecker.GuildCheckResult returners = GuildChecker.GuildCheckResult.RETURNERS;
        assertTrue(GuildChecker.worldJoinShouldClear(returners, true, "Other Guild"));
        assertFalse(GuildChecker.worldJoinShouldClear(returners, true, "Returners"));
        assertFalse(GuildChecker.worldJoinShouldClear(returners, false, ""), "no guild known yet");
        assertFalse(
                GuildChecker.worldJoinShouldClear(
                        GuildChecker.GuildCheckResult.GUILDLESS, true, "Returners"),
                "Wynntils' name may be left from an earlier scan");
        assertFalse(
                GuildChecker.worldJoinShouldClear(
                        GuildChecker.GuildCheckResult.OTHER_GUILD, true, "Returners"));
        assertFalse(
                GuildChecker.worldJoinShouldClear(
                        GuildChecker.GuildCheckResult.UNKNOWN, true, "Other Guild"));
    }

    @Test
    void ownedByOtherAccount_differentName_isAnotherAccount() {
        assertTrue(GuildChecker.ownedByOtherAccount("Wenweia", "Holidaze"));
        assertTrue(GuildChecker.ownedByOtherAccount("Wenweia", "Wenweia2"));
        assertTrue(GuildChecker.ownedByOtherAccount("Wenweia", ""));
    }
}
