package org.wynnvets.rendering.colors;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.awt.Color;

/**
 * Wynntils-style text animations for version status messages.
 *
 * <p>Provides rainbow (perfect) and obfuscated-red (defective) text effects
 * matching the animations Wynntils uses for perfect and defective gear items
 * shown in chat.</p>
 */
public final class VersionAnimations {

    private static final int RAINBOW_CYCLE_TIME = 5000;

    private VersionAnimations() {
    }

    /**
     * Creates a rainbow-animated component matching Wynntils' perfect item style.
     * Each character cycles through HSB colour space with a wave offset, bold.
     *
     * <p>Because this is evaluated once at message creation time rather than
     * per-frame, the colour is a snapshot — but Minecraft re-evaluates the
     * component on each render frame if the chat line is still visible.</p>
     *
     * @param text the text to render with rainbow animation
     * @return a bold, per-character rainbow-coloured component
     */
    public static MutableComponent makeRainbow(String text) {
        MutableComponent result = Component.literal("").withStyle(ChatFormatting.BOLD);

        int time = (int) (System.currentTimeMillis() % RAINBOW_CYCLE_TIME);
        for (int i = 0; i < text.length(); i++) {
            int hue = (time + i * RAINBOW_CYCLE_TIME / 7) % RAINBOW_CYCLE_TIME;
            Style color = Style.EMPTY
                    .withColor(Color.HSBtoRGB(hue / (float) RAINBOW_CYCLE_TIME, 0.8F, 0.8F))
                    .withItalic(false);

            result.append(Component.literal(String.valueOf(text.charAt(i))).setStyle(color));
        }

        return result;
    }

    /**
     * Creates an obfuscated-red component matching Wynntils' defective item style.
     * Random segments of text are obfuscated, with probability interpolated across
     * the string length. Bold dark-red base style.
     *
     * @param text                  the text to render with defective animation
     * @param obfuscationChanceStart probability (0–1) of obfuscation at the start
     * @param obfuscationChanceEnd   probability (0–1) of obfuscation at the end
     * @return a bold dark-red component with randomly obfuscated segments
     */
    public static MutableComponent makeObfuscated(String text, float obfuscationChanceStart, float obfuscationChanceEnd) {
        MutableComponent result = Component.literal("").withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);

        if (text.isEmpty()) {
            return result;
        }

        boolean obfuscated = Math.random() < obfuscationChanceStart;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length() - 1; i++) {
            current.append(text.charAt(i));

            float chance = lerp(obfuscationChanceStart, obfuscationChanceEnd,
                    (i + 1) / (float) (text.length() - 1));

            if (!obfuscated && Math.random() < chance) {
                result.append(Component.literal(current.toString()));
                current = new StringBuilder();
                obfuscated = true;
            } else if (obfuscated && Math.random() > chance) {
                result.append(Component.literal(current.toString()).withStyle(Style.EMPTY.withObfuscated(true)));
                current = new StringBuilder();
                obfuscated = false;
            }
        }

        current.append(text.charAt(text.length() - 1));

        if (obfuscated) {
            result.append(Component.literal(current.toString()).withStyle(Style.EMPTY.withObfuscated(true)));
        } else {
            result.append(Component.literal(current.toString()));
        }

        return result;
    }

    private static float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }
}
