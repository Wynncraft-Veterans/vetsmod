package org.wynnvets.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.rendering.colors.AnimatedGradientSequence;
import org.wynnvets.rendering.colors.GradientTextBuilder;
import org.wynnvets.rendering.colors.ShaderColorPalette;

/**
 * Reusable system for formatting guild chat pill (rank) components.
 *
 * <p>Styles the pill from the caller's supporter determination; the sender's username is not
 * read. For a supporter, while {@code showSupporterGlints} is on, the pill takes the animation
 * marker colour, animated at render time from {@link ShaderColorPalette#DARK_AQUA DARK_AQUA} by
 * default (see {@link AnimatedGradientSequence}). A pill containing custom glyphs, which is what
 * every current caller passes, is marked as one component; a plain-text label would be marked per
 * character. Otherwise the pill takes the flat base style. In every branch it renders in the
 * default font, not {@code chat/prefix} (see {@link PillCodec}).</p>
 *
 * <p>To add new pill styles, add additional checks before the default fallback in
 * {@link #formatPill(String, String, Style, boolean)}.</p>
 */
public final class PillFormatter {

    private PillFormatter() {}

    /**
     * Formats a pill component using a caller-provided supporter determination.
     */
    public static MutableComponent formatPill(
            String pillText, String username, boolean isSupporter) {
        return formatPill(pillText, username, ChatUtils.RANK_STYLE, isSupporter);
    }

    /**
     * Formats a pill component using the given base style.
     *
     * <p>If the sender is a supporter and {@code showSupporterGlints} is on, the pill takes the
     * animation marker colour, animated at render time from {@link ShaderColorPalette#DARK_AQUA}
     * by default. A plain-text label, which no current caller passes, is marked per character. The
     * PUA pill every current caller passes is marked as one component instead, because a
     * per-character gradient would break the composite glyphs. In every branch the pill renders in
     * the default font, not {@code chat/prefix}.</p>
     *
     * @param pillText    the text to display in the pill
     * @param username    the display name of the message sender
     * @param baseStyle   the fallback style when no special styling applies
     * @param isSupporter whether the sender is a supporter
     * @return a styled pill component
     */
    public static MutableComponent formatPill(
            String pillText, String username, Style baseStyle, boolean isSupporter) {
        // ── Supporter ──────────────────────────────────────────────────
        // Only apply gradient styling when the user has supporter glints enabled.
        if (isSupporter
                && org.wynnvets.config.VetsConfig.get(
                        org.wynnvets.config.VetsConfig.SHOW_SUPPORTER_GLINTS)) {
            if (containsCustomFontGlyph(pillText)) {
                // PUA pills (vetsmod's own encoded pill, which is every current caller's input)
                // render in the default font: the frame and letter glyphs are baked into specific
                // codepoints, so a per-character gradient would break them. Instead, render the
                // entire pill as a single component with the animation marker so the mixin can
                // animate it.
                return Component.literal(pillText)
                        .setStyle(
                                baseStyle
                                        .withColor(
                                                TextColor.fromRgb(
                                                        AnimatedGradientSequence.MARKER_COLOR))
                                        .withoutShadow());
            }

            // Plain-text pill, unreachable from any current caller: per-character marker is safe.
            // Each character gets the marker colour; the AnimatedChatMixin will
            // replace it with animated gradient colours at render time.
            return GradientTextBuilder.linear(
                    pillText,
                    AnimatedGradientSequence.MARKER_COLOR,
                    AnimatedGradientSequence.MARKER_COLOR,
                    baseStyle);
        }

        // ── Default → flat style ──────────────────────────────────────
        if (containsCustomFontGlyph(pillText)) {
            return Component.literal(pillText).setStyle(baseStyle.withoutShadow());
        }
        return Component.literal(pillText).setStyle(baseStyle);
    }

    /**
     * Returns {@code true} if the text contains any codepoint that
     * {@link PillCodec#isCustomGlyph(int)} classifies as resource-pack glyph art
     * rather than as text.
     */
    private static boolean containsCustomFontGlyph(String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (PillCodec.isCustomGlyph(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }
}
