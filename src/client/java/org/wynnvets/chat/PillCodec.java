package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Encoder/decoder for the Private Use Area "pill" sequences that carry rank
 * badges in Wynncraft guild chat and in vetsmod's own chat output.
 *
 * <p>A pill is a rounded badge with a label inside it. There is no image and
 * no colour information on the wire: the whole thing is spelled out as
 * invisible PUA codepoints that Wynncraft's resource pack renders as glyph
 * art, and the colour comes from the {@link Style} applied to those
 * codepoints by the sender.</p>
 *
 * <h2>Codepoint meaning is frame-scoped</h2>
 *
 * <p>There is one glyph page in Wynncraft's resource pack, and a codepoint in
 * it means nothing on its own — only its position inside a framed sequence
 * gives it a meaning. U+E003 is the private-message separator, U+E006 and
 * U+E002 are guild badge glyphs, and U+E010/U+E011 are frame pieces, yet all
 * of them sit inside the same U+E000 block that spells lowercase letters
 * inside a rank pill. Decode from the frame inward; never map a bare
 * codepoint to a character.</p>
 *
 * <p>Within a frame, three letter blocks are in use:</p>
 *
 * <table border="1">
 *   <caption>Letter blocks</caption>
 *   <tr><th>Block</th><th>Range</th><th>Meaning</th></tr>
 *   <tr><td>{@link #LOWER_ALPHABET_BASE}</td><td>U+E000–U+E019</td><td>{@code a}–{@code z}</td></tr>
 *   <tr><td>{@link #DIGIT_BASE}</td><td>U+E030–U+E039</td><td>{@code 0}–{@code 9}</td></tr>
 *   <tr><td>{@link #UPPER_ALPHABET_BASE}</td><td>U+E040–U+E059</td><td>{@code A}–{@code Z}</td></tr>
 * </table>
 *
 * <p>All three belong to Wynncraft, not to us — vetsmod borrows the uppercase
 * block for its own pills, and the server uses all three in different
 * sequences. Case is a per-sequence convention, not a way to tell who built
 * something.</p>
 *
 * <h2>The sequences</h2>
 *
 * <p><b>1. Server rank pill</b> — decode only; we never build these.
 * {@link #decodeServerPill(String)}.</p>
 * <pre>
 *   U+E062                  opener
 *   U+CFFxx                 width marker; 0xD0000 - marker is the label's
 *                           rendered width in px, so the background art can
 *                           be sized to the text
 *   U+E000 + (c - 'a') ...  the label, lowercase in every sample observed
 *   U+D0002                 terminator
 * </pre>
 *
 * <p><b>2. Server id run</b> — not a pill, but it sits immediately before one
 * in guild chat and is the reason uppercase glyphs show up in server traffic:
 * an opener followed by hex digits, each preceded by a 1px negative-space
 * marker (U+CFFFF) that kerns the run tight.</p>
 * <pre>
 *   U+E060                              opener
 *   (U+CFFFF, one hex digit glyph) ...  9-10 chars, drawn from the digit
 *                                       and uppercase blocks ('0'-'9','A'-'F')
 * </pre>
 * <p>Decoding the rank pill anchors on its own U+E062 opener, so this run is
 * skipped rather than mistaken for a label.</p>
 *
 * <p><b>3. Remote pill (light on dark)</b> — {@link #encodeRemote(String)}.
 * A light label on a dark rounded field. Used for anything that reached the
 * player over vetsmod's WebSocket rather than through Wynncraft's own guild
 * channel: bridge, honourary, and queue messages. Returns a plain
 * {@code String} because the entire sequence is one colour, which lets
 * {@link PillFormatter} decide that colour per message (flat, or the
 * supporter gradient marker).</p>
 * <pre>
 *   U+E06B                  frame open
 *   U+E040 + (c - 'A') ...  the label, uppercase
 *   U+E06C                  frame close
 * </pre>
 *
 * <p><b>4. Local pill (dark on light)</b> — {@link #encodeLocal(String, Style)}.
 * A dark label on a light rounded field, matching Wynncraft's own guild pill
 * and the {@code [Vetsmod]} pill in {@code /wv help}. Used when rewriting
 * chat that genuinely arrived through Wynncraft's guild channel, so the
 * rewrite doesn't visually announce itself as mod output. Returns a
 * {@code Component} rather than a {@code String} because it is inherently
 * two-tone — the frame takes the caller's colour and the letters are always
 * black — so it cannot be expressed as a single styled run.</p>
 * <pre>
 *   U+E010 U+2064                        frame open
 *   (U+E00F U+E012, U+E040 + (c-'A'))... one frame segment per letter,
 *                                        each segment widening the field by
 *                                        one character before the letter is
 *                                        drawn over it
 *   U+E011                               frame close
 * </pre>
 * <p>Note U+E00F and U+E012 are frame pieces here while being {@code p} and
 * {@code s} inside a server rank pill — the frame-scoping point above, in
 * practice.</p>
 *
 * <h2>Fonts</h2>
 *
 * <p>Both vetsmod styles render in the <em>default</em> font and rely on
 * Wynncraft's resource pack supplying the PUA glyphs — no font override is
 * applied anywhere on the pill path. The named {@code chat/prefix} font
 * ({@link ChatUtils#CHAT_PREFIX_STYLE}) is used for badges and continuation
 * markers, not for pills, and the server's own pill uses {@code banner/pill}.
 * Several older docstrings loosely attribute vetsmod pills to
 * {@code chat/prefix}; that is not what the code does.</p>
 *
 * @see ChatLogger#rankMap() the fast-path lookup table of known server pills
 * @see PillFormatter which colours the encoded output
 */
public final class PillCodec {

    // ── Letter blocks ─────────────────────────────────────────────────

    /** Base of the lowercase block: {@code 'a'} is U+E000. */
    public static final char LOWER_ALPHABET_BASE = '\uE000';

    /** Base of the digit block: {@code '0'} is U+E030. */
    public static final char DIGIT_BASE = '\uE030';

    /** Base of the uppercase block: {@code 'A'} is U+E040. */
    public static final char UPPER_ALPHABET_BASE = '\uE040';

    // ── Server rank pill (decode only) ────────────────────────────────

    private static final char SERVER_PILL_OPEN = '\uE062';
    private static final int SERVER_PILL_CLOSE = 0xD0002;

    /** Width marker is encoded as {@code WIDTH_BASE - pixels}. */
    private static final int SERVER_WIDTH_BASE = 0xD0000;

    /** Lower bound for a plausible width marker (a ~255px label). Used to
     *  tell the marker apart from a stray supplementary codepoint. */
    private static final int SERVER_WIDTH_MIN = 0xCFF00;

    // ── Remote style: light on dark ───────────────────────────────────

    private static final char REMOTE_FRAME_OPEN = '\uE06B';
    private static final char REMOTE_FRAME_CLOSE = '\uE06C';

    // ── Local style: dark on light ────────────────────────────────────

    private static final String LOCAL_FRAME_OPEN = "\uE010\u2064";
    private static final String LOCAL_FRAME_SEGMENT = "\uE00F\uE012";
    private static final String LOCAL_FRAME_CLOSE = "\uE011";

    private PillCodec() {}

    // ── Encoding ──────────────────────────────────────────────────────

    /**
     * Encodes {@code label} as a light-on-dark pill (see class docs, style 3).
     *
     * <p>Input that already contains PUA codepoints is returned unchanged, so
     * this is safe to call on a rank that arrived pre-encoded — waitlist and
     * honourary self-messages carry an already-built pill.</p>
     *
     * <p>Characters outside {@code A-Z} (after upper-casing) are dropped:
     * this style's frame has no digit handling and emitting the raw character
     * would render as visible ASCII sitting inside the badge art.</p>
     *
     * @param label the ASCII label, case-insensitive
     * @return the PUA sequence, or {@code label} unchanged if already encoded
     */
    public static String encodeRemote(String label) {
        if (label == null) {
            return "";
        }
        if (isEncoded(label)) {
            return label;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(REMOTE_FRAME_OPEN);
        for (char c : label.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                sb.append((char) (UPPER_ALPHABET_BASE + (c - 'A')));
            }
        }
        sb.append(REMOTE_FRAME_CLOSE);
        return sb.toString();
    }

    /**
     * Encodes {@code label} as a dark-on-light pill (see class docs, style 4).
     *
     * <p>Unlike {@link #encodeRemote(String)} this cannot pass pre-encoded
     * input through, because the frame and the letters need different styles
     * and a bare PUA string carries no seam between them.</p>
     *
     * @param label      the ASCII label, case-insensitive; non-{@code A-Z} dropped
     * @param frameStyle style for the frame glyphs; letters are always black
     * @return a two-tone pill component
     */
    public static MutableComponent encodeLocal(String label, Style frameStyle) {
        Style letterStyle = Style.EMPTY.withColor(ChatFormatting.BLACK).withoutShadow();
        MutableComponent component = Component.empty();
        component.append(Component.literal(LOCAL_FRAME_OPEN).setStyle(frameStyle));

        String upper = label == null ? "" : label.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            char letter = upper.charAt(i);
            if (letter < 'A' || letter > 'Z') {
                continue;
            }
            component.append(Component.literal(LOCAL_FRAME_SEGMENT).setStyle(frameStyle));
            component.append(
                    Component.literal(String.valueOf((char) (UPPER_ALPHABET_BASE + (letter - 'A'))))
                            .setStyle(letterStyle));
        }

        component.append(Component.literal(LOCAL_FRAME_CLOSE).setStyle(frameStyle));
        return component;
    }

    // ── Decoding ──────────────────────────────────────────────────────

    /**
     * Decodes the first Wynncraft server rank pill found in {@code text} and
     * returns its label in lowercase ({@code "strategist"}, {@code "owner"},
     * …), or {@code null} if there is no well-formed pill.
     *
     * <p>Scans for the opener rather than anchoring at position 0. Guild chat
     * puts both the guild badge prepend and a hex id run (class docs, style 2)
     * ahead of the pill, and glyphs from all three letter blocks appear in
     * those — anchoring on U+E062 is what keeps them out of the label.</p>
     *
     * <p>Every rank pill observed in captured chat spells its label in
     * lowercase. The uppercase and digit blocks are accepted anyway and folded
     * to lowercase: it costs nothing, and the alternative failure mode is
     * silently returning {@code null} for a rank Wynncraft chose to spell
     * differently.</p>
     *
     * <p>This understands any pill the server emits, including ranks that
     * predate or postdate our lookup table — which is the point of having it
     * alongside {@link ChatLogger#rankMap()}.</p>
     *
     * @param text a chat fragment that may contain a pill
     * @return the lowercase rank label, or {@code null}
     */
    public static String decodeServerPill(String text) {
        if (text == null) {
            return null;
        }
        int i = text.indexOf(SERVER_PILL_OPEN);
        if (i < 0) {
            return null;
        }
        i++; // step past the opener (a BMP char)

        StringBuilder label = new StringBuilder();
        boolean sawWidthMarker = false;

        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == SERVER_PILL_CLOSE) {
                return label.length() > 0 ? label.toString() : null;
            }
            if (!sawWidthMarker && cp >= SERVER_WIDTH_MIN && cp < SERVER_WIDTH_BASE) {
                sawWidthMarker = true;
                continue;
            }

            int lower = cp - LOWER_ALPHABET_BASE;
            if (lower >= 0 && lower < 26) {
                label.append((char) ('a' + lower));
                continue;
            }
            int upper = cp - UPPER_ALPHABET_BASE;
            if (upper >= 0 && upper < 26) {
                label.append((char) ('a' + upper)); // folded to lowercase
                continue;
            }
            int digit = cp - DIGIT_BASE;
            if (digit >= 0 && digit < 10) {
                label.append((char) ('0' + digit));
                continue;
            }
            return null; // glyph we don't recognise — not a pill we can trust
        }
        return null; // ran off the end without a terminator
    }

    // ── Predicates ────────────────────────────────────────────────────

    /**
     * Whether {@code text} already contains PUA codepoints, i.e. has been
     * encoded as a pill (by either side) and must not be encoded again.
     */
    public static boolean isEncoded(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.getType(cp) == Character.PRIVATE_USE) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
