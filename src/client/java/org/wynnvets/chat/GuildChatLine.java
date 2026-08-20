package org.wynnvets.chat;

/**
 * Splits a Wynncraft guild chat line into its rank indicator, display name and
 * body.
 *
 * <p>Every guild chat line arrives as {@code <rank pill glyphs> <display name>:
 * <body>}. The rank indicator is a run of custom-font glyphs; the display name
 * is whatever sits between the <em>last</em> such glyph and the first colon,
 * trimmed — it may contain spaces, e.g. {@code "EYAL5555/First Mage"}. That
 * header scan is shared, and {@link #rankIndicatorEnd} is the only copy of
 * it.</p>
 *
 * <h2>Two parsers, four deliberate differences</h2>
 *
 * <p>{@link #parse} serves the rewriters that only need the name and the body
 * text ({@code EncourageUpdateRewriter}, {@code StaffGuildAlertRewriter}).
 * {@link #parseServerLine} serves {@code ServerGuildChatRewriter}, which
 * rebuilds the line from the original {@code Component} tree and therefore needs
 * an offset into the untouched string rather than a cleaned-up copy. The two are
 * <b>not</b> interchangeable, and every cell below is load-bearing:</p>
 *
 * <table border="1">
 *   <caption>Where the two parsers disagree</caption>
 *   <tr><th>Cell</th><th>{@code parse}</th><th>{@code parseServerLine}</th></tr>
 *   <tr>
 *     <td>{@code null} input</td>
 *     <td>returns {@code null}</td>
 *     <td>throws {@code NullPointerException} from its own
 *         {@code message.indexOf(':')} — there is no guard</td>
 *   </tr>
 *   <tr>
 *     <td>the body</td>
 *     <td>a substring, handed back ready to use</td>
 *     <td>a char offset the caller slices, so it still lines up with the
 *         component tree's own character positions</td>
 *   </tr>
 *   <tr>
 *     <td>the rank indicator</td>
 *     <td>discarded</td>
 *     <td>returned — it carries the pill the caller decodes</td>
 *   </tr>
 *   <tr>
 *     <td>whitespace after the colon</td>
 *     <td>{@code trim()}: every kind of whitespace, at both ends</td>
 *     <td>advances past literal {@code ' '} only, and only at the start — so a
 *         body beginning with a tab or a newline keeps it</td>
 *   </tr>
 * </table>
 *
 * <p>The missing null guard reads as an oversight sitting next to a method that
 * has one. It is not: {@code ServerGuildChatRewriter} reaches this only from the
 * mixin, with a string it has already dereferenced. The row above and
 * {@code GuildChatLineTest} are what stop a future "helpful" fix from quietly
 * unifying the two.</p>
 */
public final class GuildChatLine {

    /** A guild chat line reduced to its display name and its body text. */
    public record Parsed(String username, String message) {}

    /**
     * A guild chat line reduced to its rank indicator, display name and the char
     * offset where the body begins in the original string.
     */
    public record ServerParsed(String rankIndicator, String username, int bodyCharStart) {}

    private GuildChatLine() {}

    /**
     * Parses a guild chat line into its display name and trimmed body.
     *
     * @param message the raw chat line; {@code null} and empty both yield {@code null}
     * @return the parsed line, or {@code null} if it is not guild chat
     */
    public static Parsed parse(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        int colonIndex = message.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }

        int lastGlyphEnd = rankIndicatorEnd(message, colonIndex);
        if (lastGlyphEnd < 0) {
            return null;
        }

        String username = message.substring(lastGlyphEnd, colonIndex).trim();
        if (username.isEmpty()) {
            return null;
        }

        String messageContent = message.substring(colonIndex + 1).trim();
        return new Parsed(username, messageContent);
    }

    /**
     * Parses a guild chat line into its rank indicator, display name and body
     * offset, leaving the body itself in the caller's string.
     *
     * <p>Takes no {@code null} guard, by the table in the class docs.</p>
     *
     * @param message the raw chat line
     * @return the parsed line, or {@code null} if it is not guild chat
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public static ServerParsed parseServerLine(String message) {
        int colonIndex = message.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }

        int lastGlyphEnd = rankIndicatorEnd(message, colonIndex);
        if (lastGlyphEnd < 0) {
            return null;
        }

        String username = message.substring(lastGlyphEnd, colonIndex).trim();
        if (username.isEmpty()) {
            return null;
        }

        String rankIndicator = message.substring(0, lastGlyphEnd);
        int bodyStart = colonIndex + 1;
        while (bodyStart < message.length() && message.charAt(bodyStart) == ' ') {
            bodyStart++;
        }
        return new ServerParsed(rankIndicator, username, bodyStart);
    }

    /**
     * Returns the char index just past the last custom-font glyph before
     * {@code colonIndex}, or {@code -1} if there is none or it leaves no room
     * for a display name.
     *
     * <p>A custom glyph is {@code PRIVATE_USE}, or {@code UNASSIGNED}
     * <em>above the BMP</em>. The supplementary bound is deliberate: unassigned
     * BMP codepoints turn up in ordinary text, supplementary ones are
     * Wynncraft's markers. Drop it and an ordinary chat line acquires a rank
     * indicator it never had.</p>
     */
    private static int rankIndicatorEnd(String message, int colonIndex) {
        int lastGlyphEnd = -1;
        int idx = 0;
        while (idx < colonIndex) {
            int cp = message.codePointAt(idx);
            int charCount = Character.charCount(cp);
            int type = Character.getType(cp);
            boolean isCustomGlyph =
                    type == Character.PRIVATE_USE || (type == Character.UNASSIGNED && cp > 0xFFFF);
            if (isCustomGlyph) {
                lastGlyphEnd = idx + charCount;
            }
            idx += charCount;
        }

        return lastGlyphEnd > 0 && lastGlyphEnd < colonIndex ? lastGlyphEnd : -1;
    }
}
