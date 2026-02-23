package org.wynnvets.util.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.util.SupportersFetcher;
import org.wynnvets.util.colors.AnimatedGradientSequence;
import org.wynnvets.util.colors.GradientTextBuilder;
import org.wynnvets.util.colors.ShaderColorPalette;

/**
 * Reusable system for formatting guild chat pill (rank) components.
 *
 * <p>Determines the appropriate pill styling based on the message sender's username.
 * Supporters receive a gradient pill ({@link ShaderColorPalette#AQUA AQUA} →
 * {@link ShaderColorPalette#DARK_AQUA DARK_AQUA}) for ASCII pills (bridge messages),
 * or a single supporter colour for PUA pills (server messages where the
 * {@code chat/prefix} font renders composite badge glyphs).</p>
 *
 * <p>To add new pill styles, add additional checks before the default fallback in
 * {@link #formatPill(String, String, Style)}.</p>
 */
public final class PillFormatter {

    private PillFormatter() {
    }

    /**
     * Formats a pill component with the default style rules.
     * Uses {@link ChatUtils#RANK_STYLE} as the base style.
     *
     * @param pillText the text to display in the pill (e.g. rank name)
     * @param username the display name of the message sender
     * @return a styled pill component
     */
    public static MutableComponent formatPill(String pillText, String username) {
        return formatPill(pillText, username, ChatUtils.RANK_STYLE);
    }

    /**
     * Formats a pill component using the given base style.
     *
     * <p>If the username matches a supporter, the pill receives a gradient between
     * {@link ShaderColorPalette#AQUA} and {@link ShaderColorPalette#DARK_AQUA}
     * for plain-text pills (bridge messages).  For PUA-based pills (server messages)
     * the entire pill is rendered as a single component with one supporter colour,
     * because the {@code chat/prefix} font requires specific colour patterning and
     * per-character gradient colours break composite glyph rendering.</p>
     *
     * @param pillText  the text to display in the pill
     * @param username  the display name of the message sender
     * @param baseStyle the fallback style when no special styling applies
     * @return a styled pill component
     */
    public static MutableComponent formatPill(String pillText, String username, Style baseStyle) {
        // ── Supporter ──────────────────────────────────────────────────
        if (SupportersFetcher.isSupporter(username)) {
            if (containsCustomFontGlyph(pillText)) {
                // PUA/supplementary pills from the server: the chat/prefix font uses
                // a two-tone colour structure (aqua frame + dark letters) baked into
                // specific codepoints.  Applying a per-character gradient destroys
                // this structure.  Instead, render the entire pill as a single
                // component with the animation marker so the mixin can animate it.
                return Component.literal(pillText)
                        .setStyle(baseStyle.withColor(
                                TextColor.fromRgb(AnimatedGradientSequence.MARKER_COLOR)));
            }

            // ASCII pills (bridge messages) — per-character marker is safe.
            // Each character gets the marker colour; the AnimatedChatMixin will
            // replace it with animated gradient colours at render time.
            return GradientTextBuilder.linear(
                    pillText,
                    AnimatedGradientSequence.MARKER_COLOR,
                    AnimatedGradientSequence.MARKER_COLOR,
                    baseStyle);
        }

        // ── Default → flat style ──────────────────────────────────────
        return Component.literal(pillText).setStyle(baseStyle);
    }

    /**
     * Returns {@code true} if the given username is a supporter and should
     * therefore receive animated pill styling.
     */
    public static boolean isSupporterPill(String username) {
        return SupportersFetcher.isSupporter(username);
    }

    /**
     * Returns {@code true} if the text contains any codepoint that is rendered by
     * a custom resource-pack font (Private Use Area or unassigned supplementary
     * plane characters used by Wynncraft's {@code chat/prefix} font).
     */
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
}
