package org.wynnvets.chat;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.wynnvets.chat.spoiler.SpoilerFormatter;

/**
 * Formats a chat message body: joins the lines the server wrapped by stripping its continuation
 * markers, draws an inline prefix marker as the separator glyph, renders encoded spoilers and makes
 * URLs clickable. It also holds the prefix-marker glyphs, the {@code chat/prefix} style the
 * separator and continuation markers are drawn in, and the test for a fragment that is only wrap
 * structure.
 */
public final class MessageBodyFormatter {

    private static final String GUILD_PREPEND_FULL =
            "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
    // Package-private for LocalChatDispatch, which prefixes wrapped continuation lines with it.
    static final String GUILD_PREPEND_COMPACT = "\uDAFF\uDFFC\uE001\uDB00\uDC06";
    private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";
    // Package-private for LocalChatDispatch, which draws the continuation marker of wrapped lines
    // in it.
    static final Style CHAT_PREFIX_STYLE =
            Style.EMPTY
                    .withFont(new FontDescription.Resource(Identifier.parse("chat/prefix")))
                    .withoutShadow();

    private static final Pattern URL_PATTERN =
            Pattern.compile(
                    "(?<!§)("
                            + "https?://\\S+"
                            + "|[A-Za-z0-9][A-Za-z0-9-]*(?:\\.[A-Za-z0-9][A-Za-z0-9-]*)+/\\S*"
                            + ")",
                    Pattern.CASE_INSENSITIVE);

    private MessageBodyFormatter() {}

    /**
     * Returns {@code true} when {@code text} is purely server wrap-continuation
     * structure: a lone newline or a known prefix marker glyph.  Interactive
     * fragments matching this predicate should be accumulated rather than passed
     * through directly so that {@link #formatMessageBody} can strip redundant
     * server-injected continuation markers.
     */
    public static boolean isWrapStructure(String text) {
        return text.equals("\n")
                || text.equals(GUILD_PREPEND_FULL)
                || text.equals(GUILD_PREPEND_COMPACT)
                || text.equals(PRIVATE_SEPARATOR_GLYPH);
    }

    // ── Message-body formatting ────────────────────────────────────────

    /**
     * Formats a raw message body into a styled component: strips server wrap
     * continuations, replaces inline prefix markers, renders encoded spoilers (unless
     * spoiler handling is off) and converts URLs into clickable links.
     *
     * @param message   the raw message text (may be {@code null})
     * @param textStyle the default style applied to non-marker text
     * @return a fully formatted component ready for display
     */
    public static MutableComponent formatMessageBody(String message, Style textStyle) {
        String bodyText = message == null ? "" : message;
        bodyText = stripServerContinuations(bodyText);
        MutableComponent formatted = Component.empty();
        int cursor = 0;

        while (cursor < bodyText.length()) {
            MarkerMatch match = nextPrefixMarker(bodyText, cursor);
            if (match == null) {
                appendTextWithUrls(formatted, bodyText.substring(cursor), textStyle);
                break;
            }

            // Unreachable: stripServerContinuations (called above) already removed every
            // '\n' + marker sequence, so a marker found here is never preceded by '\n' and
            // isServerWrap below is always false. The live copy of this consumption loop is
            // the inner loop in stripServerContinuations.
            int markerStart = match.index;
            boolean isServerWrap = markerStart > 0 && bodyText.charAt(markerStart - 1) == '\n';

            if (isServerWrap) {
                // Emit text up to the '\n' (excluding it)
                int textEnd = markerStart - 1;
                if (textEnd > cursor) {
                    appendTextWithUrls(formatted, bodyText.substring(cursor, textEnd), textStyle);
                }
                // Skip the '\n', the marker itself, and any single trailing space
                cursor = match.index + match.length;
                if (cursor < bodyText.length() && bodyText.charAt(cursor) == ' ') {
                    cursor++;
                }
                // Consume any additional marker+space pairs so that only one
                // continuation bar would be rendered after
                // LocalChatDispatch.wrapBlockMessage() re-wraps, if this branch could run
                // (see the note above).
                while (cursor < bodyText.length()) {
                    MarkerMatch extra = nextPrefixMarker(bodyText, cursor);
                    if (extra == null || extra.index != cursor) {
                        break;
                    }
                    cursor = extra.index + extra.length;
                    if (cursor < bodyText.length() && bodyText.charAt(cursor) == ' ') {
                        cursor++;
                    }
                }
                // Ensure words don't merge across the join point
                if (cursor < bodyText.length()) {
                    formatted.append(Component.literal(" ").setStyle(textStyle));
                }
            } else {
                // Inline marker (not from server wrapping) — replace with the
                // separator glyph in chat/prefix font, preserving the original
                // visual intent.
                if (markerStart > cursor) {
                    appendTextWithUrls(
                            formatted, bodyText.substring(cursor, markerStart), textStyle);
                }
                formatted.append(
                        Component.literal(PRIVATE_SEPARATOR_GLYPH)
                                .setStyle(prefixStyleFor(textStyle)));
                cursor = match.index + match.length;
            }
        }

        return formatted;
    }

    /**
     * Strips server-injected line-continuation sequences ({@code \n<marker>}) from
     * the body text, joining wrapped lines with a single space.  This keeps an encoded
     * spoiler block (see {@link org.wynnvets.chat.spoiler.SpoilerCodec SpoilerCodec}) from
     * being split across segments before spoiler and URL processing.
     *
     * <p>Inline markers (not preceded by {@code \n}) are left intact for the main
     * loop in {@link #formatMessageBody} to handle, unless they directly follow a
     * stripped continuation: those are consumed with it, each with at most one trailing space.</p>
     */
    private static String stripServerContinuations(String text) {
        if (text.indexOf('\n') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            int nlIndex = text.indexOf('\n', cursor);
            if (nlIndex < 0) {
                sb.append(text, cursor, text.length());
                break;
            }
            MarkerMatch match = nextPrefixMarker(text, nlIndex + 1);
            if (match != null && match.index == nlIndex + 1) {
                sb.append(text, cursor, nlIndex);
                cursor = match.index + match.length;
                if (cursor < text.length() && text.charAt(cursor) == ' ') {
                    cursor++;
                }
                // The server appears to sometimes emit multiple consecutive markers on
                // continuation lines (e.g. "\n<compact> <compact> text"); consume any
                // additional marker+space pairs so only one continuation bar is rendered
                // after LocalChatDispatch.wrapBlockMessage() re-wraps. (Server-side
                // behaviour, unverified.)
                while (cursor < text.length()) {
                    MarkerMatch extra = nextPrefixMarker(text, cursor);
                    if (extra == null || extra.index != cursor) {
                        break;
                    }
                    cursor = extra.index + extra.length;
                    if (cursor < text.length() && text.charAt(cursor) == ' ') {
                        cursor++;
                    }
                }
                if (cursor < text.length() && sb.length() > 0) {
                    sb.append(' ');
                }
            } else {
                sb.append(text, cursor, nlIndex + 1);
                cursor = nlIndex + 1;
            }
        }
        return sb.toString();
    }

    /**
     * Appends a text segment to a parent component, making URLs clickable with
     * {@link ClickEvent.OpenUrl} and passing the text between them through
     * {@link SpoilerFormatter#appendWithSpoilers SpoilerFormatter.appendWithSpoilers}
     * (encoded spoiler blocks).
     */
    // Package-private for LegacyTextParser, which links each run of legacy-styled text.
    static void appendTextWithUrls(MutableComponent parent, String text, Style textStyle) {
        if (text.isEmpty()) {
            return;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                SpoilerFormatter.appendWithSpoilers(
                        parent, text.substring(lastEnd, matcher.start()), textStyle);
            }
            String url = matcher.group();
            try {
                String href = url.matches("(?i)^https?://.*") ? url : "https://" + url;
                URI uri = URI.create(href);
                Style urlStyle = textStyle.withClickEvent(new ClickEvent.OpenUrl(uri));
                parent.append(Component.literal(url).setStyle(urlStyle));
            } catch (Exception e) {
                parent.append(Component.literal(url).setStyle(textStyle));
            }
            lastEnd = matcher.end();
        }
        if (lastEnd == 0) {
            SpoilerFormatter.appendWithSpoilers(parent, text, textStyle);
        } else if (lastEnd < text.length()) {
            SpoilerFormatter.appendWithSpoilers(parent, text.substring(lastEnd), textStyle);
        }
    }

    private static MarkerMatch nextPrefixMarker(String text, int fromIndex) {
        int fullIndex = text.indexOf(GUILD_PREPEND_FULL, fromIndex);
        int compactIndex = text.indexOf(GUILD_PREPEND_COMPACT, fromIndex);
        int separatorIndex = text.indexOf(PRIVATE_SEPARATOR_GLYPH, fromIndex);

        int bestIndex = Integer.MAX_VALUE;
        int bestLength = 0;

        if (fullIndex >= 0 && fullIndex < bestIndex) {
            bestIndex = fullIndex;
            bestLength = GUILD_PREPEND_FULL.length();
        }
        if (compactIndex >= 0 && compactIndex < bestIndex) {
            bestIndex = compactIndex;
            bestLength = GUILD_PREPEND_COMPACT.length();
        }
        if (separatorIndex >= 0 && separatorIndex < bestIndex) {
            bestIndex = separatorIndex;
            bestLength = PRIVATE_SEPARATOR_GLYPH.length();
        }

        if (bestLength == 0) {
            return null;
        }

        return new MarkerMatch(bestIndex, bestLength);
    }

    private static Style prefixStyleFor(Style textStyle) {
        TextColor textColor = textStyle.getColor();
        if (textColor == null) {
            return CHAT_PREFIX_STYLE;
        }

        return CHAT_PREFIX_STYLE.withColor(textColor);
    }

    private static final class MarkerMatch {
        final int index;
        final int length;

        MarkerMatch(int index, int length) {
            this.index = index;
            this.length = length;
        }
    }
}
