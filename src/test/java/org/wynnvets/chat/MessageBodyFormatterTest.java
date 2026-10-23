package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link MessageBodyFormatter}: goldens for {@code formatMessageBody}, the wrap-structure
 * test, and {@code stripServerContinuations} called directly, with a property test over every
 * short string built from marker pieces.
 *
 * <p>Each golden is the body's {@code Component.toString()}, which spells out every segment's text,
 * colour, font, shadow and click event. The string form is Minecraft 1.21.11's, so a Minecraft
 * update that changes {@code Component.toString()} will need the goldens regenerated.</p>
 *
 * <p>Every body given to {@code formatMessageBody} keeps each run of plain text empty or a URL
 * once the banned hard set is stripped.
 * Any other text goes to {@code SpoilerFormatter.appendWithSpoilers}, which reads
 * {@code VetsConfig}, and {@code VetsConfig} cannot initialise in a test. So no body here reaches
 * the spoiler rendering or has text beside a URL, and no body keeps a newline or gains the space
 * that joins two wrapped lines. Those two arms of {@code stripServerContinuations} are pinned by
 * calling it directly, which is package-private for that; the bytecode proof and the in-game pass
 * cover the rest.</p>
 */
class MessageBodyFormatterTest {

    private static final String FULL = "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
    private static final String COMPACT = "\uDAFF\uDFFC\uE001\uDB00\uDC06";
    private static final String SEP = "\uE003";
    private static final Style AQUA = Style.EMPTY.withColor(ChatFormatting.AQUA);

    private static String format(String body, Style style) {
        return MessageBodyFormatter.formatMessageBody(body, style).toString();
    }

    private static String strip(String text) {
        return MessageBodyFormatter.stripServerContinuations(text);
    }

    // ── formatMessageBody ─────────────────────────────────────────────

    @Test
    void formatMessageBody_nullOrEmpty_isEmpty() {
        assertEquals("empty", format(null, AQUA));
        assertEquals("empty", format("", AQUA));
    }

    @Test
    void formatMessageBody_dropsTheHardSetFirst() {
        // The hard set goes before anything else, so a body made only of it is empty, and one
        // beside a marker formats as the marker alone.
        assertEquals("empty", format("\u00C1\uE07E\uE080\uE086", AQUA));
        assertEquals(format(SEP, AQUA), format(SEP + "\u00C1", AQUA));
        // A hard char between a newline and a continuation marker goes first, so the
        // continuation still matches and the newline goes with it.
        assertEquals("empty", format("\n\u00C1" + COMPACT, AQUA));
    }

    @Test
    void formatMessageBody_inlineMarker_becomesTheSeparatorInTheTextColour() {
        String separator =
                "empty[siblings=[literal{\uE003}[style={color=aqua,shadowColor=0,"
                        + "font=Resource[id=minecraft:chat/prefix]}]]]";
        assertEquals(separator, format(SEP, AQUA));
        assertEquals(separator, format(FULL, AQUA));
        assertEquals(separator, format(COMPACT, AQUA));
    }

    @Test
    void formatMessageBody_inlineMarker_withNoTextColour_keepsThePlainPrefixStyle() {
        assertEquals(
                "empty[siblings=[literal{\uE003}[style={shadowColor=0,"
                        + "font=Resource[id=minecraft:chat/prefix]}]]]",
                format(SEP, Style.EMPTY));
    }

    @Test
    void formatMessageBody_url_isLinked() {
        assertEquals(
                "empty[siblings=[literal{https://wynnvets.org/a}[style={color=aqua,"
                        + "clickEvent=OpenUrl[uri=https://wynnvets.org/a]}]]]",
                format("https://wynnvets.org/a", AQUA));
    }

    @Test
    void formatMessageBody_bareHost_isShownAsTypedAndLinkedOverHttps() {
        assertEquals(
                "empty[siblings=[literal{wynnvets.org/a}[style={color=aqua,"
                        + "clickEvent=OpenUrl[uri=https://wynnvets.org/a]}]]]",
                format("wynnvets.org/a", AQUA));
    }

    @Test
    void formatMessageBody_upperCaseScheme_isLinkedAsTyped() {
        assertEquals(
                "empty[siblings=[literal{HTTPS://WYNNVETS.ORG/A}[style={color=aqua,"
                        + "clickEvent=OpenUrl[uri=HTTPS://WYNNVETS.ORG/A]}]]]",
                format("HTTPS://WYNNVETS.ORG/A", AQUA));
    }

    @Test
    void formatMessageBody_urlThatIsNotAValidUri_staysUnlinked() {
        assertEquals(
                "empty[siblings=[literal{https://wynnvets.org/%zz}[style={color=aqua}]]]",
                format("https://wynnvets.org/%zz", AQUA));
    }

    @Test
    void formatMessageBody_markerBetweenUrls_splitsThem() {
        assertEquals(
                "empty[siblings=[literal{https://wynnvets.org/}[style={color=aqua,"
                        + "clickEvent=OpenUrl[uri=https://wynnvets.org/]}], "
                        + "literal{\uE003}[style={color=aqua,shadowColor=0,"
                        + "font=Resource[id=minecraft:chat/prefix]}], "
                        + "literal{https://wynnvets.org/b/}[style={color=aqua,"
                        + "clickEvent=OpenUrl[uri=https://wynnvets.org/b/]}]]]",
                format("https://wynnvets.org/" + SEP + "https://wynnvets.org/b/", AQUA));
    }

    @Test
    void formatMessageBody_leadingContinuations_areStripped() {
        assertEquals(
                "empty[siblings=[literal{https://wynnvets.org/}[style={color=aqua,"
                        + "clickEvent=OpenUrl[uri=https://wynnvets.org/]}]]]",
                format("\n" + COMPACT + " " + COMPACT + " https://wynnvets.org/", AQUA));
    }

    // ── isWrapStructure ───────────────────────────────────────────────

    @Test
    void isWrapStructure_newlineOrWholeMarker_isTrue() {
        assertTrue(MessageBodyFormatter.isWrapStructure("\n"));
        assertTrue(MessageBodyFormatter.isWrapStructure(FULL));
        assertTrue(MessageBodyFormatter.isWrapStructure(COMPACT));
        assertTrue(MessageBodyFormatter.isWrapStructure(SEP));
    }

    @Test
    void isWrapStructure_anythingElse_isFalse() {
        for (String text :
                new String[] {"", "x", " ", "\n\n", FULL + " ", SEP + SEP, "\uDAFF\uDFFC"}) {
            assertFalse(MessageBodyFormatter.isWrapStructure(text), escaped(text));
        }
    }

    // ── stripServerContinuations ──────────────────────────────────────

    @Test
    void stripServerContinuations_withNoContinuation_isUnchanged() {
        assertEquals("one two", strip("one two"));
        assertEquals("one " + SEP + " two", strip("one " + SEP + " two"));
    }

    @Test
    void stripServerContinuations_continuation_joinsTheLinesWithOneSpace() {
        assertEquals("one two", strip("one\n" + COMPACT + " two"));
        assertEquals("one two", strip("one\n" + COMPACT + "two"));
    }

    @Test
    void stripServerContinuations_continuationAtEitherEnd_addsNoSpace() {
        assertEquals("two", strip("\n" + COMPACT + " two"));
        assertEquals("one", strip("one\n" + COMPACT));
    }

    @Test
    void stripServerContinuations_newlineWithoutAMarker_isKept() {
        assertEquals("one\ntwo", strip("one\ntwo"));
        assertEquals("one\n two", strip("one\n\n" + COMPACT + " two"));
    }

    @Test
    void stripServerContinuations_markersRightAfterAContinuation_goWithIt() {
        assertEquals("one two", strip("one\n" + COMPACT + " " + COMPACT + " two"));
        assertEquals("one two", strip("one\n" + COMPACT + COMPACT + "two"));
    }

    @Test
    void stripServerContinuations_separatorOrFullMarkerAfterANewline_isAContinuationToo() {
        assertEquals("one two", strip("one\n" + SEP + " two"));
        assertEquals("one two", strip("one\n" + FULL + " two"));
    }

    @Test
    void stripServerContinuations_markerAfterTextOnAJoinedLine_isLeft() {
        assertEquals(
                "one two " + SEP + " three", strip("one\n" + COMPACT + " two " + SEP + " three"));
    }

    /**
     * Every string of up to six pieces, each a newline, a space, a letter, a whole marker or a part
     * of one, so that markers can also assemble across pieces: no newline in the output is followed
     * by a whole marker.
     */
    @Test
    void stripServerContinuations_neverLeavesANewlineBeforeAMarker() {
        String[] pieces = {
            "\n",
            " ",
            "x",
            FULL,
            COMPACT,
            SEP,
            "\uDAFF\uDFFC",
            "\uE001",
            "\uDB00\uDC06",
            "\uE006",
            "\uDAFF",
            "\uDAFF\uDFFF\uE002\uDAFF\uDFFE"
        };
        String[] markers = {FULL, COMPACT, SEP};
        int[] at = new int[6];
        StringBuilder in = new StringBuilder();
        long strings = 0;
        for (int length = 0; length <= 6; length++) {
            Arrays.fill(at, 0);
            while (true) {
                in.setLength(0);
                for (int i = 0; i < length; i++) {
                    in.append(pieces[at[i]]);
                }
                String out = strip(in.toString());
                strings++;
                for (int nl = out.indexOf('\n'); nl >= 0; nl = out.indexOf('\n', nl + 1)) {
                    for (String marker : markers) {
                        if (out.startsWith(marker, nl + 1)) {
                            fail(escaped(in.toString()) + " -> " + escaped(out));
                        }
                    }
                }
                int p = length - 1;
                while (p >= 0 && ++at[p] == pieces.length) {
                    at[p] = 0;
                    p--;
                }
                if (p < 0) {
                    break;
                }
            }
        }
        assertEquals(3_257_437, strings);
    }

    private static String escaped(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04X", (int) c));
        }
        return b.toString();
    }
}
