package org.wynnvets.rendering.colors;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.wynnvets.chat.PillCodec;

/**
 * Utility for creating gradient-coloured text components.
 *
 * <p>Splits a string into individual characters, interpolating between a start
 * and end colour to produce a smooth linear gradient across the text.</p>
 */
public final class GradientTextBuilder {
    private GradientTextBuilder() {}

    /**
     * Creates a component whose characters are linearly interpolated between two RGB colours.
     *
     * @param text     the text to render
     * @param startRgb starting colour (0xRRGGBB)
     * @param endRgb   ending colour (0xRRGGBB)
     * @return a {@link MutableComponent} with per-code-point colouring
     */
    public static MutableComponent linear(String text, int startRgb, int endRgb) {
        return linear(text, startRgb, endRgb, Style.EMPTY);
    }

    /**
     * Creates a gradient component that preserves all properties of {@code baseStyle}
     * (e.g.&nbsp;font) while overriding the colour per code-point.
     *
     * @param text      the text to render
     * @param startRgb  starting colour (0xRRGGBB)
     * @param endRgb    ending colour (0xRRGGBB)
     * @param baseStyle the style whose non-colour properties are preserved on every character
     * @return a {@link MutableComponent} with per-code-point colouring
     */
    public static MutableComponent linear(String text, int startRgb, int endRgb, Style baseStyle) {
        MutableComponent component = Component.empty();

        if (text == null || text.isEmpty()) {
            return component;
        }

        int cpCount = text.codePointCount(0, text.length());

        if (cpCount == 1) {
            return Component.literal(text)
                    .setStyle(baseStyle.withColor(TextColor.fromRgb(startRgb)));
        }

        int charIndex = 0;
        int cpIndex = 0;
        while (charIndex < text.length()) {
            int cp = text.codePointAt(charIndex);

            if (PillCodec.isCustomGlyph(cp)) {
                // A run of custom font glyphs must stay in one text run so the
                // resource-pack font can render it as composite badge art;
                // colouring per character would break the composite.
                int groupCharStart = charIndex;
                int groupCpStart = cpIndex;
                while (charIndex < text.length()) {
                    cp = text.codePointAt(charIndex);
                    if (!PillCodec.isCustomGlyph(cp)) {
                        break;
                    }
                    charIndex += Character.charCount(cp);
                    cpIndex++;
                }
                // Colour the whole group at its positional midpoint.
                float t = ((groupCpStart + cpIndex - 1) / 2.0f) / (cpCount - 1);
                int rgb = interpolateRgb(startRgb, endRgb, t);
                String groupStr = text.substring(groupCharStart, charIndex);
                component.append(
                        Component.literal(groupStr)
                                .setStyle(
                                        baseStyle
                                                .withColor(TextColor.fromRgb(rgb))
                                                .withoutShadow()));
            } else {
                float t = cpIndex / (float) (cpCount - 1);
                int rgb = interpolateRgb(startRgb, endRgb, t);
                String cpStr = new String(Character.toChars(cp));
                component.append(
                        Component.literal(cpStr)
                                .setStyle(baseStyle.withColor(TextColor.fromRgb(rgb))));
                charIndex += Character.charCount(cp);
                cpIndex++;
            }
        }

        return component;
    }

    // Package-private for unit tests. See ColorLerpTest.
    static int interpolateRgb(int startRgb, int endRgb, float t) {
        int startR = (startRgb >> 16) & 0xFF;
        int startG = (startRgb >> 8) & 0xFF;
        int startB = startRgb & 0xFF;

        int endR = (endRgb >> 16) & 0xFF;
        int endG = (endRgb >> 8) & 0xFF;
        int endB = endRgb & 0xFF;

        int r = Math.round(startR + (endR - startR) * t);
        int g = Math.round(startG + (endG - startG) * t);
        int b = Math.round(startB + (endB - startB) * t);

        return (r << 16) | (g << 8) | b;
    }
}
