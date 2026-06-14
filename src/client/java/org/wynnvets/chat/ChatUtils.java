package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.wynnvets.rendering.colors.AnimatedGradientSequence;
import org.wynnvets.fetcher.polling.SupportersPoller;
import org.wynnvets.chat.spoiler.SpoilerFormatter;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized utility for sending formatted chat messages to the local player.
 * Heavily inspired by pixlze's guild-api
 *
 * <p>All mod-initiated chat output should go through this class so that messages
 * are consistently formatted: {@code <badge> <pill> <username>: <message>}.</p>
 */
public final class ChatUtils {

    private static final ThreadLocal<Boolean> INTERNAL_CHAT_DISPATCH = ThreadLocal.withInitial(() -> false);

    /** Style used for rank "pill" text. */
    public static final Style RANK_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);

    /** Style used for the display-name portion of guild chat. */
    public static final Style NAME_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);

    /** Style used for the rank pill / separator / message body of honourary chat. */
    public static final Style HONOURARY_RANK_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);

    /** Style used for the display-name portion of honourary chat. */
    public static final Style HONOURARY_NAME_STYLE = Style.EMPTY.withColor(ChatFormatting.AQUA);

    /** Red style used for admin-locked guild message body text. */
    public static final Style ADMIN_RANK_STYLE = Style.EMPTY.withColor(ChatFormatting.RED);

    /** Dark-red style used for admin-locked guild message display names. */
    public static final Style ADMIN_NAME_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_RED);

    private static final String CAPTAIN = "captain";
    private static final String STRATEGIST = "strategist";
    private static final String CHIEF = "chief";
    private static final String OWNER = "owner";
    private static final String STAFF_PILL_FRAME_OPEN = "\uE010\u2064";
    private static final String STAFF_PILL_FRAME_SEGMENT = "\uE00F\uE012";
    private static final String STAFF_PILL_FRAME_CLOSE = "\uE011";
    private static final String GUILD_PREPEND_FULL = "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
    private static final String GUILD_PREPEND_COMPACT = "\uDAFF\uDFFC\uE001\uDB00\uDC06";
    private static final String PRIVATE_SEPARATOR_GLYPH = "\uE003";
    private static final Style CHAT_PREFIX_STYLE = Style.EMPTY
            .withFont(new FontDescription.Resource(Identifier.parse("chat/prefix")))
            .withoutShadow();

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?<!§)("
            + "https?://\\S+"
            + "|[A-Za-z0-9][A-Za-z0-9-]*(?:\\.[A-Za-z0-9][A-Za-z0-9-]*)+/\\S*"
            + ")",
            Pattern.CASE_INSENSITIVE);

    private ChatUtils() {
    }

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

    /**
     * Returns whether the current thread is dispatching a mod-generated chat message.
     */
    public static boolean isInternalDispatch() {
        return Boolean.TRUE.equals(INTERNAL_CHAT_DISPATCH.get());
    }

    // ── Simple messages ────────────────────────────────────────────────

    /**
     * Sends a message to the player's chat with a {@link Prepend#DEFAULT} badge.
     *
     * @param message the message component to display
     */
    public static void sendLocalMessage(Component message) {
        sendLocalMessage(message, Prepend.DEFAULT);
    }

    /**
     * Same as {@link #sendLocalMessage(Component)} but marks this as the
     * start of a new render block first ({@link Prepend#resetDedup()}).
     * The next badge dedup hit re-extends to the full banner, giving
     * staff a visual divider between back-to-back command outputs.
     * Use for the *first* line of any new command response; subsequent
     * lines in the same block should keep using plain
     * {@link #sendLocalMessage(Component)} so they demote to the compact
     * indicator as usual.
     */
    public static void sendLocalMessageNewBlock(Component message) {
        Prepend.resetDedup();
        sendLocalMessage(message, Prepend.DEFAULT);
    }

    /**
     * Sends a message to the player's chat with the given badge prepended.
     *
     * @param message the message component to display
     * @param prepend the badge to prepend
     */
    public static void sendLocalMessage(Component message, Prepend prepend) {
        MutableComponent badge = prepend.get();
        MutableComponent full = Component.empty()
                .append(badge)
                .append(message);

        dispatchToChat(full, badge.getStyle());
    }

    // ── Guild-style messages (<badge> <pill> <username>: <message>) ───

    /**
     * Sends a guild-chat–style message:
     * {@code <guild badge> <rank> <displayName>: <message>}.
     *
     * @param rank        the rank text (pill); may be empty
     * @param displayName the player display name
     * @param message     the chat message body
     */
    public static void sendGuildChatMessage(String rank, String displayName, String message) {
        MutableComponent badge = Prepend.GUILD.get();
        String normalizedRank = rank == null ? "" : rank.trim();

        // Convert ASCII rank text (e.g. "Recruiter" from the outbound WebSocket)
        // to PUA pill characters for the chat/prefix font.  Ranks that are
        // already PUA-encoded (waitlist/honourary self-messages) pass through.
        String pillText = normalizedRank.isEmpty() ? "" : encodePillIfAscii(normalizedRank);

        MutableComponent body = Component.empty();

        boolean isSupporter = !pillText.isEmpty()
            && SupportersPoller.isSupporter(displayName);

        if (!pillText.isEmpty()) {
            body.append(PillFormatter.formatPill(pillText, displayName, isSupporter))
                    .append(" ");
        }

        body.append(Component.literal(displayName).setStyle(NAME_STYLE))
                .append(Component.literal(": ").setStyle(RANK_STYLE))
            .append(formatMessageBody(message, RANK_STYLE));

        MutableComponent full = Component.empty()
                .append(badge)
                .append(body);

        Style badgeStyle = badge.getStyle();
        if (isSupporter) {
            dispatchAnimatedChat(full, badgeStyle);
        } else {
            dispatchToChat(full, badgeStyle);
        }
    }

    /**
     * Sends an honourary-chat–style message:
     * {@code &9<guild badge> &3<rank> &b<displayName>&3: <message>}.
     *
     * <p>Used for outgoing {@code /wg} echoes and for inbound messages with
     * {@code type="honourary"}.  The badge uses the {@code U+E013} glyph
     * instead of the regular {@code U+E006}, and the colour scheme inverts
     * the rank/name relationship so honourary chat is distinguishable from
     * Returners guild chat.</p>
     */
    public static void sendHonouraryChatMessage(String rank, String displayName, String message) {
        MutableComponent badge = Prepend.GUILD_HONOURARY.get();
        String normalizedRank = rank == null ? "" : rank.trim();

        String pillText = normalizedRank.isEmpty() ? "" : encodePillIfAscii(normalizedRank);

        MutableComponent body = Component.empty();

        boolean isSupporter = !pillText.isEmpty()
            && SupportersPoller.isSupporter(displayName);

        if (!pillText.isEmpty()) {
            body.append(PillFormatter.formatPill(pillText, displayName, HONOURARY_RANK_STYLE, isSupporter))
                    .append(" ");
        }

        body.append(Component.literal(displayName).setStyle(HONOURARY_NAME_STYLE))
                .append(Component.literal(": ").setStyle(HONOURARY_RANK_STYLE))
                .append(formatMessageBody(message, HONOURARY_RANK_STYLE));

        MutableComponent full = Component.empty()
                .append(badge)
                .append(body);

        Style badgeStyle = badge.getStyle();
        if (isSupporter) {
            dispatchAnimatedChat(full, badgeStyle);
        } else {
            dispatchToChat(full, badgeStyle);
        }
    }

    /**
     * Sends a guild-chat–style message in admin-locked red styling:
     * {@code &c<guild badge> <rank>&4 <displayName>&c: <message>}.
     */
    public static void sendGuildChatMessageRed(String rank, String displayName, String message) {
        MutableComponent badge = Prepend.GUILD.get()
                .withStyle(style -> style.withColor(ChatFormatting.RED));
        String normalizedRank = rank == null ? "" : rank.trim();

        MutableComponent body = Component.empty();

        if (!normalizedRank.isEmpty()) {
            body.append(PillFormatter.formatPill(normalizedRank, displayName, ADMIN_RANK_STYLE))
                    .append(" ");
        }

        body.append(Component.literal(displayName).setStyle(ADMIN_NAME_STYLE))
                .append(Component.literal(": ").setStyle(ADMIN_RANK_STYLE))
            .append(formatMessageBody(message, ADMIN_RANK_STYLE));

        MutableComponent full = Component.empty()
                .append(badge)
                .append(body);

        dispatchToChat(full, badge.getStyle());
    }

        /**
         * Sends a guild-chat–style message in admin-locked red styling with a custom
         * pre-styled rank pill component.
         */
        public static void sendGuildChatMessageRed(Component rankComponent, String displayName, String message) {
        MutableComponent badge = Prepend.GUILD.get()
            .withStyle(style -> style.withColor(ChatFormatting.RED));

        MutableComponent body = Component.empty();

        if (rankComponent != null && !rankComponent.getString().trim().isEmpty()) {
            body.append(rankComponent)
                .append(" ");
        }

        body.append(Component.literal(displayName).setStyle(ADMIN_NAME_STYLE))
            .append(Component.literal(": ").setStyle(ADMIN_RANK_STYLE))
            .append(formatMessageBody(message, ADMIN_RANK_STYLE));

        MutableComponent full = Component.empty()
            .append(badge)
            .append(body);

        dispatchToChat(full, badge.getStyle());
        }

    /**
     * Sends a guild-chat–style message where the body is a pre-built component.
     * Used when the message body requires special formatting (e.g. animated text)
     * that cannot be expressed as a plain string.
     *
     * @param rank        the rank text (pill); may be empty
     * @param displayName the player display name
     * @param bodyComponent the pre-built message body component
     */
    public static void sendGuildChatMessageWithBody(String rank, String displayName, Component bodyComponent) {
        MutableComponent badge = Prepend.GUILD.get();
        String normalizedRank = rank == null ? "" : rank.trim();

        String pillText = normalizedRank.isEmpty() ? "" : encodePillIfAscii(normalizedRank);

        MutableComponent body = Component.empty();

        boolean isSupporter = !pillText.isEmpty()
            && SupportersPoller.isSupporter(displayName);

        if (!pillText.isEmpty()) {
            body.append(PillFormatter.formatPill(pillText, displayName, isSupporter))
                    .append(" ");
        }

        body.append(Component.literal(displayName).setStyle(NAME_STYLE))
                .append(Component.literal(": ").setStyle(RANK_STYLE))
                .append(bodyComponent);

        MutableComponent full = Component.empty()
                .append(badge)
                .append(body);

        dispatchToChat(full, badge.getStyle());
    }

    /**
     * Sends a staff-channel styled message using the same visuals as /v self echo.
     * Defaults to Captain when rank is unknown.
     */
    public static void sendStaffChannelMessage(String displayName, String message, String rank) {
        sendGuildChatMessageRed(buildStaffPillComponent(rank), displayName, message);
    }

    /**
     * Builds a mixed red/dark staff pill glyph sequence.
     */
    public static Component buildStaffPillComponent(String rank) {
        String label = normalizeStaffRank(rank);

        MutableComponent component = Component.empty();
        Style redStyle = ADMIN_RANK_STYLE.withoutShadow();
        Style darkStyle = Style.EMPTY.withColor(ChatFormatting.BLACK).withoutShadow();

        component.append(Component.literal(STAFF_PILL_FRAME_OPEN).setStyle(redStyle));

        String upper = label.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            char letter = upper.charAt(i);
            if (letter < 'A' || letter > 'Z') {
                continue;
            }

            component.append(Component.literal(STAFF_PILL_FRAME_SEGMENT).setStyle(redStyle));
            component.append(Component.literal(String.valueOf((char) ('\uE040' + (letter - 'A')))).setStyle(darkStyle));
        }

        component.append(Component.literal(STAFF_PILL_FRAME_CLOSE).setStyle(redStyle));
        return component;
    }

    /**
     * Encodes an ASCII rank name to PUA pill characters (E040 letter range
     * with E06B/E06C frame) for the {@code chat/prefix} font.  Strings that
     * already contain PUA codepoints are returned as-is.
     */
    static String encodePillIfAscii(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.getType(cp) == Character.PRIVATE_USE) {
                return text;
            }
            i += Character.charCount(cp);
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\uE06B');
        for (char c : text.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('\uE040' + (c - 'A')));
            }
        }
        sb.append('\uE06C');
        return sb.toString();
    }

    private static String normalizeStaffRank(String rank) {
        String normalized = rank == null ? CAPTAIN : rank.trim().toLowerCase();
        switch (normalized) {
            case STRATEGIST:
                return STRATEGIST;
            case CHIEF:
                return CHIEF;
            case OWNER:
                return OWNER;
            case CAPTAIN:
            default:
                return CAPTAIN;
        }
    }

    /**
     * Formats a raw message body into a styled component, processing inline
     * prefix markers and converting URLs into clickable links.
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

            // Check whether a '\n' immediately precedes this marker.  If so, the
            // marker is a server-injected line-continuation prefix — absorb both
            // the '\n' and the marker (plus any trailing space) so that the text
            // re-flows as a single paragraph for wrapBlockMessage() to re-wrap.
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
                // The server sometimes emits multiple consecutive markers on
                // continuation lines (e.g. "\n<compact> <compact> text").
                // Consume any additional marker+space pairs so that only one
                // continuation bar is rendered after wrapBlockMessage() re-wraps.
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
                    appendTextWithUrls(formatted, bodyText.substring(cursor, markerStart), textStyle);
                }
                formatted.append(Component.literal(PRIVATE_SEPARATOR_GLYPH).setStyle(prefixStyleFor(textStyle)));
                cursor = match.index + match.length;
            }
        }

        return formatted;
    }

    /**
     * Strips server-injected line-continuation sequences ({@code \n<marker>}) from
     * the body text, joining wrapped lines with a single space.  This ensures that
     * inline patterns like {@code ||spoiler||} are not split across segments before
     * spoiler and URL processing.
     *
     * <p>Inline markers (not preceded by {@code \n}) are left intact for the main
     * loop in {@link #formatMessageBody} to handle.</p>
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
     * Builds a fresh {@link MutableComponent} from text containing legacy
     * {@code §}-formatting codes, detecting URLs and making them clickable.
     * Bare hosts (e.g. {@code example.com/path}) are displayed as-typed but
     * linked to {@code https://}-prefixed URIs. URL segments inherit the
     * accumulated legacy style (color, bold, underline, …) from the codes
     * preceding them, since vanilla {@code §}-state does not carry across
     * sibling components.
     */
    public static MutableComponent literalWithUrls(String text, Style baseStyle) {
        MutableComponent parent = Component.empty();
        if (text.isEmpty()) {
            return parent;
        }
        Style currentStyle = baseStyle;
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                if (buffer.length() > 0) {
                    appendTextWithUrls(parent, buffer.toString(), currentStyle);
                    buffer.setLength(0);
                }
                currentStyle = applyLegacyCode(baseStyle, currentStyle,
                        Character.toLowerCase(text.charAt(i + 1)));
                i += 2;
                continue;
            }
            buffer.append(c);
            i++;
        }
        if (buffer.length() > 0) {
            appendTextWithUrls(parent, buffer.toString(), currentStyle);
        }
        return parent;
    }

    /**
     * Apply a single legacy {@code §X} code to the accumulating style. Color
     * codes reset formatting back to {@code baseStyle} per vanilla behavior;
     * format codes (k/l/m/n/o) add to whatever {@code currentStyle} already has;
     * {@code §r} resets to {@code baseStyle}.
     */
    private static Style applyLegacyCode(Style baseStyle, Style currentStyle, char code) {
        switch (code) {
            case '0': return baseStyle.withColor(ChatFormatting.BLACK);
            case '1': return baseStyle.withColor(ChatFormatting.DARK_BLUE);
            case '2': return baseStyle.withColor(ChatFormatting.DARK_GREEN);
            case '3': return baseStyle.withColor(ChatFormatting.DARK_AQUA);
            case '4': return baseStyle.withColor(ChatFormatting.DARK_RED);
            case '5': return baseStyle.withColor(ChatFormatting.DARK_PURPLE);
            case '6': return baseStyle.withColor(ChatFormatting.GOLD);
            case '7': return baseStyle.withColor(ChatFormatting.GRAY);
            case '8': return baseStyle.withColor(ChatFormatting.DARK_GRAY);
            case '9': return baseStyle.withColor(ChatFormatting.BLUE);
            case 'a': return baseStyle.withColor(ChatFormatting.GREEN);
            case 'b': return baseStyle.withColor(ChatFormatting.AQUA);
            case 'c': return baseStyle.withColor(ChatFormatting.RED);
            case 'd': return baseStyle.withColor(ChatFormatting.LIGHT_PURPLE);
            case 'e': return baseStyle.withColor(ChatFormatting.YELLOW);
            case 'f': return baseStyle.withColor(ChatFormatting.WHITE);
            case 'k': return currentStyle.withObfuscated(true);
            case 'l': return currentStyle.withBold(true);
            case 'm': return currentStyle.withStrikethrough(true);
            case 'n': return currentStyle.withUnderlined(true);
            case 'o': return currentStyle.withItalic(true);
            case 'r': return baseStyle;
            default:  return currentStyle;
        }
    }

    /**
     * Appends a text segment to a parent component, detecting URLs and making
     * them clickable with {@link ClickEvent.OpenUrl}.
     */
    private static void appendTextWithUrls(MutableComponent parent, String text, Style textStyle) {
        if (text.isEmpty()) {
            return;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                SpoilerFormatter.appendWithSpoilers(parent, text.substring(lastEnd, matcher.start()), textStyle);
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

    // ── Block-message wrapping ──────────────────────────────────────────

    /**
     * Wraps a message in Wynncraft's block-message style: continuation lines are
     * prefixed with the compact block indicator ({@code \uDAFF\uDFFC\uE001\uDB00\uDC06})
     * in the {@code chat/prefix} font, matching the visual style of server-wrapped
     * guild chat.
     *
     * <p>Uses {@link StringSplitter#splitLines(FormattedText, int, Style, FormattedText)}
     * to word-wrap to the current chat width.</p>
     *
     * @param message      the full message component (badge + body)
     * @param prependStyle the style for the continuation prefix (inherits colour from the badge)
     * @return the wrapped message with {@code \n}-separated lines
     */
    private static Component wrapBlockMessage(Component message, Style prependStyle) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        StringSplitter splitter = font.getSplitter();

        int chatWidth = ChatComponent.getWidth(minecraft.options.chatWidth().get());

        // Build the continuation prefix: block marker + space, styled like the badge
        Style continuationStyle = CHAT_PREFIX_STYLE;
        TextColor prependColor = prependStyle.getColor();
        if (prependColor != null) {
            continuationStyle = continuationStyle.withColor(prependColor);
        }
        MutableComponent continuation = Component.literal(GUILD_PREPEND_COMPACT)
                .append(" ")
                .setStyle(continuationStyle);

        List<FormattedText> lines = splitter.splitLines(
                message, chatWidth, message.getStyle());

        if (lines.size() <= 1) {
            return message;
        }

        MutableComponent wrapped = toComponent(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            wrapped.append("\n");
            wrapped.append(continuation.copy()).append(toComponent(lines.get(i)));
        }
        return wrapped;
    }

    /**
     * Converts a {@link FormattedText} back to a {@link MutableComponent}.
     */
    private static MutableComponent toComponent(FormattedText text) {
        MutableComponent result = Component.empty();
        text.visit((style, content) -> {
            if (!content.isEmpty()) {
                result.append(Component.literal(content).setStyle(style));
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    /**
     * Counts the number of rendered lines a component will occupy in chat and
     * updates {@link Prepend}'s badge deduplication counter.
     */
    private static void countRenderedLines(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        int chatWidth = ChatComponent.getWidth(minecraft.options.chatWidth().get());
        int lineCount = ComponentRenderUtils.wrapComponents(
                message, chatWidth, minecraft.font).size();
        Prepend.addRenderedLines(lineCount);
    }

    // ── Internal ───────────────────────────────────────────────────────

    /**
     * Dispatches a component with the animated gradient context active so that
     * {@code AnimatedChatMixin} wraps the stored lines.
     * Uses the supporter gradient (DARK_AQUA → white, 3 s cycle).
     *
     * @param message      the full message component
     * @param prependStyle the badge style used for continuation-line block markers
     */
    public static void dispatchAnimatedChat(Component message, Style prependStyle) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(() -> {
            if (minecraft.player != null) {
                Component wrapped = wrapBlockMessage(message, prependStyle);
                countRenderedLines(wrapped);
                boolean previous = INTERNAL_CHAT_DISPATCH.get();
                INTERNAL_CHAT_DISPATCH.set(true);
                AnimatedGradientSequence.beginAnimation(
                    AnimatedGradientSequence.effectiveDefaultStart(),
                    AnimatedGradientSequence.effectiveDefaultEnd(),
                    AnimatedGradientSequence.DEFAULT_CYCLE_TIME_MS);
                try {
                    minecraft.player.displayClientMessage(wrapped, false);
                } finally {
                    AnimatedGradientSequence.endAnimation();
                    INTERNAL_CHAT_DISPATCH.set(previous);
                }
            }
        });
    }

    /**
     * Thread-safely dispatches a component to the player's chat HUD.
     * If the player is not yet available the message is silently dropped.
     *
     * <p>Used by chat rewriters to dispatch rebuilt messages directly
     * without going through the high-level message-building API.</p>
     *
     * @param message      the full message component
     * @param prependStyle the badge style used for continuation-line block markers
     */
    public static void dispatchToChat(Component message, Style prependStyle) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.execute(() -> {
            if (minecraft.player != null) {
                Component wrapped = wrapBlockMessage(message, prependStyle);
                countRenderedLines(wrapped);
                boolean previous = INTERNAL_CHAT_DISPATCH.get();
                INTERNAL_CHAT_DISPATCH.set(true);
                try {
                    minecraft.player.displayClientMessage(wrapped, false);
                } finally {
                    INTERNAL_CHAT_DISPATCH.set(previous);
                }
            }
        });
    }
}
