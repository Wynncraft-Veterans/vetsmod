package org.wynnvets.chat.spoiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
        assertTrue(encoded.contains(SpoilerCodec.WRAPPER_PREFIX),
                "expected visible wrapper prefix in: " + encoded);
        assertTrue(encoded.contains(SpoilerCodec.WRAPPER_SUFFIX),
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
        assertFalse(decoded.contains(SpoilerCodec.WRAPPER_PREFIX),
                "wrapper prefix should be stripped, got: " + decoded);
        assertEquals("plain ||spoil||", decoded);
    }

    @Test
    void roundTrip_highCodepointViaEscapePath() {
        // 'ÿ' is U+00FF (255), above DIRECT_MAX (253), forcing the ESCAPE encoding path.
        String original = "||ÿ||";
        assertEquals(original, SpoilerCodec.decodeToDiscord(SpoilerCodec.encodeSpoilers(original)));
    }

    @Test
    void roundTrip_cjkCharacterViaEscapePath() {
        // CJK char also exercises the multi-digit base-254 path.
        String original = "||中||"; // 中
        assertEquals(original, SpoilerCodec.decodeToDiscord(SpoilerCodec.encodeSpoilers(original)));
    }
}
