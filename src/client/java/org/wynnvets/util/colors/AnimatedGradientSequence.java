package org.wynnvets.util.colors;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

/**
 * A {@link FormattedCharSequence} wrapper that applies an animated, moving,
 * two-colour gradient to every character whose style carries the
 * {@link #MARKER_COLOR} sentinel.  Non-marked characters (e.g. a chat badge
 * prefix) pass through unmodified.
 *
 * <p>Because {@code accept()} is invoked by the font renderer on every frame,
 * the gradient shifts smoothly over time without any external tick loop.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * AnimatedGradientSequence.beginAnimation(0x55FFFF, 0x88FFE9, 3000);
 * try {
 *     ChatUtils.sendLocalMessage(
 *         Component.literal("hello")
 *             .withColor(AnimatedGradientSequence.MARKER_COLOR)
 *             .withStyle(ChatFormatting.BOLD));
 * } finally {
 *     AnimatedGradientSequence.endAnimation();
 * }
 * }</pre>
 *
 * The companion {@code AnimatedChatMixin} detects the pending config and wraps
 * the stored {@code FormattedCharSequence} with this class.
 */
public class AnimatedGradientSequence implements FormattedCharSequence {

    /**
     * Sentinel colour value used to mark characters that should be animated.
     * Characters with any other colour are passed through unchanged.
     */
    public static final int MARKER_COLOR = 0x00DEAD;
    public static final int DEFAULT_START_COLOR = ShaderColorPalette.DARK_AQUA;
    public static final int DEFAULT_END_COLOR = 0xAADDFF;
    public static final int DEFAULT_CYCLE_TIME_MS = 3000;

    // ── Thread-local animation context ──────────────────────────────────

    private static final ThreadLocal<AnimConfig> CURRENT_CONFIG = new ThreadLocal<>();

    /** Sets the animation parameters for the current thread. */
    public static void beginAnimation(int startColor, int endColor, int cycleTimeMs) {
        CURRENT_CONFIG.set(new AnimConfig(startColor, endColor, cycleTimeMs));
    }

    /** Clears the thread-local animation context. */
    public static void endAnimation() {
        CURRENT_CONFIG.remove();
    }

    /** Returns the animation config for the current thread, or {@code null}. */
    public static AnimConfig currentConfig() {
        return CURRENT_CONFIG.get();
    }

    // ── Instance fields ─────────────────────────────────────────────────

    private final FormattedCharSequence delegate;
    private final int startColor;
    private final int endColor;
    private final int cycleTimeMs;
    /** Pre-computed count of marker characters (badge chars are excluded). */
    private final int animatedCharCount;

    public AnimatedGradientSequence(
            FormattedCharSequence delegate,
            int startColor,
            int endColor,
            int cycleTimeMs) {
        this.delegate = delegate;
        this.startColor = startColor;
        this.endColor = endColor;
        this.cycleTimeMs = cycleTimeMs;

        // Count animated characters once at construction time.
        int[] count = {0};
        delegate.accept((index, style, cp) -> {
            if (isMarker(style)) count[0]++;
            return true;
        });
        this.animatedCharCount = count[0];
    }

    // ── FormattedCharSequence ───────────────────────────────────────────

    @Override
    public boolean accept(FormattedCharSink sink) {
        if (animatedCharCount == 0) {
            return delegate.accept(sink);
        }

        float time = (System.currentTimeMillis() % cycleTimeMs) / (float) cycleTimeMs;
        int[] animIdx = {0};

        return delegate.accept((index, style, cp) -> {
            if (isMarker(style)) {
                float charPhase = animatedCharCount <= 1
                        ? 0f
                        : animIdx[0] / (float) (animatedCharCount - 1);

                // Wave: the gradient slides across the text over time.
                float phase = (charPhase + time) % 1.0f;
                // Ping-pong so the gradient oscillates between the two colours.
                float t = phase < 0.5f ? phase * 2.0f : 2.0f - phase * 2.0f;

                int color = interpolateColor(startColor, endColor, t);
                style = style.withColor(TextColor.fromRgb(color));
                animIdx[0]++;
            }
            return sink.accept(index, style, cp);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isMarker(Style style) {
        TextColor color = style.getColor();
        return color != null && color.getValue() == MARKER_COLOR;
    }

    private static int interpolateColor(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = Math.round(r1 + (r2 - r1) * t);
        int g = Math.round(g1 + (g2 - g1) * t);
        int b = Math.round(b1 + (b2 - b1) * t);
        return (r << 16) | (g << 8) | b;
    }

    // ── Config record ───────────────────────────────────────────────────

    public record AnimConfig(int startColor, int endColor, int cycleTimeMs) {}
}
