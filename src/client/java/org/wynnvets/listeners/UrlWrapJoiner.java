package org.wynnvets.listeners;

import java.util.ArrayList;
import java.util.List;
import org.wynnvets.chat.PillCodec;

/**
 * Joins a URL that Wynncraft hard-broke across chat lines, from the raw line breaks rather than
 * from a guess about the words that follow.
 *
 * <p>Wynncraft wraps a guild chat line in two ways. A word wrap breaks at a space: the line either
 * keeps that space before the {@code \n} or drops it, and a URL that does not fit moves whole to
 * the next line. A URL wider than a line is broken in place, mid-token, with no space on either
 * side of the {@code \n}. Every continuation line starts with the wrap prefix (resource-pack glyph
 * art, then one space). Wynntils' {@code StyledTextUtils.unwrap} then folds each {@code \n} and its
 * prefix into a single space, so afterwards a hard break and a typed space look the same; this
 * class reads the line before that happens.</p>
 *
 * <p>A break is a hard URL break when all of these hold: the last whitespace-delimited token
 * before the {@code \n} holds a URL (contains {@code http://} or {@code https://}, so a URL
 * glued to punctuation such as {@code (} or {@code <} counts, unless that bracket or quote also
 * closes at the token's end, which marks the URL complete) or continues a URL chain, the
 * {@code \n} has no space immediately before it, and the next line, after its prefix, starts
 * with a non-space character. The fragment that follows is that line's first token. A chain
 * continues across the next break only while its latest fragment is the whole of its line, that
 * is, it filled the line. The unwrapped text holds the chain as {@code p1 p2 ... pn} with single
 * spaces, and {@link #join} replaces the first occurrence of that with {@code p1p2...pn}.</p>
 *
 * <p>Known residue: an unbracketed URL that ends exactly at a wrap point where Wynncraft also
 * dropped the space before the next word looks like a hard break, so the URL merges with that
 * word. Nothing in the raw line separates the two cases.</p>
 */
final class UrlWrapJoiner {

    private UrlWrapJoiner() {}

    /**
     * Returns {@code messageContent} with every hard-broken URL in {@code rawPlain} joined.
     *
     * @param rawPlain the line as it arrived, with its {@code \n} and wrap prefixes
     * @param messageContent the same line after the unwrap, cut down to the message body
     * @return {@code messageContent}, or the same text with each chain's fragments glued
     *     together; unchanged when {@code rawPlain} has no hard URL break or a chain's unwrapped
     *     form is not found in it
     */
    static String join(String rawPlain, String messageContent) {
        if (rawPlain == null
                || messageContent == null
                || rawPlain.indexOf('\n') < 0
                || !rawPlain.contains("://")) {
            return messageContent;
        }

        String[] lines = rawPlain.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            lines[i] = stripPrefix(lines[i]);
        }

        String result = messageContent;
        List<String> chain = new ArrayList<>();
        boolean chainOpen = false;
        for (int i = 0; i + 1 < lines.length; i++) {
            String current = lines[i];
            String next = lines[i + 1];

            boolean hard = false;
            if (!current.isEmpty()
                    && !isSpace(current.charAt(current.length() - 1))
                    && !next.isEmpty()
                    && !isSpace(next.charAt(0))) {
                if (!chain.isEmpty() && chainOpen) {
                    hard = true;
                } else if (startsChain(lastToken(current))) {
                    result = glue(result, chain);
                    chain.clear();
                    chain.add(lastToken(current));
                    hard = true;
                }
            }

            if (!hard) {
                result = glue(result, chain);
                chain.clear();
                chainOpen = false;
                continue;
            }

            String piece = firstToken(next);
            chain.add(piece);
            chainOpen = piece.length() == next.length();
        }
        return glue(result, chain);
    }

    /**
     * Replaces the first occurrence of {@code p1 p2 ... pn}, the unwrapped form of a chain, with
     * {@code p1p2...pn}. A chain of fewer than two pieces, or one the text does not contain, leaves
     * the text as it is.
     */
    private static String glue(String text, List<String> chain) {
        if (chain.size() < 2) {
            return text;
        }
        String spaced = String.join(" ", chain);
        int at = text.indexOf(spaced);
        if (at < 0) {
            return text;
        }
        return text.substring(0, at)
                + String.join("", chain)
                + text.substring(at + spaced.length());
    }

    private static String stripPrefix(String line) {
        int i = 0;
        while (i < line.length()) {
            int cp = line.codePointAt(i);
            if (!PillCodec.isCustomGlyph(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        if (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c);
    }

    /**
     * Whether {@code token} can start a chain: it holds a URL, and no bracket or quote that
     * opens just before the scheme closes at the token's end, as in {@code (https://x.org/a)},
     * where the URL is plainly complete.
     */
    private static boolean startsChain(String token) {
        int http = token.indexOf("http://");
        int https = token.indexOf("https://");
        int scheme = http < 0 ? https : https < 0 ? http : Math.min(http, https);
        if (scheme < 0) {
            return false;
        }
        if (scheme == 0) {
            return true;
        }
        char closer = closerOf(token.charAt(scheme - 1));
        return closer == 0 || token.charAt(token.length() - 1) != closer;
    }

    /** The character that closes {@code opener}, or 0 when it opens nothing. */
    private static char closerOf(char opener) {
        return switch (opener) {
            case '(' -> ')';
            case '<' -> '>';
            case '[' -> ']';
            case '"' -> '"';
            case '\'' -> '\'';
            default -> 0;
        };
    }

    private static String lastToken(String line) {
        int i = line.length();
        while (i > 0 && !isSpace(line.charAt(i - 1))) {
            i--;
        }
        return line.substring(i);
    }

    private static String firstToken(String line) {
        int i = 0;
        while (i < line.length() && !isSpace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }
}
