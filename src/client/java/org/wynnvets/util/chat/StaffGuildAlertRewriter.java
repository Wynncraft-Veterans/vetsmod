package org.wynnvets.util.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.wynnvets.util.StaffRanksFetcher;

/**
 * Rewrites incoming guild chat alerts from staff that begin with the double-bang marker.
 */
public final class StaffGuildAlertRewriter {

    private static final String ALERT_PREFIX = "‼";
    private static final String SHOUT_SYMBOL_PREFIX = "\uDAFF\uDFFC\uE015\uDAFF\uDFFF\uE002\uDAFF\uDFFE";
    private static final String ALERT_FRAME_OPEN = "\uE010\u2064";
    private static final String ALERT_FRAME_SEGMENT = "\uE00F\uE012";
    private static final String ALERT_FRAME_CLOSE = "\uE011";

    private static final Style SHOUT_PREFIX_STYLE = Style.EMPTY
        .withFont(ResourceLocation.parse("chat/prefix"))
        .withColor(ChatFormatting.LIGHT_PURPLE);
    private static final Style ALERT_FRAME_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE);
    private static final Style ALERT_TEXT_STYLE = Style.EMPTY.withColor(ChatFormatting.WHITE);
    private static final Style ALERT_BODY_STYLE = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE);
    private static final Style ALERT_BODY_BOLD_STYLE = ALERT_BODY_STYLE.withBold(true);

    private StaffGuildAlertRewriter() {
    }

    public static boolean tryRewrite(String messageString) {
        ParsedGuildChat parsed = parseGuildChat(messageString);
        if (parsed == null) {
            return false;
        }

        if (!isCurrentStaffSender(parsed.username)) {
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

        MutableComponent body = Component.empty()
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
        if (StaffRanksFetcher.confirmedRankFor(normalized).isPresent()) {
            return true;
        }

        String[] variants = normalized.split("/");
        for (String variant : variants) {
            String candidate = variant.trim();
            if (!candidate.isEmpty() && StaffRanksFetcher.confirmedRankFor(candidate).isPresent()) {
                return true;
            }
        }

        return false;
    }

    private static ParsedGuildChat parseGuildChat(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        int colonIndex = message.indexOf(':');
        if (colonIndex <= 0) {
            return null;
        }

        int usernameStart = message.lastIndexOf(' ', colonIndex - 1);
        if (usernameStart <= 0) {
            return null;
        }

        String username = message.substring(usernameStart + 1, colonIndex).trim();
        if (username.isEmpty()) {
            return null;
        }

        int rankStart = message.lastIndexOf(' ', usernameStart - 1);
        if (rankStart <= 0) {
            return null;
        }

        String rankIndicator = message.substring(rankStart + 1, usernameStart).trim();
        if (rankIndicator.isEmpty() || !containsCustomFontGlyph(rankIndicator)) {
            return null;
        }

        String messageContent = message.substring(colonIndex + 1).trim();
        return new ParsedGuildChat(username, messageContent);
    }

    private static boolean containsCustomFontGlyph(String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int type = Character.getType(codePoint);
            if (type == Character.PRIVATE_USE
                || (type == Character.UNASSIGNED && codePoint > 0xFFFF)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private record ParsedGuildChat(String username, String message) {
    }
}