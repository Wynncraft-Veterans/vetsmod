package org.wynnvets.guild;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StaffRankChecker}'s two response predicates — the pure half
 * of the {@code /gu rank} handshake.
 *
 * <p>Both are unordered substring tests, not a parse: three fragments for the
 * unauthorized reply, two for the authorized one, each checked with
 * {@code contains} against the whole lower-cased line. Nothing constrains
 * their order or adjacency, so the false-positive surface pinned below is real
 * behaviour rather than a hypothetical.</p>
 *
 * <p>KNOWN BUG (pinned, not fixed): both fold case with the no-argument
 * {@code toLowerCase()}. See {@code
 * .claude/ephemeral/bugs-found-via-mellow-rain/default-locale-case-folding-cluster.md}.
 * This site is the one place in that cluster where the defect is reachable with
 * the server's real wording rather than only in theory — see the locale section
 * at the bottom.</p>
 *
 * <p>NOTE: only the predicates are exercised. {@code processMessage},
 * {@code reset} and {@code loadPersistedState} all reach {@code VetsConfig},
 * and {@code refreshStaffStatusIfNeeded} queues through Wynntils
 * {@code Handlers.Command}. {@link StaffRankChecker}'s static state is six
 * primitives and three {@code long} constants, so nothing loads during
 * class-init; if that changes, this test fails at class-init and the fix is to
 * extract the predicates.</p>
 */
class StaffRankCheckerTest {

    /** What Wynncraft sends a non-staff player who runs {@code /gu rank}. */
    private static final String UNAUTHORIZED =
            "You must be a Captain or higher to use this command!";

    /** What it sends a staff player: the argument-usage hint, because
     *  {@code /gu rank} with no arguments is incomplete rather than forbidden. */
    private static final String AUTHORIZED = "Invalid arguments, try: /gu rank [name] [rank]";

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    private static final Locale ORIGINAL_DEFAULT = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(ORIGINAL_DEFAULT);
    }

    // ----- isStaffRankUnauthorizedResponse -----

    @Test
    void unauthorized_matchesTheRealServerLine() {
        assertTrue(StaffRankChecker.isStaffRankUnauthorizedResponse(UNAUTHORIZED));
    }

    @Test
    void unauthorized_foldsCase() {
        // Passed shouting and un-folded: the predicate has to do the
        // lower-casing itself. Folding it here first made this vacuous.
        assertTrue(
                StaffRankChecker.isStaffRankUnauthorizedResponse(
                        "YOU MUST BE A CAPTAIN OR HIGHER TO USE THIS COMMAND!"),
                "the predicate lower-cases its input, so the caller need not");
        assertTrue(
                StaffRankChecker.isStaffRankUnauthorizedResponse(
                        "You Must Be A Captain Or Higher To Use This Command!"));
    }

    @Test
    void unauthorized_requiresAllThreeFragments() {
        assertFalse(
                StaffRankChecker.isStaffRankUnauthorizedResponse(
                        "You must be a Chief or higher to use this command!"),
                "the rank word is load-bearing");
        assertFalse(
                StaffRankChecker.isStaffRankUnauthorizedResponse("You must be a Captain!"),
                "without the trailing clause it is not the /gu rank refusal");
        assertFalse(
                StaffRankChecker.isStaffRankUnauthorizedResponse(
                        "Captain Bob asked you to use this command"),
                "without the opening clause either");
    }

    @Test
    void unauthorized_fragmentsAreUnorderedSoAnyLineCarryingAllThreeMatches() {
        // A live false-positive surface: three independent contains() calls
        // impose no order and no adjacency. A guild-chat line that happens to
        // quote the refusal backwards is accepted as the server's own reply.
        assertTrue(
                StaffRankChecker.isStaffRankUnauthorizedResponse(
                        "to use this command captain says you must be a member"));
    }

    @Test
    void unauthorized_nullAndEmpty() {
        assertFalse(StaffRankChecker.isStaffRankUnauthorizedResponse(null));
        assertFalse(StaffRankChecker.isStaffRankUnauthorizedResponse(""));
    }

    // ----- isStaffRankAuthorizedResponse -----

    @Test
    void authorized_matchesTheRealServerLine() {
        assertTrue(StaffRankChecker.isStaffRankAuthorizedResponse(AUTHORIZED));
    }

    @Test
    void authorized_requiresBothFragments() {
        assertFalse(
                StaffRankChecker.isStaffRankAuthorizedResponse("Invalid arguments, try: /gu list"),
                "the usage hint must name the rank arguments");
        assertFalse(
                StaffRankChecker.isStaffRankAuthorizedResponse("/gu rank [name] [rank]"),
                "the bare usage line without the error prefix is not a response");
    }

    @Test
    void authorized_matchesTheLiteralBracketsNotAPattern() {
        // "rank [name] [rank]" is a substring test, so the square brackets are
        // data. A line with the placeholders filled in does not match.
        assertFalse(
                StaffRankChecker.isStaffRankAuthorizedResponse(
                        "Invalid arguments, try: /gu rank Alice Captain"));
    }

    @Test
    void authorized_nullAndEmpty() {
        assertFalse(StaffRankChecker.isStaffRankAuthorizedResponse(null));
        assertFalse(StaffRankChecker.isStaffRankAuthorizedResponse(""));
    }

    // ----- The two are disjoint on real input -----

    @Test
    void theTwoPredicatesDoNotBothFireOnEitherRealLine() {
        // processMessage checks unauthorized first and treats authorized as an
        // else-branch, so an overlap would silently resolve to "not staff".
        assertFalse(StaffRankChecker.isStaffRankAuthorizedResponse(UNAUTHORIZED));
        assertFalse(StaffRankChecker.isStaffRankUnauthorizedResponse(AUTHORIZED));
    }

    // ----- Default-locale case folding (bug, pinned as-is) -----

    @Test
    void authorized_failsUnderATurkishDefaultLocaleOnTheRealServerLine() {
        // "Invalid" starts with an uppercase I, which folds to the dotless
        // U+0131 under a Turkish default. The lower-cased line then no longer
        // contains "invalid arguments, try:" and a staff player is never
        // recognised as staff. Reachable with the server's actual wording, not
        // just in theory. Fixing it means passing Locale.ROOT; this assertion
        // flips to assertTrue when that lands.
        Locale.setDefault(TURKISH);
        assertFalse(StaffRankChecker.isStaffRankAuthorizedResponse(AUTHORIZED));
    }

    @Test
    void unauthorized_survivesATurkishDefaultLocaleOnlyBecauseItsWordingHasNoCapitalI() {
        // Same defect, no symptom: every I in the refusal is already lowercase.
        // That asymmetry is why the cluster has to be fixed as a cluster — a
        // site-by-site pass would find nothing wrong here and stop.
        Locale.setDefault(TURKISH);
        assertTrue(StaffRankChecker.isStaffRankUnauthorizedResponse(UNAUTHORIZED));
        assertFalse(
                StaffRankChecker.isStaffRankUnauthorizedResponse(
                        UNAUTHORIZED.toUpperCase(Locale.ROOT)),
                "shout the same line and the fold breaks it too");
    }
}
