package org.wynnvets.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UrlWrapJoiner#join}: joining a URL that Wynncraft hard-broke across chat
 * lines, from the raw line breaks.
 *
 * <p>The three captured cases are lines from an in-game session, copied from the debug log's
 * {@code onGuildChat raw [...]} and {@code onGuildChat plain [...]} entries (the log shows the
 * newline as a backslash and an {@code n}). Every glyph-art code point is a unicode escape.
 * {@link #HEAD_FIRST} and {@link #HEAD_CONT} are the line headers, {@link #WRAP} the
 * continuation prefix, and the unwrapped message is what {@code StyledTextUtils.unwrap} left
 * after the {@code Wenweia: } header.</p>
 */
class UrlWrapJoinerTest {

    /** A first line's header: the guild badge and the sender's rank pill. */
    private static final String HEAD_FIRST =
            "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE \uE060\uDAFF\uDFFF\uE032\uDAFF\uDFFF\uE037\uDAFF\uDFFF\uE038\uDAFF\uDFFF\uE034\uDAFF\uDFFF\uE035\uDAFF\uDFFF\uE062\uDAFF\uDFE2\uE002\uE007\uE008\uE004\uE005\uDB00\uDC02 ";

    /** A continuation line's header: the compact block indicator and the sender's rank pill. */
    private static final String HEAD_CONT =
            "\uDAFF\uDFFC\uE001\uDB00\uDC06 \uE060\uDAFF\uDFFF\uE032\uDAFF\uDFFF\uE037\uDAFF\uDFFF\uE038\uDAFF\uDFFF\uE034\uDAFF\uDFFF\uE035\uDAFF\uDFFF\uE062\uDAFF\uDFE2\uE002\uE007\uE008\uE004\uE005\uDB00\uDC02 ";

    /** The prefix of every continuation line: the compact block indicator, then one space. */
    private static final String WRAP = "\uDAFF\uDFFC\uE001\uDB00\uDC06 ";

    private static final String URL = "https://anni.wynnvets.org/";

    private static String a(int count) {
        return "a".repeat(count);
    }

    // ----- the three captures -----

    @Test
    void aUrlBrokenInPlaceAcrossThreeLinesIsJoined() {
        // Wynncraft broke the URL mid-token: no space before either newline.
        String raw =
                HEAD_FIRST
                        + "Wenweia: a "
                        + URL
                        + a(12)
                        + "\n"
                        + WRAP
                        + a(51)
                        + "\n"
                        + WRAP
                        + a(11)
                        + " Then";
        String unwrapped = "a " + URL + a(12) + " " + a(51) + " " + a(11) + " Then";

        assertEquals("a " + URL + a(12 + 51 + 11) + " Then", UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void aWordWrapThatKeptItsTrailingSpaceLeavesTheUrlAndTheWordAfterItAlone() {
        String raw =
                HEAD_CONT
                        + "Wenweia: padding padding padding padding "
                        + "\n"
                        + WRAP
                        + URL
                        + "me Then more";
        // The unwrap keeps the kept space and adds its own, hence two spaces.
        String unwrapped = "padding padding padding padding  " + URL + "me Then more";

        assertEquals(unwrapped, UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void aWordWrapThatDroppedItsTrailingSpaceLeavesTheUrlAndTheWordAfterItAlone() {
        String raw =
                HEAD_CONT
                        + "Wenweia: padding padding padding padding padding"
                        + "\n"
                        + WRAP
                        + "padding padding padding "
                        + URL
                        + "me Then"
                        + "\n"
                        + WRAP
                        + "more";
        String unwrapped = "padding ".repeat(8) + URL + "me Then more";

        assertEquals(unwrapped, UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void theCapturedUnwrappedTextsAreTheOnesTheFixturesRebuild() {
        // Pins the fixtures against the log: the unwrapped texts the three captures produced.
        assertEquals(
                "a https://anni.wynnvets.org/aaaaaaaaaaaa aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa aaaaaaaaaaa Then",
                "a " + URL + a(12) + " " + a(51) + " " + a(11) + " Then");
        assertEquals(
                "padding padding padding padding  https://anni.wynnvets.org/me Then more",
                "padding padding padding padding  " + URL + "me Then more");
        assertEquals(
                "padding padding padding padding padding padding padding padding https://anni.wynnvets.org/me Then more",
                "padding ".repeat(8) + URL + "me Then more");
    }

    // ----- synthetic cases -----

    @Test
    void noUrlIsUntouched() {
        String raw = HEAD_CONT + "Wenweia: hello there\n" + WRAP + "world";
        String unwrapped = "hello there world";

        assertSame(unwrapped, UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void noNewlineIsUntouched() {
        String raw = HEAD_CONT + "Wenweia: see " + URL + "path Then more";
        String unwrapped = "see " + URL + "path Then more";

        assertSame(unwrapped, UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void aUrlThatEndsTheMessageIsJoined() {
        String raw = HEAD_CONT + "Wenweia: go " + URL + "aaaa\n" + WRAP + "bbbb";

        assertEquals("go " + URL + "aaaabbbb", UrlWrapJoiner.join(raw, "go " + URL + "aaaa bbbb"));
    }

    @Test
    void aUrlGluedToPunctuationIsJoined() {
        // "(https://..." or "<https://...>" is one token that does not start with the scheme.
        String raw = HEAD_CONT + "Wenweia: see (" + URL + "aaaa\n" + WRAP + "bbbb)";

        assertEquals(
                "see (" + URL + "aaaabbbb)", UrlWrapJoiner.join(raw, "see (" + URL + "aaaa bbbb)"));
    }

    @Test
    void aBracketedUrlThatClosesOnItsLineIsComplete() {
        // A word wrap that dropped its space after "(URL)": the closing bracket shows the URL
        // ended there, so "Then" is not glued on.
        String raw = HEAD_CONT + "Wenweia: see (" + URL + "me)\n" + WRAP + "Then more";
        String unwrapped = "see (" + URL + "me) Then more";

        assertSame(unwrapped, UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void twoBrokenUrlsAreEachJoined() {
        String raw =
                HEAD_CONT
                        + "Wenweia: "
                        + URL
                        + "xx\n"
                        + WRAP
                        + "yy and https://b.org/zz\n"
                        + WRAP
                        + "ww";
        String unwrapped = URL + "xx yy and https://b.org/zz ww";

        assertEquals(URL + "xxyy and https://b.org/zzww", UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void aChainStopsAtAFragmentThatDoesNotFillItsLine() {
        // "y z" shares its line with a word, so the line did not fill and the next break is a
        // word wrap, not a second hard break.
        String raw = HEAD_CONT + "Wenweia: " + URL + "x\n" + WRAP + "y z\n" + WRAP + "w";

        assertEquals(URL + "xy z w", UrlWrapJoiner.join(raw, URL + "x y z w"));
    }

    @Test
    void aFragmentThatFillsItsLineContinuesTheChain() {
        String raw = HEAD_CONT + "Wenweia: " + URL + "x\n" + WRAP + "yy\n" + WRAP + "zz";

        assertEquals(URL + "xyyzz", UrlWrapJoiner.join(raw, URL + "x yy zz"));
    }

    @Test
    void aNextLineThatStartsWithASpaceIsNotAHardBreak() {
        String raw = HEAD_CONT + "Wenweia: " + URL + "x\n" + WRAP + " y";
        String unwrapped = URL + "x  y";

        assertEquals(unwrapped, UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void aChainTheUnwrappedTextDoesNotHoldLeavesItAlone() {
        String raw = HEAD_CONT + "Wenweia: " + URL + "x\n" + WRAP + "y";
        String unwrapped = "something else entirely";

        assertSame(unwrapped, UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void onlyTheFirstOccurrenceOfAChainIsJoined() {
        String raw = HEAD_CONT + "Wenweia: " + URL + "x\n" + WRAP + "y";
        String unwrapped = URL + "x y " + URL + "x y";

        assertEquals(URL + "xy " + URL + "x y", UrlWrapJoiner.join(raw, unwrapped));
    }

    @Test
    void nullInputsPassThrough() {
        assertEquals("text", UrlWrapJoiner.join(null, "text"));
        assertNull(UrlWrapJoiner.join(HEAD_CONT + "Wenweia: " + URL + "x\n" + WRAP + "y", null));
    }
}
