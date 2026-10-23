package org.wynnvets.chat.spoiler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpoilerCodecTest {

    @Test
    void containsPipeSpoiler_nullEmptyAndNoMarker() {
        assertFalse(SpoilerCodec.containsPipeSpoiler(null));
        assertFalse(SpoilerCodec.containsPipeSpoiler(""));
        assertFalse(SpoilerCodec.containsPipeSpoiler("plain text"));
        assertFalse(SpoilerCodec.containsPipeSpoiler("|| not a real spoiler"));
    }

    @Test
    void containsPipeSpoiler_singleAndMultiple() {
        assertTrue(SpoilerCodec.containsPipeSpoiler("hello ||world||"));
        assertTrue(SpoilerCodec.containsPipeSpoiler("||a|| and ||b||"));
    }

    @Test
    void containsEncodedSpoiler_requiresBothDelimiters() {
        String onlyStart = "x" + SpoilerCodec.SPOILER_START + "y";
        String onlyEnd = "x" + SpoilerCodec.SPOILER_END + "y";
        String both = "" + SpoilerCodec.SPOILER_START + 'A' + SpoilerCodec.SPOILER_END;

        assertFalse(SpoilerCodec.containsEncodedSpoiler(null));
        assertFalse(SpoilerCodec.containsEncodedSpoiler(""));
        assertFalse(SpoilerCodec.containsEncodedSpoiler("plain"));
        assertFalse(SpoilerCodec.containsEncodedSpoiler(onlyStart));
        assertFalse(SpoilerCodec.containsEncodedSpoiler(onlyEnd));
        assertTrue(SpoilerCodec.containsEncodedSpoiler(both));
    }

    @Test
    void encodeSpoilers_passesThroughWhenNoMarkers() {
        assertSame(null, SpoilerCodec.encodeSpoilers(null));
        // empty short-circuits via the isEmpty() branch and returns the same reference
        String empty = "";
        assertSame(empty, SpoilerCodec.encodeSpoilers(empty));
        String plain = "no markers here";
        assertEquals(plain, SpoilerCodec.encodeSpoilers(plain));
    }

    @Test
    void encodeSpoilers_wrapsContentWithVisiblePrefix() {
        String encoded = SpoilerCodec.encodeSpoilers("a ||x|| b");
        assertTrue(
                encoded.contains(SpoilerCodec.WRAPPER_PREFIX),
                "expected visible wrapper prefix in: " + encoded);
        assertTrue(
                encoded.contains(SpoilerCodec.WRAPPER_SUFFIX),
                "expected visible wrapper suffix in: " + encoded);
        assertTrue(SpoilerCodec.containsEncodedSpoiler(encoded));
    }

    @Test
    void roundTrip_simpleAsciiSpoiler() {
        String original = "||hello||";
        assertEquals(original, SpoilerCodec.decodeToDiscord(SpoilerCodec.encodeSpoilers(original)));
    }

    @Test
    void roundTrip_multipleSpoilersPreservesSurroundingText() {
        String original = "before ||a|| middle ||bb|| end";
        assertEquals(original, SpoilerCodec.decodeToDiscord(SpoilerCodec.encodeSpoilers(original)));
    }

    @Test
    void decodeContent_emptyReturnsEmpty() {
        assertEquals("", SpoilerCodec.decodeContent(""));
        assertEquals("", SpoilerCodec.decodeContent(null));
    }

    @Test
    void decodeContent_truncatedEscapeDoesNotThrow() {
        // A lone ESCAPE char (U+F700) at the end with no following 3 digits must not throw.
        String truncated = "";
        assertDoesNotThrow(() -> SpoilerCodec.decodeContent(truncated));
    }

    @Test
    void decodeToDiscord_stripsVisibleWrapper() {
        String encoded = SpoilerCodec.encodeSpoilers("plain ||spoil||");
        String decoded = SpoilerCodec.decodeToDiscord(encoded);
        // The [Spoiler: ...] wrapper must not survive the decode.
        assertFalse(
                decoded.contains(SpoilerCodec.WRAPPER_PREFIX),
                "wrapper prefix should be stripped, got: " + decoded);
        assertEquals("plain ||spoil||", decoded);
    }

    @Test
    void roundTrip_highCodepointViaEscapePath() {
        // 'ÿ' is U+00FF (255), above DIRECT_MAX (253), forcing the ESCAPE encoding path.
        String original = "||ÿ||";
        assertEquals(original, SpoilerCodec.decodeToDiscord(SpoilerCodec.encodeSpoilers(original)));
    }

    // Banned characters. Fixtures are built from escapes.

    @Test
    void encodeSpoilers_dropsTheHardSetBeforeEncoding() {
        String encoded = SpoilerCodec.encodeSpoilers("||ok\u00C1\uE07E\uE080\uE086ok||");
        assertFalse(encoded.contains("\u00C1"), "raw U+00C1 in: " + encoded);
        assertFalse(encoded.contains("\uF6C3"), "U+00C1's direct encoding in: " + encoded);
        assertFalse(encoded.contains("\uF700"), "an escape for a hard char in: " + encoded);
        assertEquals("||okok||", SpoilerCodec.decodeToDiscord(encoded));
    }

    @Test
    void encodeSpoilers_dropsTheSoftSetBeforeEncoding() {
        String encoded = SpoilerCodec.encodeSpoilers("||ok\uE041\u00A7ok||");
        // The encoded form itself: the decoder strips both sets too, so a round trip alone would
        // pass without the encode strip.
        assertEquals(SpoilerCodec.encodeSpoilers("||okok||"), encoded);
        assertEquals("||okok||", SpoilerCodec.decodeToDiscord(encoded));
    }

    @Test
    void encodeSpoilers_allBannedContentLeavesAnEmptyBlock() {
        String encoded = SpoilerCodec.encodeSpoilers("||\u00C1||");
        assertTrue(
                encoded.contains("" + SpoilerCodec.SPOILER_START + SpoilerCodec.SPOILER_END),
                "not an empty block: " + encoded);
        assertEquals("||||", SpoilerCodec.decodeToDiscord(encoded));
    }

    @Test
    void decodeContent_handWrittenBlocksCannotProduceAHardChar() {
        // U+00C1 by direct encoding.
        assertEquals("", SpoilerCodec.decodeContent("\uF6C3"));
        // U+E080 by the escape encodeContent itself writes: digits 68, 225, 0.
        assertEquals("", SpoilerCodec.decodeContent("\uF700\uF646\uF6E3\uF602"));
        // Printable ASCII after the escape used to wrap round to U+E080 and U+00C1. The range
        // check drops these, and the decode-side strip would drop the result anyway.
        assertEquals("", SpoilerCodec.decodeContent("\uF700d\"Z"));
        assertEquals("", SpoilerCodec.decodeContent("\uF700!|("));
    }

    @Test
    void decodeContent_dropsASoftGlyphReachedByEscape() {
        // U+E041 (a pill letter): digits 5, 225, 0.
        assertEquals("", SpoilerCodec.decodeContent("\uF700\uF607\uF6E3\uF602"));
    }

    @Test
    void decodeContent_dropsAnEscapeWithAnOutOfRangeDigitWhole() {
        // 'x' is below U+F602; the escape goes, and the 'A' after it (U+F643) stays.
        assertEquals("A", SpoilerCodec.decodeContent("\uF700x\uF602\uF602\uF643"));
    }

    @Test
    void decodeContent_digitRangeEdges() {
        // Digit 254 (U+F700) and digit -1 (U+F601) sit just outside the range: dropped whole.
        assertEquals("A", SpoilerCodec.decodeContent("\uF700\uF700\uF602\uF602\uF643"));
        assertEquals("A", SpoilerCodec.decodeContent("\uF700\uF601\uF602\uF602\uF643"));
        // Digit 253 (U+F6FF) is the top of the range: 254 + 253 is U+01FB.
        assertEquals("\u01FB", SpoilerCodec.decodeContent("\uF700\uF6FF\uF602\uF602"));
    }

    @Test
    void decodeContent_dropsAnEscapeAboveUffffWholeNotUnitByUnit() {
        // All three digits are in range but sum past U+FFFF. Skipping one unit would decode the
        // digits as three direct U+00FD chars, so this pins that all four units go.
        assertEquals("A", SpoilerCodec.decodeContent("\uF700\uF6FF\uF6FF\uF6FF\uF643"));
    }

    @Test
    void roundTrip_highestBmpCharStillDecodes() {
        // U+FFFF is digits 3, 3, 1, the largest value an escape may carry.
        String original = "||\uFFFF||";
        assertEquals(original, SpoilerCodec.decodeToDiscord(SpoilerCodec.encodeSpoilers(original)));
    }

    @Test
    void roundTrip_cjkCharacterViaEscapePath() {
        // CJK char also exercises the multi-digit base-254 path.
        String original = "||中||"; // 中
        assertEquals(original, SpoilerCodec.decodeToDiscord(SpoilerCodec.encodeSpoilers(original)));
    }
}
