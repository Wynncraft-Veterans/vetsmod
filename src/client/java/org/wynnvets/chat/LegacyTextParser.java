package org.wynnvets.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Builds a chat component from text that carries legacy {@code §} formatting codes. Each code
 * styles the text that follows it. URLs in the text become clickable links and encoded spoilers
 * become spoiler labels, as in a message body formatted by {@link MessageBodyFormatter}.
 */
public final class LegacyTextParser {

    private LegacyTextParser() {}

    /**
     * Builds a fresh {@link MutableComponent} from text containing legacy
     * {@code §}-formatting codes, detecting URLs and making them clickable, and
     * rendering PUA-encoded spoiler blocks as spoiler labels (see
     * {@link MessageBodyFormatter#formatMessageBody}).
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
                    MessageBodyFormatter.appendTextWithUrls(
                            parent, buffer.toString(), currentStyle);
                    buffer.setLength(0);
                }
                currentStyle =
                        applyLegacyCode(
                                baseStyle, currentStyle, Character.toLowerCase(text.charAt(i + 1)));
                i += 2;
                continue;
            }
            buffer.append(c);
            i++;
        }
        if (buffer.length() > 0) {
            MessageBodyFormatter.appendTextWithUrls(parent, buffer.toString(), currentStyle);
        }
        return parent;
    }

    /**
     * Apply a single legacy {@code §X} code to the accumulating style. Color
     * codes reset to {@code baseStyle} plus the new colour (vanilla clears the format
     * flags instead; the two agree when {@code baseStyle} carries none, as
     * {@code Style.EMPTY} does); format codes (k/l/m/n/o) add to whatever
     * {@code currentStyle} already has; {@code §r} resets to {@code baseStyle}.
     */
    private static Style applyLegacyCode(Style baseStyle, Style currentStyle, char code) {
        switch (code) {
            case '0':
                return baseStyle.withColor(ChatFormatting.BLACK);
            case '1':
                return baseStyle.withColor(ChatFormatting.DARK_BLUE);
            case '2':
                return baseStyle.withColor(ChatFormatting.DARK_GREEN);
            case '3':
                return baseStyle.withColor(ChatFormatting.DARK_AQUA);
            case '4':
                return baseStyle.withColor(ChatFormatting.DARK_RED);
            case '5':
                return baseStyle.withColor(ChatFormatting.DARK_PURPLE);
            case '6':
                return baseStyle.withColor(ChatFormatting.GOLD);
            case '7':
                return baseStyle.withColor(ChatFormatting.GRAY);
            case '8':
                return baseStyle.withColor(ChatFormatting.DARK_GRAY);
            case '9':
                return baseStyle.withColor(ChatFormatting.BLUE);
            case 'a':
                return baseStyle.withColor(ChatFormatting.GREEN);
            case 'b':
                return baseStyle.withColor(ChatFormatting.AQUA);
            case 'c':
                return baseStyle.withColor(ChatFormatting.RED);
            case 'd':
                return baseStyle.withColor(ChatFormatting.LIGHT_PURPLE);
            case 'e':
                return baseStyle.withColor(ChatFormatting.YELLOW);
            case 'f':
                return baseStyle.withColor(ChatFormatting.WHITE);
            case 'k':
                return currentStyle.withObfuscated(true);
            case 'l':
                return currentStyle.withBold(true);
            case 'm':
                return currentStyle.withStrikethrough(true);
            case 'n':
                return currentStyle.withUnderlined(true);
            case 'o':
                return currentStyle.withItalic(true);
            case 'r':
                return baseStyle;
            default:
                return currentStyle;
        }
    }
}
