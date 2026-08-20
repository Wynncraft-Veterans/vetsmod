package org.wynnvets.chat.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the eight pure leaves of {@link MessageFanoutDispatcher}'s echo
 * matcher — the string half of deciding whether an outbound {@code /msg} came
 * back as a server echo.
 *
 * <p>Fixtures are plain text with formatting stripped but PUA glyphs retained,
 * because the entry point is fed {@code message.getString()}.</p>
 *
 * <p>Two live false-positive surfaces are pinned rather than described, since
 * both look like sloppiness a later pass would tidy away and both are load
 * bearing: {@code isOutboundRecipientTarget} matches the recipient segment with
 * {@code contains}, so a message to {@code bobby} is accepted as one to
 * {@code bob}; and {@code containsCensoredVariant} treats {@code *} in the
 * <em>message</em> as a wildcard, so a run of asterisks at least as long as the
 * payload matches anything at all.</p>
 *
 * <p>{@code shouldSuppressFeedback} is deliberately not covered. Populating its
 * queue would need a second seam plus a reset hook that does not exist, for an
 * orchestrator no later phase is extracting.</p>
 *
 * <p>NOTE: {@link MessageFanoutDispatcher} imports Minecraft and Wynntils, but
 * its static state is six primitives, a queue and a lock object, so nothing
 * loads during class-init. If a future contributor adds a static initializer
 * that loads MC or Wynntils, this test starts failing at class-init time and
 * the fix is to extract the leaves into a pure type.</p>
 */
class MessageFanoutDispatcherTest {

    /** The private-message separator glyph the outbound line carries. */
    private static final String SEP = String.valueOf((char) 0xE003);

    // ----- isOutboundRecipientTarget -----

    @Test
    void isOutboundRecipientTarget_matchesTheSegmentBetweenGlyphAndColon() {
        assertTrue(
                MessageFanoutDispatcher.isOutboundRecipientTarget(
                        "you " + SEP + " bob: hi", "bob"));
    }

    @Test
    void isOutboundRecipientTarget_requiresBothTheGlyphAndAColonAfterIt() {
        assertFalse(
                MessageFanoutDispatcher.isOutboundRecipientTarget("you bob: hi", "bob"),
                "no separator glyph — not an outbound private message");
        assertFalse(
                MessageFanoutDispatcher.isOutboundRecipientTarget("you " + SEP + " bob hi", "bob"),
                "no colon after the glyph");
        assertFalse(
                MessageFanoutDispatcher.isOutboundRecipientTarget("a: b " + SEP + " bob", "bob"),
                "the colon must come after the glyph, not before it");
    }

    @Test
    void isOutboundRecipientTarget_recipientIsASubstringTestSoPrefixesFalseMatch() {
        // Live false-positive surface: a message addressed to "bobby" is
        // accepted as one addressed to "bob". Narrowing this to equals() would
        // change which feedback lines are attributed to which dispatch.
        assertTrue(
                MessageFanoutDispatcher.isOutboundRecipientTarget(
                        "you " + SEP + " bobby: hi", "bob"));
        assertFalse(
                MessageFanoutDispatcher.isOutboundRecipientTarget(
                        "you " + SEP + " bob: hi", "bobby"),
                "the containment only runs one way");
    }

    @Test
    void isOutboundRecipientTarget_looksOnlyAtTheRecipientSegment() {
        // The name appearing in the body rather than the header is not a match.
        assertFalse(
                MessageFanoutDispatcher.isOutboundRecipientTarget(
                        "you " + SEP + " alice: tell bob hi", "bob"));
    }

    // ----- isOfflineRecipientMessage / isOfflineGuidanceMessage -----

    @Test
    void isOfflineRecipientMessage_bothServerWordings() {
        assertTrue(MessageFanoutDispatcher.isOfflineRecipientMessage("bob is not online", "bob"));
        assertTrue(
                MessageFanoutDispatcher.isOfflineRecipientMessage(
                        "bob is not currently online", "bob"));
    }

    @Test
    void isOfflineRecipientMessage_isAnchoredOnTheNameNotJustThePhrase() {
        assertFalse(
                MessageFanoutDispatcher.isOfflineRecipientMessage("alice is not online", "bob"),
                "the name and the phrase have to be adjacent");
    }

    @Test
    void isOfflineGuidanceMessage_bothFragments() {
        assertTrue(
                MessageFanoutDispatcher.isOfflineGuidanceMessage(
                        "be sure to use exact names, prediction does not work if they are"
                                + " offline"));
        assertTrue(
                MessageFanoutDispatcher.isOfflineGuidanceMessage(
                        "or the user is on a separate server"));
        assertFalse(MessageFanoutDispatcher.isOfflineGuidanceMessage("bob is not online"));
    }

    // ----- normalizeForEchoComparison -----

    @Test
    void normalize_stripsWhitespaceAndFoldsCase() {
        assertEquals(
                "helloworld", MessageFanoutDispatcher.normalizeForEchoComparison("Hello World"));
        assertEquals("ab", MessageFanoutDispatcher.normalizeForEchoComparison("A\t\nB"));
    }

    @Test
    void normalize_stripsPuaAndTheOtherInvisibleCategories() {
        // PRIVATE_USE, CONTROL, FORMAT, SURROGATE and UNASSIGNED all go. This is
        // the widest of the repo's PUA predicates — it strips strictly more than
        // PillCodec.isEncoded tests for.
        assertEquals(
                "ab",
                MessageFanoutDispatcher.normalizeForEchoComparison("a" + (char) 0xE003 + "b"),
                "PRIVATE_USE");
        assertEquals(
                "ab",
                MessageFanoutDispatcher.normalizeForEchoComparison("a" + (char) 0x0007 + "b"),
                "CONTROL");
        assertEquals(
                "ab",
                MessageFanoutDispatcher.normalizeForEchoComparison("a" + (char) 0x200B + "b"),
                "FORMAT");
        assertEquals(
                "ab",
                MessageFanoutDispatcher.normalizeForEchoComparison("a" + (char) 0xD800 + "b"),
                "an unpaired SURROGATE");
        assertEquals(
                "ab",
                MessageFanoutDispatcher.normalizeForEchoComparison(
                        "a" + new StringBuilder().appendCodePoint(0xD0002) + "b"),
                "UNASSIGNED above the BMP — the plane-13 pill terminator");
    }

    @Test
    void normalize_stripsUnassignedCodepointsInsideTheBmpToo() {
        // This is what makes the predicate *unbounded*, and it is the single
        // property that separates it from the canonical isCustomGlyph used by
        // the guild-chat parsers, which qualify UNASSIGNED with `cp > 0xFFFF`.
        // The supplementary probe above passes under either form; only this one
        // discriminates. U+0378 is unassigned and inside the BMP.
        assertEquals(
                "ab",
                MessageFanoutDispatcher.normalizeForEchoComparison("a" + (char) 0x0378 + "b"),
                "a bounded UNASSIGNED clause would keep this character");
    }

    @Test
    void normalize_keepsEmojiBecauseTheyAreOtherSymbolNotPrivateUse() {
        // The padlock the bridge prefixes locked messages with survives
        // normalisation, so it participates in the echo comparison on both
        // sides rather than being silently dropped from one.
        String padlock = new StringBuilder().appendCodePoint(0x1F510).toString();
        assertEquals(
                padlock + "hi",
                MessageFanoutDispatcher.normalizeForEchoComparison(padlock + " Hi"));
    }

    @Test
    void normalize_punctuationSurvives() {
        // Only the invisible categories are removed; '*' in particular has to
        // survive or the censored-variant match below could never fire.
        assertEquals("h***o!", MessageFanoutDispatcher.normalizeForEchoComparison("H***o!"));
    }

    // ----- extractEchoTokens -----

    @Test
    void extractEchoTokens_splitsOnEveryNonAlphanumeric() {
        assertEquals(
                List.of("hello", "world"),
                MessageFanoutDispatcher.extractEchoTokens("Hello, world!"));
        assertEquals(
                List.of("a1", "b2"),
                MessageFanoutDispatcher.extractEchoTokens("a1-b2"),
                "digits are token characters, the hyphen is a separator");
    }

    @Test
    void extractEchoTokens_emptyAndPunctuationOnlyYieldNoTokens() {
        assertEquals(List.of(), MessageFanoutDispatcher.extractEchoTokens(""));
        assertEquals(List.of(), MessageFanoutDispatcher.extractEchoTokens("--- !!! ---"));
    }

    // ----- containsTokenSubsequence -----

    @Test
    void containsTokenSubsequence_isASubsequenceNotAContiguousRun() {
        // The greedy two-pointer walk allows arbitrary gaps, so the server
        // inserting words into the echo does not defeat the match.
        assertTrue(
                MessageFanoutDispatcher.containsTokenSubsequence(
                        List.of("a", "x", "b", "y", "c"), List.of("a", "b", "c")));
        assertFalse(
                MessageFanoutDispatcher.containsTokenSubsequence(
                        List.of("a", "b", "c"), List.of("a", "c", "b")),
                "order still has to hold");
    }

    @Test
    void containsTokenSubsequence_emptyPayloadIsVacuouslyContained() {
        assertTrue(MessageFanoutDispatcher.containsTokenSubsequence(List.of("a"), List.of()));
        assertFalse(MessageFanoutDispatcher.containsTokenSubsequence(List.of(), List.of("a")));
    }

    // ----- containsCensoredVariant -----

    @Test
    void containsCensoredVariant_starInTheMessageStandsForAnyCharacter() {
        // Wynncraft's profanity filter replaces characters with '*', so the echo
        // of "hello" can come back as "h***o".
        assertTrue(MessageFanoutDispatcher.containsCensoredVariant("say h***o there", "hello"));
        assertTrue(MessageFanoutDispatcher.containsCensoredVariant("hello", "hello"));
    }

    @Test
    void containsCensoredVariant_aRunOfStarsMatchesAnyPayloadOfThatLength() {
        // Live false-positive surface, and the reason the caller only reaches
        // here after the plain contains() has already failed: the wildcard is
        // unbounded, so an all-asterisk region matches every payload short
        // enough to fit inside it.
        assertTrue(MessageFanoutDispatcher.containsCensoredVariant("*****", "hello"));
        assertTrue(MessageFanoutDispatcher.containsCensoredVariant("*****", "world"));
        assertFalse(
                MessageFanoutDispatcher.containsCensoredVariant("****", "hello"),
                "the run still has to be at least as long as the payload");
    }

    @Test
    void containsCensoredVariant_theWildcardIsOneDirectionalOnly() {
        // A '*' on the payload side is a literal, not a wildcard.
        assertFalse(MessageFanoutDispatcher.containsCensoredVariant("hello", "h***o"));
    }

    @Test
    void containsCensoredVariant_emptyPayloadIsFalseNotVacuouslyTrue() {
        assertFalse(MessageFanoutDispatcher.containsCensoredVariant("anything", ""));
    }

    // ----- containsNormalizedPayload, the composition of the above -----

    @Test
    void containsNormalizedPayload_nullAndEmptyOnEitherSide() {
        assertFalse(MessageFanoutDispatcher.containsNormalizedPayload(null, "x"));
        assertFalse(MessageFanoutDispatcher.containsNormalizedPayload("x", null));
        assertFalse(MessageFanoutDispatcher.containsNormalizedPayload("", "x"));
        assertFalse(MessageFanoutDispatcher.containsNormalizedPayload("x", ""));
    }

    @Test
    void containsNormalizedPayload_payloadThatNormalisesAwayIsNotAMatch() {
        // A payload of nothing but whitespace and glyphs would otherwise be
        // contained in every message.
        assertFalse(
                MessageFanoutDispatcher.containsNormalizedPayload(
                        "anything", "  " + (char) 0xE003 + " "));
    }

    @Test
    void containsNormalizedPayload_matchesAcrossGlyphsSpacingAndCase() {
        assertTrue(
                MessageFanoutDispatcher.containsNormalizedPayload(
                        "you " + SEP + " bob: HEL" + (char) 0xE003 + "LO there", "hello"));
    }

    @Test
    void containsNormalizedPayload_fallsThroughToTheTokenSubsequence() {
        // Neither the plain contains() nor the censored variant fires here —
        // the fragments are present but separated, which only the token walk
        // sees.
        assertTrue(
                MessageFanoutDispatcher.containsNormalizedPayload(
                        "hello, world, friend", "hello, friend"));
    }

    @Test
    void containsNormalizedPayload_tokenBoundariesComeFromPunctuationOnlyNotSpaces() {
        // Non-obvious ordering: normalisation strips whitespace *before*
        // extractEchoTokens splits on non-alphanumerics, so a space never
        // produces a token boundary. Space-separated words collapse into one
        // token and the subsequence walk cannot see through them. Any later
        // refactor that tokenises the raw text instead of the normalised text
        // changes which echoes are recognised.
        assertEquals(
                List.of("helloworldfriend"),
                MessageFanoutDispatcher.extractEchoTokens(
                        MessageFanoutDispatcher.normalizeForEchoComparison("hello world friend")));
        assertFalse(
                MessageFanoutDispatcher.containsNormalizedPayload(
                        "hello world friend", "hello friend"),
                "the same pair separated by spaces rather than commas does not match");
    }
}
