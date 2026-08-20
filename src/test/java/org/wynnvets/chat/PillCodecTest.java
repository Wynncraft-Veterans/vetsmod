package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PillCodec}'s five public entry points.
 *
 * <p>NOTE: {@code encodeLocal} builds real {@code Component} and {@code Style}
 * objects, which the harness supports — the test source set carries the client
 * compile classpath. {@link PillCodec}'s own static state is twelve {@code char},
 * {@code int} and {@code String} constants, so nothing loads during class-init. If a future
 * contributor adds a static initializer that touches Minecraft's registries or
 * Wynntils, this test starts failing at class-init time, and the fix is to
 * extract the pure half rather than to drop the coverage.</p>
 *
 * <p>Every codepoint below is written numerically. They are a wire format
 * shared with Wynncraft's resource pack, they are invisible in an editor, and
 * a test that read them back from the subject's own private fields would pass
 * through any renumbering.</p>
 */
class PillCodecTest {

    // ----- Wire constants, restated -----

    private static final int LOWER_ALPHABET_BASE = 0xE000;
    private static final int DIGIT_BASE = 0xE030;
    private static final int UPPER_ALPHABET_BASE = 0xE040;

    private static final char SERVER_PILL_OPEN = (char) 0xE062;
    private static final int SERVER_PILL_CLOSE = 0xD0002;
    private static final int SERVER_WIDTH_BASE = 0xD0000;
    private static final int SERVER_WIDTH_MIN = 0xCFF00;

    /** Opener of the hex id run that precedes a pill in guild chat. */
    private static final char SERVER_ID_RUN_OPEN = (char) 0xE060;

    /** The 1px negative-space marker that kerns the id run tight. */
    private static final int SERVER_KERN_MARKER = 0xCFFFF;

    private static final char REMOTE_FRAME_OPEN = (char) 0xE06B;
    private static final char REMOTE_FRAME_CLOSE = (char) 0xE06C;

    private static final String LOCAL_FRAME_OPEN = chars(0xE010, 0x2064);
    private static final String LOCAL_FRAME_SEGMENT = chars(0xE00F, 0xE012);
    private static final String LOCAL_FRAME_CLOSE = chars(0xE011);

    private static final Style FRAME_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /** Captured at class-init, before any test can have changed it. */
    private static final Locale ORIGINAL_DEFAULT = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(ORIGINAL_DEFAULT);
    }

    // ----- Fixture builders -----

    private static String chars(int... codepoints) {
        StringBuilder sb = new StringBuilder();
        for (int cp : codepoints) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /** Spell {@code label} in the lowercase block (U+E000 + c - 'a'). */
    private static String lowerGlyphs(String label) {
        StringBuilder sb = new StringBuilder();
        for (char c : label.toCharArray()) {
            sb.append((char) (LOWER_ALPHABET_BASE + (c - 'a')));
        }
        return sb.toString();
    }

    /** Spell {@code label} in the uppercase block (U+E040 + c - 'A'). */
    private static String upperGlyphs(String label) {
        StringBuilder sb = new StringBuilder();
        for (char c : label.toCharArray()) {
            sb.append((char) (UPPER_ALPHABET_BASE + (c - 'A')));
        }
        return sb.toString();
    }

    /** A well-formed server rank pill: opener, width marker, label, terminator. */
    private static String serverPill(String glyphs, int widthPx) {
        return SERVER_PILL_OPEN
                + chars(SERVER_WIDTH_BASE - widthPx)
                + glyphs
                + chars(SERVER_PILL_CLOSE);
    }

    /** A server pill whose only variable is the codepoint in the marker slot. */
    private static String pillWithMarker(int marker) {
        return SERVER_PILL_OPEN + chars(marker) + lowerGlyphs("owner") + chars(SERVER_PILL_CLOSE);
    }

    private static String emptyRemotePill() {
        return "" + REMOTE_FRAME_OPEN + REMOTE_FRAME_CLOSE;
    }

    // ----- isCustomGlyph -----

    @Test
    void isCustomGlyph_acceptsPrivateUseInEveryPlane() {
        assertTrue(PillCodec.isCustomGlyph(LOWER_ALPHABET_BASE), "BMP PUA, the pill letters");
        assertTrue(PillCodec.isCustomGlyph(0xF8FF), "the top of the BMP PUA block");
        assertTrue(PillCodec.isCustomGlyph(0xF0000), "Supplementary PUA-A");
        assertTrue(PillCodec.isCustomGlyph(0x100000), "Supplementary PUA-B");
    }

    @Test
    void isCustomGlyph_acceptsSupplementaryUnassignedButNotBmpUnassigned() {
        // The load-bearing half. Wynncraft's width marker, kern marker and pill
        // terminator are all unassigned codepoints above the BMP, so they must
        // count; unassigned codepoints inside the BMP turn up in ordinary text,
        // so they must not. Drop the bound and a plain chat line carrying
        // U+0378 acquires a rank indicator it never had — which is exactly what
        // GuildChatLineTest pins from the other side.
        assertTrue(PillCodec.isCustomGlyph(SERVER_PILL_CLOSE), "U+D0002, the terminator");
        assertTrue(PillCodec.isCustomGlyph(SERVER_WIDTH_MIN), "U+CFF00, a width marker");
        assertTrue(PillCodec.isCustomGlyph(SERVER_KERN_MARKER), "U+CFFFF, the kern marker");

        assertFalse(PillCodec.isCustomGlyph(0x0378), "unassigned, but inside the BMP");
        assertFalse(PillCodec.isCustomGlyph(0x0379), "the codepoint next to it, likewise");
    }

    @Test
    void isCustomGlyph_rejectsOrdinaryText() {
        assertFalse(PillCodec.isCustomGlyph('a'));
        assertFalse(PillCodec.isCustomGlyph('Z'));
        assertFalse(PillCodec.isCustomGlyph('7'));
        assertFalse(PillCodec.isCustomGlyph(' '), "space is SPACE_SEPARATOR, not unassigned");
        assertFalse(PillCodec.isCustomGlyph(0x2064), "U+2064 is FORMAT — the local frame uses it");
        assertFalse(PillCodec.isCustomGlyph(0x1F600), "an assigned supplementary codepoint");
    }

    @Test
    void isCustomGlyph_andIsEncodedDisagreeOnTheServerMarkers() {
        // Stated as a pair because the two predicates sit in the same class and
        // read as the same question. isEncoded gates encodeRemote's passthrough
        // and must stay PRIVATE_USE-only; widening it to match this one makes
        // encodeRemote hand a raw label back.
        for (int marker : new int[] {SERVER_PILL_CLOSE, SERVER_WIDTH_MIN, SERVER_KERN_MARKER}) {
            assertTrue(PillCodec.isCustomGlyph(marker));
            assertFalse(PillCodec.isEncoded(chars(marker)));
        }
    }

    // ----- isEncoded -----

    @Test
    void isEncoded_nullEmptyAndPlainAscii() {
        assertFalse(PillCodec.isEncoded(null));
        assertFalse(PillCodec.isEncoded(""));
        assertFalse(PillCodec.isEncoded("VET"));
        assertFalse(PillCodec.isEncoded("[Vetsmod]"));
    }

    @Test
    void isEncoded_anyBmpPuaCodepointCounts() {
        assertTrue(PillCodec.isEncoded(chars(LOWER_ALPHABET_BASE)), "the lowercase block");
        assertTrue(PillCodec.isEncoded(chars(UPPER_ALPHABET_BASE)), "the uppercase block");
        assertTrue(PillCodec.isEncoded("x" + REMOTE_FRAME_OPEN + "y"), "anywhere in the string");
        assertTrue(PillCodec.isEncoded(chars(0xF8FF)), "the top of the BMP PUA block");
    }

    @Test
    void isEncoded_supplementaryPuaPlanesAlsoCount() {
        // The scan is codepoint-wise, not char-wise, so plane 15/16 PUA is
        // recognised and its surrogates are never inspected individually.
        assertTrue(PillCodec.isEncoded(chars(0xF0000)), "Supplementary PUA-A");
        assertTrue(PillCodec.isEncoded(chars(0x100000)), "Supplementary PUA-B");
    }

    @Test
    void isEncoded_ignoresTheServerPillMarkersBecauseTheyAreUnassignedNotPua() {
        // Load-bearing. The width marker (U+CFF00-U+CFFFF) and the terminator
        // (U+D0002) live in unassigned planes 12 and 13, so Character.getType
        // returns UNASSIGNED, not PRIVATE_USE, and this predicate says "not
        // encoded". The repo's other PUA predicates pair PRIVATE_USE with an
        // UNASSIGNED clause; this one deliberately does not. Adding that clause
        // here makes encodeRemote treat a label carrying either marker as
        // already encoded and hand it back raw.
        assertFalse(PillCodec.isEncoded(chars(SERVER_PILL_CLOSE)), "terminator is UNASSIGNED");
        assertFalse(PillCodec.isEncoded(chars(SERVER_WIDTH_MIN)), "width marker is UNASSIGNED");
        assertFalse(PillCodec.isEncoded(chars(SERVER_KERN_MARKER)), "kern marker is UNASSIGNED");
    }

    // ----- encodeRemote -----

    @Test
    void encodeRemote_nullYieldsEmptyStringNotNull() {
        assertEquals("", PillCodec.encodeRemote(null));
    }

    @Test
    void encodeRemote_framesTheUppercaseGlyphs() {
        assertEquals(
                REMOTE_FRAME_OPEN + upperGlyphs("VET") + REMOTE_FRAME_CLOSE,
                PillCodec.encodeRemote("VET"));
    }

    @Test
    void encodeRemote_upperCasesTheLabel() {
        // Fixtures deliberately contain no 'i' — see the locale case below, which
        // is why this is not the general property it looks like.
        assertEquals(PillCodec.encodeRemote("VET"), PillCodec.encodeRemote("vet"));
        assertEquals(PillCodec.encodeRemote("VET"), PillCodec.encodeRemote("VeT"));
    }

    @Test
    void encodeRemote_dropsAnIUnderATurkishDefaultLocale() {
        // KNOWN BUG (pinned, not fixed): encodeRemote and encodeLocal fold with
        // the no-argument toUpperCase(). Under a Turkish default, 'i' becomes
        // the dotted capital U+0130, which is outside A-Z, so the letter is
        // silently dropped rather than rejected — a "vip" pill renders as "VP".
        // See {@code
        // .claude/ephemeral/bugs-found-via-mellow-rain/default-locale-case-folding-cluster.md}.
        // Fixing it means passing Locale.ROOT; this assertion flips then.
        Locale.setDefault(TURKISH);

        assertEquals(
                REMOTE_FRAME_OPEN + upperGlyphs("VP") + REMOTE_FRAME_CLOSE,
                PillCodec.encodeRemote("vip"),
                "the i folds to U+0130, fails the A-Z test and is dropped");
        assertEquals(
                REMOTE_FRAME_OPEN + upperGlyphs("VIP") + REMOTE_FRAME_CLOSE,
                PillCodec.encodeRemote("VIP"),
                "an already-uppercase label is untouched by the fold and survives");
    }

    @Test
    void encodeLocal_dropsAnIUnderATurkishDefaultLocaleToo() {
        // Same defect, same cluster, second site.
        Locale.setDefault(TURKISH);

        assertEquals(
                LOCAL_FRAME_OPEN
                        + LOCAL_FRAME_SEGMENT
                        + upperGlyphs("V")
                        + LOCAL_FRAME_SEGMENT
                        + upperGlyphs("P")
                        + LOCAL_FRAME_CLOSE,
                PillCodec.encodeLocal("vip", FRAME_STYLE).getString());
    }

    @Test
    void encodeRemote_dropsEverythingOutsideAtoZ() {
        // This style's frame has no digit art, so emitting the raw character
        // would draw visible ASCII inside the badge.
        assertEquals(
                REMOTE_FRAME_OPEN + upperGlyphs("AB") + REMOTE_FRAME_CLOSE,
                PillCodec.encodeRemote("A1-B "));
        assertEquals(
                emptyRemotePill(), PillCodec.encodeRemote(""), "an empty label still gets a frame");
        assertEquals(
                emptyRemotePill(),
                PillCodec.encodeRemote("123"),
                "a label with no A-Z survives as a bare frame");
    }

    @Test
    void encodeRemote_passesPreEncodedInputThroughUnchanged() {
        // Waitlist and honourary self-messages arrive carrying a built pill.
        String already = REMOTE_FRAME_OPEN + upperGlyphs("VET") + REMOTE_FRAME_CLOSE;
        assertSame(already, PillCodec.encodeRemote(already), "returned by identity, not rebuilt");
    }

    @Test
    void encodeRemote_reEncodesInputCarryingOnlyServerPillMarkers() {
        // The consequence of the isEncoded gap above, stated as behaviour: the
        // markers are not PUA, so the passthrough does not fire, and the
        // rebuild then drops them for not being A-Z. Widening isEncoded would
        // turn this into a passthrough of the raw marker.
        String markers = chars(SERVER_WIDTH_MIN, SERVER_PILL_CLOSE);
        assertEquals(emptyRemotePill(), PillCodec.encodeRemote(markers));
    }

    // ----- encodeLocal -----

    @Test
    void encodeLocal_oneFrameSegmentPerLetter() {
        MutableComponent pill = PillCodec.encodeLocal("AB", FRAME_STYLE);

        assertEquals(
                LOCAL_FRAME_OPEN
                        + LOCAL_FRAME_SEGMENT
                        + upperGlyphs("A")
                        + LOCAL_FRAME_SEGMENT
                        + upperGlyphs("B")
                        + LOCAL_FRAME_CLOSE,
                pill.getString());
        assertEquals(6, pill.getSiblings().size(), "open + (segment, letter) per letter + close");
    }

    @Test
    void encodeLocal_lettersAreBlackAndTheFrameTakesTheCallerStyle() {
        // The two-tone split is why this returns a Component rather than a
        // String: one styled run cannot express it.
        MutableComponent pill = PillCodec.encodeLocal("A", FRAME_STYLE);

        Style frame = pill.getSiblings().get(0).getStyle();
        Style letter = pill.getSiblings().get(2).getStyle();

        assertEquals(
                ChatFormatting.AQUA.getColor().intValue(),
                frame.getColor().getValue(),
                "the frame carries the caller's colour");
        assertEquals(
                ChatFormatting.BLACK.getColor().intValue(),
                letter.getColor().getValue(),
                "letters are always black regardless of the frame colour");
    }

    @Test
    void encodeLocal_nullAndUnrepresentableLabelsYieldABareFrame() {
        assertEquals(
                LOCAL_FRAME_OPEN + LOCAL_FRAME_CLOSE,
                PillCodec.encodeLocal(null, FRAME_STYLE).getString());
        assertEquals(
                LOCAL_FRAME_OPEN + LOCAL_FRAME_CLOSE,
                PillCodec.encodeLocal("12 -", FRAME_STYLE).getString());
    }

    @Test
    void encodeLocal_hasNoPreEncodedPassthrough() {
        // Deliberate asymmetry with encodeRemote: the frame and the letters
        // need different styles and a bare PUA string carries no seam between
        // them, so pre-encoded input is consumed as a label — and since PUA
        // glyphs are not A-Z, every character is dropped.
        String already = REMOTE_FRAME_OPEN + upperGlyphs("VET") + REMOTE_FRAME_CLOSE;
        assertEquals(
                LOCAL_FRAME_OPEN + LOCAL_FRAME_CLOSE,
                PillCodec.encodeLocal(already, FRAME_STYLE).getString());
    }

    // ----- decodeServerPill -----

    @Test
    void decodeServerPill_nullAndNoOpener() {
        assertNull(PillCodec.decodeServerPill(null));
        assertNull(PillCodec.decodeServerPill(""));
        assertNull(PillCodec.decodeServerPill("plain guild chat"));
    }

    @Test
    void decodeServerPill_readsTheLowercaseLabel() {
        assertEquals("owner", PillCodec.decodeServerPill(serverPill(lowerGlyphs("owner"), 40)));
    }

    @Test
    void decodeServerPill_widthMarkerIsOptional() {
        // The marker branch is guarded by the sawWidthMarker latch, but nothing
        // requires the marker to appear at all.
        String noMarker = SERVER_PILL_OPEN + lowerGlyphs("chief") + chars(SERVER_PILL_CLOSE);
        assertEquals("chief", PillCodec.decodeServerPill(noMarker));
    }

    @Test
    void decodeServerPill_foldsUppercaseAndAcceptsDigits() {
        // No captured sample spells a rank in uppercase; the block is accepted
        // anyway so an unexpected spelling degrades to a label rather than null.
        assertEquals("owner", PillCodec.decodeServerPill(serverPill(upperGlyphs("OWNER"), 40)));
        String digits = chars(DIGIT_BASE, DIGIT_BASE + 1, DIGIT_BASE + 2);
        assertEquals("012", PillCodec.decodeServerPill(serverPill(digits, 12)));
    }

    @Test
    void decodeServerPill_scansForTheOpenerPastPrecedingGlyphRuns() {
        // Guild chat puts the badge prepend and a hex id run ahead of the pill,
        // and those draw from all three letter blocks. Anchoring on U+E062
        // rather than position 0 is what keeps them out of the label.
        String idRun =
                SERVER_ID_RUN_OPEN
                        + chars(SERVER_KERN_MARKER)
                        + upperGlyphs("AF")
                        + chars(DIGIT_BASE + 7);
        assertEquals(
                "strategist",
                PillCodec.decodeServerPill(
                        "guild " + idRun + serverPill(lowerGlyphs("strategist"), 55)));
    }

    @Test
    void decodeServerPill_unterminatedOrEmptyLabelIsNull() {
        assertNull(
                PillCodec.decodeServerPill(SERVER_PILL_OPEN + lowerGlyphs("owner")),
                "ran off the end without a terminator");
        assertNull(
                PillCodec.decodeServerPill(serverPill("", 40)),
                "terminator reached with nothing collected");
    }

    @Test
    void decodeServerPill_unrecognisedGlyphAbortsTheWholeDecode() {
        // Not "skip the odd character" — a pill we cannot fully read is not a
        // pill we can trust, so the whole result is discarded.
        String withStray =
                serverPill(lowerGlyphs("own") + REMOTE_FRAME_OPEN + lowerGlyphs("er"), 40);
        assertNull(PillCodec.decodeServerPill(withStray));
    }

    @Test
    void decodeServerPill_widthMarkerIsConsumedAtMostOnce() {
        // The latch means a second marker-range codepoint falls through to the
        // letter checks and aborts.
        String twice =
                SERVER_PILL_OPEN
                        + chars(SERVER_WIDTH_BASE - 40, SERVER_WIDTH_BASE - 41)
                        + lowerGlyphs("owner")
                        + chars(SERVER_PILL_CLOSE);
        assertNull(PillCodec.decodeServerPill(twice));
    }

    @Test
    void decodeServerPill_widthMarkerRangeBoundaries() {
        // The marker test is inclusive at the bottom and exclusive at the top.
        assertEquals(
                "owner",
                PillCodec.decodeServerPill(pillWithMarker(SERVER_WIDTH_MIN)),
                "U+CFF00 is a width marker");
        assertNull(
                PillCodec.decodeServerPill(pillWithMarker(SERVER_WIDTH_MIN - 1)),
                "U+CFEFF is below the marker range");
        assertNull(
                PillCodec.decodeServerPill(pillWithMarker(SERVER_WIDTH_BASE)),
                "U+D0000 itself is not a width marker");
    }

    @Test
    void decodeServerPill_stopsAtTheFirstTerminator() {
        String two = serverPill(lowerGlyphs("owner"), 40) + serverPill(lowerGlyphs("chief"), 40);
        assertEquals("owner", PillCodec.decodeServerPill(two), "only the first pill is read");
    }

    // ----- Cross-checks -----

    @Test
    void encodeRemote_outputIsNotAServerPillAndDoesNotDecode() {
        // The two sequences share the uppercase block but not the frame, so a
        // vetsmod pill must not be mistaken for a server rank.
        assertNull(PillCodec.decodeServerPill(PillCodec.encodeRemote("VET")));
    }

    @Test
    void encodeRemote_recognisesItsOwnOutputAsEncoded() {
        assertTrue(PillCodec.isEncoded(PillCodec.encodeRemote("VET")));
    }
}
