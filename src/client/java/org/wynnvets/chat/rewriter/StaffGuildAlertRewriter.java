package org.wynnvets.chat.rewriter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.wynnvets.chat.ChatUtils;
import org.wynnvets.chat.NickResolver;
import org.wynnvets.fetcher.polling.StaffRanksPoller;

/**
 * Rewrites incoming guild chat alerts from staff that begin with the double-bang marker.
 */
public final class StaffGuildAlertRewriter {

    private static final String ALERT_PREFIX = "‼";
    private static final String SHOUT_SYMBOL_PREFIX =
            "\uDAFF\uDFFC\uE015\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
    private static final String ALERT_FRAME_OPEN = "\uE010\u2064";
    private static final String ALERT_FRAME_SEGMENT = "\uE00F\uE012";
    private static final String ALERT_FRAME_CLOSE = "\uE011";

    private static final Style SHOUT_PREFIX_STYLE =
            Style.EMPTY
                    .withFont(new FontDescription.Resource(Identifier.parse("chat/prefix")))
                    .withColor(ChatFormatting.LIGHT_PURPLE)
                    .withoutShadow();
    private static final Style ALERT_FRAME_STYLE =
            Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE);
    private static final Style ALERT_TEXT_STYLE = Style.EMPTY.withColor(ChatFormatting.WHITE);
    private static final Style ALERT_BODY_STYLE =
            Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE);
    private static final Style ALERT_BODY_BOLD_STYLE = ALERT_BODY_STYLE.withBold(true);

    private StaffGuildAlertRewriter() {}

    /**
     * Attempts to display an outbound WebSocket guild message as a staff alert.
     *
     * <p>Unlike {@link #tryRewrite}, this variant works with pre-parsed fields
     * from the outbound JSON (no Minecraft {@link Component} parsing needed).
     * Used for honorary and waitlist users who receive {@code /a} alerts via
     * the WebSocket rather than the Wynncraft server chat.</p>
     *
     * @param username the sender's username (already resolved by the server)
     * @param message  the message body (may start with {@code ‼})
     * @return {@code true} if the message was displayed as an alert
     */
    public static boolean tryRewriteOutbound(String username, String message) {
        if (!isCurrentStaffSender(username)) {
            return false;
        }

        String trimmed = message.stripLeading();
        if (!trimmed.startsWith(ALERT_PREFIX)) {
            return false;
        }

        String alertMessage = trimmed.substring(ALERT_PREFIX.length()).stripLeading();
        boolean boldBody = alertMessage.startsWith("!");
        if (boldBody) {
            alertMessage = alertMessage.substring(1).stripLeading();
        }

        Style bodyStyle = boldBody ? ALERT_BODY_BOLD_STYLE : ALERT_BODY_STYLE;

        MutableComponent body =
                Component.empty()
                        .append(Component.literal(SHOUT_SYMBOL_PREFIX).setStyle(SHOUT_PREFIX_STYLE))
                        .append(Component.literal(" ").setStyle(bodyStyle))
                        .append(buildAlertPill())
                        .append(Component.literal(": ").setStyle(bodyStyle))
                        .append(ChatUtils.formatMessageBody(alertMessage, bodyStyle));

        ChatUtils.dispatchToChat(body, SHOUT_PREFIX_STYLE);
        return true;
    }

    /**
     * Attempts to rewrite a guild chat message as a staff alert.
     *
     * @param message       the original chat component (used to resolve nicknames via hover text)
     * @param messageString the raw chat line
     * @return {@code true} if the message was rewritten (caller should cancel)
     */
    public static boolean tryRewrite(Component message, String messageString) {
        ParsedGuildChat parsed = parseGuildChat(messageString);
        if (parsed == null) {
            return false;
        }

        // The parsed username may be a Wynncraft nickname (e.g. "Wencrobat"
        // instead of "Wenweia").  Resolve the true username from hover text
        // so the staff check works for nicknamed players.
        String senderUsername = NickResolver.realUsernameOrFallback(message, parsed.username);

        if (!isCurrentStaffSender(senderUsername)) {
            return false;
        }

        String trimmed = parsed.message.stripLeading();
        if (!trimmed.startsWith(ALERT_PREFIX)) {
            return false;
        }

        String alertMessage = trimmed.substring(ALERT_PREFIX.length()).stripLeading();
        boolean boldBody = alertMessage.startsWith("!");
        if (boldBody) {
            alertMessage = alertMessage.substring(1).stripLeading();
        }

        Style bodyStyle = boldBody ? ALERT_BODY_BOLD_STYLE : ALERT_BODY_STYLE;

        MutableComponent body =
                Component.empty()
                        .append(Component.literal(SHOUT_SYMBOL_PREFIX).setStyle(SHOUT_PREFIX_STYLE))
                        .append(Component.literal(" ").setStyle(bodyStyle))
                        .append(buildAlertPill())
                        .append(Component.literal(": ").setStyle(bodyStyle))
                        .append(ChatUtils.formatMessageBody(alertMessage, bodyStyle));

        ChatUtils.dispatchToChat(body, SHOUT_PREFIX_STYLE);
        return true;
    }

    private static MutableComponent buildAlertPill() {
        MutableComponent pill = Component.empty();

        pill.append(Component.literal(ALERT_FRAME_OPEN).setStyle(ALERT_FRAME_STYLE));
        appendAlertGlyph(pill, '\uE040'); // A
        appendAlertGlyph(pill, '\uE04B'); // L
        appendAlertGlyph(pill, '\uE044'); // E
        appendAlertGlyph(pill, '\uE051'); // R
        appendAlertGlyph(pill, '\uE053'); // T
        pill.append(Component.literal(ALERT_FRAME_CLOSE).setStyle(ALERT_FRAME_STYLE));

        return pill;
    }

    private static void appendAlertGlyph(MutableComponent pill, char glyph) {
        pill.append(Component.literal(ALERT_FRAME_SEGMENT).setStyle(ALERT_FRAME_STYLE));
        pill.append(Component.literal(String.valueOf(glyph)).setStyle(ALERT_TEXT_STYLE));
    }

    private static boolean isCurrentStaffSender(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        String normalized = username.trim();
        if (StaffRanksPoller.confirmedRankFor(normalized).isPresent()) {
            return true;
        }

        String[] variants = normalized.split("/");
        for (String variant : variants) {
            String candidate = variant.trim();
            if (!candidate.isEmpty() && StaffRanksPoller.confirmedRankFor(candidate).isPresent()) {
                return true;
            }
        }

        return false;
    }

    // Package-private for unit tests. See GuildChatParseTest.
    static ParsedGuildChat parseGuildChat(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        int colonIndex = message.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }

        // Find the last custom-font glyph before the colon.  The rank
        // indicator always ends with such a glyph; everything after it
        // (trimmed) up to the colon is the display name — which may
        // contain spaces (e.g. "EYAL5555/First Mage").
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

        if (lastGlyphEnd <= 0 || lastGlyphEnd >= colonIndex) {
            return null;
        }

        String username = message.substring(lastGlyphEnd, colonIndex).trim();
        if (username.isEmpty()) {
            return null;
        }

        String messageContent = message.substring(colonIndex + 1).trim();
        return new ParsedGuildChat(username, messageContent);
    }

    // Package-private for unit tests. See GuildChatParseTest.
    record ParsedGuildChat(String username, String message) {}
}
