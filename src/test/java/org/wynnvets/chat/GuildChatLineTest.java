package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GuildChatLine}'s two parsers: the header scan they share, and the cells where
 * they deliberately differ.
 *
 * <p>Everything above the first divergence heading exercises the shared header scan: find the
 * last custom glyph before the first colon, take the trimmed run between it and the colon as the
 * display name. A "custom glyph" is {@link org.wynnvets.chat.PillCodec#isCustomGlyph(int)
 * PillCodec.isCustomGlyph}, and these cases exercise the supplementary bound in it from the
 * caller's side;
 * {@link PillCodecTest#isCustomGlyph_acceptsSupplementaryUnassignedButNotBmpUnassigned()}
 * pins the same bound at the predicate itself. {@link GuildChatLine#parse} also returns
 * {@code null} for {@code null} and empty input, as its parameter documentation says.</p>
 *
 * <p>Everything below pins the three cells where the two parsers disagree:</p>
 *
 * <ol>
 *   <li>{@code parseServerLine} returns a char <em>index</em> into the original string, not a
 *       body substring;</li>
 *   <li>it keeps the rank indicator, which {@code parse} discards;</li>
 *   <li>it advances past <b>literal spaces only</b> where {@code parse} {@code trim()}s, so the
 *       body offset differs whenever the character after the colon is a tab or a newline.
 *       ({@code trim()} cuts at {@code U+0020}, not at {@code Character.isWhitespace}; neither
 *       method removes an EM SPACE.)</li>
 * </ol>
 *
 * <p>(3) is the cell a merge of the two parsers would flatten without anyone noticing, because
 * it needs a body that starts with whitespace that is not a space.</p>
 *
 * <p>{@link GuildChatLine} has no static state and no Wynntils import, so it loads without a
 * running client.</p>
 */
class GuildChatLineTest {

    /** A rank pill glyph — PRIVATE_USE, the ordinary case. */
    private static final String PUA = String.valueOf((char) 0xE062);

    /** The pill terminator — UNASSIGNED and above the BMP, so it also counts. */
    private static final String SUPPLEMENTARY_UNASSIGNED =
            new StringBuilder().appendCodePoint(0xD0002).toString();

    /** UNASSIGNED but inside the BMP, so the {@code cp > 0xFFFF} bound excludes it. */
    private static final String BMP_UNASSIGNED = String.valueOf((char) 0x0378);

    // ----- The shared header scan -----

    @Test
    void bothReadTheNameBetweenTheLastGlyphAndTheColon() {
        String line = PUA + " Metrafish: hello there";

        GuildChatLine.Parsed shared = GuildChatLine.parse(line);
        assertNotNull(shared);
        assertEquals("Metrafish", shared.username());
        assertEquals("hello there", shared.message());

        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals("Metrafish", server.username());
    }

    @Test
    void theNameMayContainSpaces() {
        // Wynncraft display names can carry a suffix, e.g. "Salted/Some Nickname".
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + " Salted/Some Nickname: hi");
        assertNotNull(shared);
        assertEquals("Salted/Some Nickname", shared.username());
    }

    @Test
    void theScanTakesTheLastGlyphNotTheFirst() {
        // The rank indicator is a run of glyphs; only what follows the final one
        // is the name.
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + "junk" + PUA + " Metrafish: hi");
        assertNotNull(shared);
        assertEquals("Metrafish", shared.username());
    }

    @Test
    void supplementaryUnassignedCountsAsAGlyphButBmpUnassignedDoesNot() {
        // The predicate is PRIVATE_USE || (UNASSIGNED && cp > 0xFFFF). The
        // supplementary bound is deliberate: unassigned BMP codepoints turn up
        // in ordinary text, supplementary ones are Wynncraft's markers.
        GuildChatLine.Parsed viaSupplementary =
                GuildChatLine.parse(SUPPLEMENTARY_UNASSIGNED + " Metrafish: hi");
        assertNotNull(viaSupplementary);
        assertEquals("Metrafish", viaSupplementary.username());

        assertNull(
                GuildChatLine.parse(BMP_UNASSIGNED + " Metrafish: hi"),
                "an unassigned BMP codepoint is not a rank glyph");
    }

    @Test
    void aLineWithNoGlyphBeforeTheColonIsRejected() {
        assertNull(GuildChatLine.parse("Metrafish: hi"));
        assertNull(GuildChatLine.parseServerLine("Metrafish: hi"));
    }

    @Test
    void aGlyphAfterTheColonDoesNotCount() {
        // The scan stops at the colon, so a glyph in the body is invisible.
        assertNull(GuildChatLine.parse("Metrafish: " + PUA + " hi"));
    }

    @Test
    void missingOrLeadingColonIsRejected() {
        assertNull(GuildChatLine.parse(PUA + " Metrafish hi"), "no colon at all");
        assertNull(GuildChatLine.parse(": " + PUA + " Metrafish"), "colonIndex <= 0");
        assertNull(GuildChatLine.parseServerLine(PUA + " Metrafish hi"));
    }

    @Test
    void aGlyphEndingExactlyAtTheColonLeavesNoRoomForAName() {
        // lastGlyphEnd >= colonIndex fires before the emptiness check.
        assertNull(GuildChatLine.parse(PUA + ": hi"));
        assertNull(GuildChatLine.parseServerLine(PUA + ": hi"));
    }

    @Test
    void aWhitespaceOnlyNameIsRejected() {
        assertNull(GuildChatLine.parse(PUA + "   : hi"), "the name trims to empty");
        assertNull(GuildChatLine.parseServerLine(PUA + "   : hi"));
    }

    @Test
    void theNameIsTrimmedOnBothSides() {
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + " \t Metrafish \t : hi");
        assertNotNull(shared);
        assertEquals("Metrafish", shared.username());

        GuildChatLine.ServerParsed server =
                GuildChatLine.parseServerLine(PUA + " \t Metrafish \t : hi");
        assertNotNull(server);
        assertEquals("Metrafish", server.username(), "both trim the name identically");
    }

    // ----- Null and empty input -----

    @Test
    void nullIsRejectedByParse() {
        assertNull(GuildChatLine.parse(null));
    }

    @Test
    void emptyInputIsRejectedByBothButByDifferentRoutes() {
        // parse short-circuits on isEmpty(); parseServerLine gets colonIndex == -1 and fails the
        // <= 0 check.
        assertNull(GuildChatLine.parse(""));
        assertNull(GuildChatLine.parseServerLine(""));
    }

    // ----- Divergences 1 and 2: what comes back -----

    @Test
    void parseServerLineReturnsAnIndexAndKeepsTheRankIndicator() {
        String line = PUA + " Metrafish: hello";

        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals(PUA, server.rankIndicator(), "everything up to and including the last glyph");
        assertEquals(
                "hello",
                line.substring(server.bodyCharStart()),
                "the body is an offset the caller slices, not a string it is handed");

        GuildChatLine.Parsed shared = GuildChatLine.parse(line);
        assertNotNull(shared);
        assertEquals("hello", shared.message(), "parse hands back the substring");
    }

    // ----- Divergence 3: the cell a merge would flatten -----

    @Test
    void bodyOffsetDivergesWhenTheCharacterAfterTheColonIsNotASpace() {
        // parse trim()s the body, which removes tabs and newlines; parseServerLine advances
        // only past literal ' ', so it stops on the first tab or newline and the caller
        // renders it.
        String line = PUA + " Metrafish: \n hello";

        GuildChatLine.Parsed shared = GuildChatLine.parse(line);
        assertNotNull(shared);
        assertEquals("hello", shared.message(), "trim() removes the leading newline");

        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals(
                "\n hello",
                line.substring(server.bodyCharStart()),
                "the space-only skip stops at the newline and keeps it in the body");
    }

    @Test
    void bodyOffsetAlsoDivergesOnATab() {
        String line = PUA + " Metrafish:\thello";

        GuildChatLine.Parsed shared = GuildChatLine.parse(line);
        assertNotNull(shared);
        assertEquals("hello", shared.message());

        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals("\thello", line.substring(server.bodyCharStart()));
    }

    @Test
    void parseAlsoTrimsTheTrailingEndOfTheBodyAndAnIndexCannot() {
        // trim() is two-sided. An index has no way to express that, so the
        // trailing whitespace survives in parseServerLine's caller.
        String line = PUA + " Metrafish: hello   ";

        GuildChatLine.Parsed shared = GuildChatLine.parse(line);
        assertNotNull(shared);
        assertEquals("hello", shared.message());

        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals("hello   ", line.substring(server.bodyCharStart()));
    }

    @Test
    void anEmptyBodyIsAnEmptyStringNotNull() {
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + " Metrafish:   ");
        assertNotNull(shared);
        assertEquals("", shared.message());

        String line = PUA + " Metrafish:   ";
        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals(
                line.length(),
                server.bodyCharStart(),
                "the space skip runs off the end rather than overrunning it");
    }
}
