package org.wynnvets.rendering.colors;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.wynnvets.config.VetsConfig;

/**
 * A {@link FormattedCharSequence} wrapper that applies an animated, moving,
 * two-colour gradient to every character whose style carries the
 * {@link #MARKER_COLOR} or {@link #GREY_MARKER_COLOR} sentinel — the first from
 * the pair this instance was built with, the second always from
 * {@link #effectiveGreyStart()} / {@link #effectiveGreyEnd()}.  Characters
 * carrying neither (e.g. a chat badge prefix) pass through unmodified.
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
 * The companion {@link org.wynnvets.mixin.client.chat.AnimatedChatMixin AnimatedChatMixin} wraps
 * every newly inserted chat line with this class, built from {@link #effectiveDefaultStart()},
 * {@link #effectiveDefaultEnd()} and {@link #DEFAULT_CYCLE_TIME_MS}. It does not read the config
 * {@link #beginAnimation} sets (see {@code CURRENT_CONFIG}), so the colours passed in the example
 * above never reach the wrapper.
 */
public class AnimatedGradientSequence implements FormattedCharSequence {

    /**
     * Sentinel colour value used to mark characters that should be animated.
     * Characters with any other colour are passed through unchanged.
     */
    public static final int MARKER_COLOR = 0x00DEAD;

    public static final int GREY_MARKER_COLOR = 0x00DEAF;
    public static final int DEFAULT_START_COLOR = ShaderColorPalette.DARK_AQUA;
    public static final int DEFAULT_END_COLOR = 0xAADDFF;
    public static final int DEFAULT_GREY_START_COLOR = 0x888888;
    public static final int DEFAULT_GREY_END_COLOR = 0xBBBBBB;
    public static final int DEFAULT_CYCLE_TIME_MS = 3000;

    // CVD-friendly variants. The default cyan pair differs by ~5 units of
    // luminance (255 scale); for protan/deutan users the alternation
    // collapses to a single tone. Same cyan/blue family, ~90 units of
    // luminance delta — still subtle, but the shimmer is now perceptible.
    public static final int CV_DEFAULT_START_COLOR = 0x6699BB;
    public static final int CV_DEFAULT_END_COLOR = 0xDDF0FF;
    public static final int CV_GREY_START_COLOR = 0x666666;
    public static final int CV_GREY_END_COLOR = 0xCCCCCC;

    /** Start colour for the supporter shimmer, honouring {@code colorBlindMode}. */
    public static int effectiveDefaultStart() {
        return VetsConfig.get(VetsConfig.COLOR_BLIND_MODE)
                ? CV_DEFAULT_START_COLOR
                : DEFAULT_START_COLOR;
    }

    /** End colour for the supporter shimmer, honouring {@code colorBlindMode}. */
    public static int effectiveDefaultEnd() {
        return VetsConfig.get(VetsConfig.COLOR_BLIND_MODE)
                ? CV_DEFAULT_END_COLOR
                : DEFAULT_END_COLOR;
    }

    /** Start colour for the grey supporter shimmer {@code /wv list} gives supporters in its
     *  grey-coloured list, honouring {@code colorBlindMode}. */
    public static int effectiveGreyStart() {
        return VetsConfig.get(VetsConfig.COLOR_BLIND_MODE)
                ? CV_GREY_START_COLOR
                : DEFAULT_GREY_START_COLOR;
    }

    /** End colour for the grey supporter shimmer {@code /wv list} gives supporters in its
     *  grey-coloured list, honouring {@code colorBlindMode}. */
    public static int effectiveGreyEnd() {
        return VetsConfig.get(VetsConfig.COLOR_BLIND_MODE)
                ? CV_GREY_END_COLOR
                : DEFAULT_GREY_END_COLOR;
    }

    // ── Thread-local animation context ──────────────────────────────────

    /** Write-only by decision: set by {@link #beginAnimation} and cleared by
     *  {@link #endAnimation}, never read. The accessor that read it had no callers and was removed;
     *  the pair itself stays because {@link org.wynnvets.chat.ChatUtils#dispatchAnimatedChat
     *  ChatUtils#dispatchAnimatedChat} calls endAnimation() from the same finally block that
     *  restores INTERNAL_CHAT_DISPATCH, so collapsing them would strand that ThreadLocal true and
     *  make every later chat line look mod-generated. */
    private static final ThreadLocal<AnimConfig> CURRENT_CONFIG = new ThreadLocal<>();

    /** Sets the animation parameters for the current thread. */
    public static void beginAnimation(int startColor, int endColor, int cycleTimeMs) {
        CURRENT_CONFIG.set(new AnimConfig(startColor, endColor, cycleTimeMs));
    }

    /** Clears the thread-local animation context. */
    public static void endAnimation() {
        CURRENT_CONFIG.remove();
    }

    // ── Instance fields ─────────────────────────────────────────────────

    private final FormattedCharSequence delegate;
    private final int startColor;
    private final int endColor;
    private final int cycleTimeMs;

    /** Pre-computed count of marker characters (badge chars are excluded). */
    private final int animatedCharCount;

    public AnimatedGradientSequence(
            FormattedCharSequence delegate, int startColor, int endColor, int cycleTimeMs) {
        this.delegate = delegate;
        this.startColor = startColor;
        this.endColor = endColor;
        this.cycleTimeMs = cycleTimeMs;

        // Count animated characters once at construction time.
        int[] count = {0};
        delegate.accept(
                (index, style, cp) -> {
                    if (isAnyMarker(style)) count[0]++;
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

        return delegate.accept(
                (index, style, cp) -> {
                    if (isAnyMarker(style)) {
                        float charPhase =
                                animatedCharCount <= 1
                                        ? 0f
                                        : animIdx[0] / (float) (animatedCharCount - 1);

                        // Wave: the gradient slides across the text over time.
                        float phase = (charPhase + time) % 1.0f;
                        // Ping-pong so the gradient oscillates between the two colours.
                        float t = phase < 0.5f ? phase * 2.0f : 2.0f - phase * 2.0f;

                        int start, end;
                        if (isGreyMarker(style)) {
                            start = effectiveGreyStart();
                            end = effectiveGreyEnd();
                        } else {
                            start = startColor;
                            end = endColor;
                        }
                        int color = ColorMath.interpolateRgb(start, end, t);
                        style = style.withColor(TextColor.fromRgb(color));
                        animIdx[0]++;
                    }
                    return sink.accept(index, style, cp);
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean isAnyMarker(Style style) {
        TextColor color = style.getColor();
        return color != null
                && (color.getValue() == MARKER_COLOR || color.getValue() == GREY_MARKER_COLOR);
    }

    private static boolean isGreyMarker(Style style) {
        TextColor color = style.getColor();
        return color != null && color.getValue() == GREY_MARKER_COLOR;
    }

    // ── Config record ───────────────────────────────────────────────────

    public record AnimConfig(int startColor, int endColor, int cycleTimeMs) {}
}
