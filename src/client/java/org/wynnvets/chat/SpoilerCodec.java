package org.wynnvets.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes and decodes spoiler content between Discord-style {@code ||text||}
 * markers and PUA-encoded form (U+F600–U+F700 range).
 *
 * <p>Encoding scheme:
 * <ul>
 *   <li>{@code \uF600} — start of spoiler block</li>
 *   <li>{@code \uF601} — end of spoiler block</li>
 *   <li>{@code \uF602}–{@code \uF6FF} — direct encoding of chars 0–253</li>
 *   <li>{@code \uF700} — escape prefix for chars ≥ 254, followed by 3 base-254 digits</li>
 * </ul>
 *
 * <p>For ASCII spoiler content (the common case), each character maps to exactly
 * one PUA char, and the delimiters add only 2 chars (vs 4 for {@code ||...||}),
 * so the encoded form is always at least as compact as the original.</p>
 */
public final class SpoilerCodec {

    /** Marks the start of a PUA-encoded spoiler block. */
    public static final char SPOILER_START = '\uF600';

    /** Marks the end of a PUA-encoded spoiler block. */
    public static final char SPOILER_END = '\uF601';

    /** Base codepoint for direct character encoding (chars 0–253). */
    private static final char ENCODE_BASE = '\uF602';

    /** Escape prefix for characters ≥ 254, followed by 3 base-254 digits. */
    private static final char ESCAPE = '\uF700';

    /** Direct encoding covers chars 0 through DIRECT_MAX (inclusive). */
    private static final int DIRECT_MAX = 253;

    /** Radix for base-254 extended encoding. */
    private static final int RADIX = 254;

    private static final Pattern PIPE_SPOILER = Pattern.compile("\\|\\|(.+?)\\|\\|");

    private SpoilerCodec() {}

    /**
     * Returns {@code true} if the text contains Discord-style {@code ||spoiler||} markers.
     */
    public static boolean containsPipeSpoiler(String text) {
        return text != null && PIPE_SPOILER.matcher(text).find();
    }

    /**
     * Returns {@code true} if the text contains at least one PUA-encoded spoiler block.
     */
    public static boolean containsEncodedSpoiler(String text) {
        if (text == null) {
            return false;
        }
        int start = text.indexOf(SPOILER_START);
        return start >= 0 && text.indexOf(SPOILER_END, start + 1) > start;
    }

    /**
     * Replaces all {@code ||spoiler||} markers in a message with PUA-encoded form.
     * Returns the message unchanged if no markers are found.
     */
    public static String encodeSpoilers(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        Matcher matcher = PIPE_SPOILER.matcher(message);
        if (!matcher.find()) {
            return message;
        }

        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        matcher.reset();
        while (matcher.find()) {
            sb.append(message, lastEnd, matcher.start());
            sb.append(SPOILER_START);
            encodeContent(matcher.group(1), sb);
            sb.append(SPOILER_END);
            lastEnd = matcher.end();
        }
        sb.append(message, lastEnd, message.length());
        return sb.toString();
    }

    /**
     * Decodes a PUA-encoded content string (without start/end delimiters) back
     * to plaintext.
     */
    public static String decodeContent(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < encoded.length()) {
            char ch = encoded.charAt(i);
            if (ch == ESCAPE) {
                if (i + 3 < encoded.length()) {
                    int d0 = encoded.charAt(i + 1) - ENCODE_BASE;
                    int d1 = encoded.charAt(i + 2) - ENCODE_BASE;
                    int d2 = encoded.charAt(i + 3) - ENCODE_BASE;
                    sb.append((char) (DIRECT_MAX + 1 + d0 + d1 * RADIX + d2 * RADIX * RADIX));
                    i += 4;
                } else {
                    i++;
                }
            } else if (ch >= ENCODE_BASE && ch < ESCAPE) {
                sb.append((char) (ch - ENCODE_BASE));
                i++;
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Replaces all PUA-encoded spoiler blocks in text with Discord
     * {@code ||spoiler||} format.
     */
    public static String decodeToDiscord(String text) {
        if (text == null || !containsEncodedSpoiler(text)) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf(SPOILER_START, cursor);
            if (start < 0) {
                sb.append(text, cursor, text.length());
                break;
            }
            int end = text.indexOf(SPOILER_END, start + 1);
            if (end < 0) {
                sb.append(text, cursor, text.length());
                break;
            }
            sb.append(text, cursor, start);
            sb.append("||").append(decodeContent(text.substring(start + 1, end))).append("||");
            cursor = end + 1;
        }
        return sb.toString();
    }

    private static void encodeContent(String text, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= DIRECT_MAX) {
                sb.append((char) (ENCODE_BASE + c));
            } else {
                int adjusted = c - (DIRECT_MAX + 1);
                sb.append(ESCAPE);
                sb.append((char) (ENCODE_BASE + adjusted % RADIX));
                sb.append((char) (ENCODE_BASE + (adjusted / RADIX) % RADIX));
                sb.append((char) (ENCODE_BASE + adjusted / (RADIX * RADIX)));
            }
        }
    }
}
