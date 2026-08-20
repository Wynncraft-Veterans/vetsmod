package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GuildChatLine} — one shared header scan, one shared parser,
 * one deliberate variant, and here is what separates them.
 *
 * <p>{@link GuildChatLine#parse} is what two rewriters used to keep a copy of
 * each; {@link GuildChatLine#parseServerLine} is a different function that
 * happened to wear the same name. Everything above the first divergence heading
 * exercises the header scan they share: find the last custom glyph before the
 * first colon, take the trimmed run between it and the colon as the display
 * name. A "custom glyph" is {@link org.wynnvets.chat.PillCodec#isCustomGlyph(int)
 * PillCodec.isCustomGlyph}, and the supplementary bound in it is what these cases
 * exercise from the caller's side —
 * {@link PillCodecTest#isCustomGlyph_acceptsSupplementaryUnassignedButNotBmpUnassigned()}
 * pins the same bound at the predicate itself.</p>
 *
 * <p>Everything below pins the four cells where the two disagree:</p>
 *
 * <ol>
 *   <li>{@code parseServerLine} has no null guard, so it throws where
 *       {@code parse} returns null;</li>
 *   <li>it returns a char <em>index</em> into the original string, not a body
 *       substring;</li>
 *   <li>it keeps the rank indicator, which {@code parse} discards;</li>
 *   <li>it advances past <b>literal spaces only</b> where {@code parse}
 *       {@code trim()}s — so the body offset differs whenever the character after
 *       the colon is a tab or a newline. ({@code trim()} cuts at {@code U+0020},
 *       not at {@code Character.isWhitespace}; neither method removes an EM
 *       SPACE.)</li>
 * </ol>
 *
 * <p>(4) is the one a collapse of the two would flatten without anyone
 * noticing, because it needs a body that starts with whitespace that is not a
 * space. Now that both bodies sit in one file, these four cases and the class's
 * own divergence table are what stop that collapse from looking obvious.</p>
 *
 * <p>NOTE: this class used to be {@code chat.rewriter.GuildChatParseTest} and
 * loaded three rewriters to reach three copies of the parser, two of which
 * import Wynntils. {@link GuildChatLine} has no static state and no Wynntils
 * import, so the class-init fragility that sat behind the parse coverage is
 * gone.</p>
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
        String line = PUA + " Alice: hello there";

        GuildChatLine.Parsed shared = GuildChatLine.parse(line);
        assertNotNull(shared);
        assertEquals("Alice", shared.username());
        assertEquals("hello there", shared.message());

        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals("Alice", server.username());
    }

    @Test
    void theNameMayContainSpaces() {
        // Wynncraft display names can carry a suffix, e.g. "EYAL5555/First Mage".
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + " EYAL5555/First Mage: hi");
        assertNotNull(shared);
        assertEquals("EYAL5555/First Mage", shared.username());
    }

    @Test
    void theScanTakesTheLastGlyphNotTheFirst() {
        // The rank indicator is a run of glyphs; only what follows the final one
        // is the name.
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + "junk" + PUA + " Alice: hi");
        assertNotNull(shared);
        assertEquals("Alice", shared.username());
    }

    @Test
    void supplementaryUnassignedCountsAsAGlyphButBmpUnassignedDoesNot() {
        // The predicate is PRIVATE_USE || (UNASSIGNED && cp > 0xFFFF). The
        // supplementary bound is deliberate: unassigned BMP codepoints turn up
        // in ordinary text, supplementary ones are Wynncraft's markers.
        GuildChatLine.Parsed viaSupplementary =
                GuildChatLine.parse(SUPPLEMENTARY_UNASSIGNED + " Alice: hi");
        assertNotNull(viaSupplementary);
        assertEquals("Alice", viaSupplementary.username());

        assertNull(
                GuildChatLine.parse(BMP_UNASSIGNED + " Alice: hi"),
                "an unassigned BMP codepoint is not a rank glyph");
    }

    @Test
    void aLineWithNoGlyphBeforeTheColonIsRejected() {
        assertNull(GuildChatLine.parse("Alice: hi"));
        assertNull(GuildChatLine.parseServerLine("Alice: hi"));
    }

    @Test
    void aGlyphAfterTheColonDoesNotCount() {
        // The scan stops at the colon, so a glyph in the body is invisible.
        assertNull(GuildChatLine.parse("Alice: " + PUA + " hi"));
    }

    @Test
    void missingOrLeadingColonIsRejected() {
        assertNull(GuildChatLine.parse(PUA + " Alice hi"), "no colon at all");
        assertNull(GuildChatLine.parse(": " + PUA + " Alice"), "colonIndex <= 0");
        assertNull(GuildChatLine.parseServerLine(PUA + " Alice hi"));
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
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + " \t Alice \t : hi");
        assertNotNull(shared);
        assertEquals("Alice", shared.username());

        GuildChatLine.ServerParsed server =
                GuildChatLine.parseServerLine(PUA + " \t Alice \t : hi");
        assertNotNull(server);
        assertEquals("Alice", server.username(), "both trim the name identically");
    }

    // ----- Divergence 1: the null guard -----

    @Test
    void nullIsRejectedByParseAndThrowsInParseServerLine() {
        assertNull(GuildChatLine.parse(null));
        assertThrows(
                NullPointerException.class,
                () -> GuildChatLine.parseServerLine(null),
                "no guard — it goes straight to message.indexOf(':')");
    }

    @Test
    void emptyInputIsRejectedByBothButByDifferentRoutes() {
        // parse short-circuits on isEmpty(); parseServerLine gets
        // colonIndex == -1 and fails the <= 0 check. Same answer, so a collapse
        // onto either body would be safe here — which is exactly why the null
        // case above has to be checked separately.
        assertNull(GuildChatLine.parse(""));
        assertNull(GuildChatLine.parseServerLine(""));
    }

    // ----- Divergences 2 and 3: what comes back -----

    @Test
    void parseServerLineReturnsAnIndexAndKeepsTheRankIndicator() {
        String line = PUA + " Alice: hello";

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

    // ----- Divergence 4: the cell a three-way collapse would flatten -----

    @Test
    void bodyOffsetDivergesWhenTheCharacterAfterTheColonIsNotASpace() {
        // The whole reason this test class exists. parse trim()s the body,
        // which removes tabs and newlines; parseServerLine advances only past
        // literal ' ', so it stops on the first tab or newline and the caller
        // renders it.
        String line = PUA + " Alice: \n hello";

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
        String line = PUA + " Alice:\thello";

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
        String line = PUA + " Alice: hello   ";

        GuildChatLine.Parsed shared = GuildChatLine.parse(line);
        assertNotNull(shared);
        assertEquals("hello", shared.message());

        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals("hello   ", line.substring(server.bodyCharStart()));
    }

    @Test
    void anEmptyBodyIsAnEmptyStringNotNull() {
        GuildChatLine.Parsed shared = GuildChatLine.parse(PUA + " Alice:   ");
        assertNotNull(shared);
        assertEquals("", shared.message());

        String line = PUA + " Alice:   ";
        GuildChatLine.ServerParsed server = GuildChatLine.parseServerLine(line);
        assertNotNull(server);
        assertEquals(
                line.length(),
                server.bodyCharStart(),
                "the space skip runs off the end rather than overrunning it");
    }
}
