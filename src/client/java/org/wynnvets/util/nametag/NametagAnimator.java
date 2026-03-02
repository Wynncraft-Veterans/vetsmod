package org.wynnvets.util.nametag;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies a subtle animated gradient to the username portion of a supporter's
 * in-game nametag.  The animation mirrors the ping-pong wave used by
 * {@link org.wynnvets.util.colors.AnimatedGradientSequence} for the chat
 * contributor glint.
 *
 * <p>Wynncraft nametags follow the format:
 * {@code [colour1][symbol] [colour2][prefix] username},
 * where the symbol and spacing may use Private Use Area characters and
 * surrogate-pair alignment glyphs.  Rather than parsing this complex format,
 * we receive the authoritative username from the mixin (via GameProfile)
 * and locate its occurrence in the nametag text.</p>
 *
 * <p>Called every render frame from {@code NametagMixin}; because nametag
 * components are rebuilt from the render state each frame the time-based
 * colour calculation produces smooth animation without an external tick loop.</p>
 */
public final class NametagAnimator {

    /** Duration of one full ping-pong cycle (ms).  Matches the chat glint. */
    private static final int CYCLE_TIME_MS = 3000;

    /**
     * How far to blend the original colour toward white when computing the
     * animation end-colour.  0.5 produces a clearly visible lightening that
     * works across all nametag colours (aqua, blue, green, yellow, etc.).
     */
    private static final float LIGHTEN_FACTOR = 0.5f;

    /** Fallback colour when the username has no explicit style colour.
     *  Minecraft's default text colour (white / light grey). */
    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFF;

    private NametagAnimator() {}

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns an animated copy of the nametag {@link Component} with the
     * username characters coloured using a time-based gradient.
     * The caller is responsible for the supporter check.
     *
     * @param nameTag  the player's current nametag component
     * @param username the player's real username (from GameProfile)
     * @return an animated replacement, or {@code null} if the username
     *         cannot be located in the nametag text
     */
    public static Component tryAnimate(Component nameTag, String username) {
        if (nameTag == null || username == null || username.isEmpty()) return null;

        String fullText = nameTag.getString();

        // Find the username in the nametag text (case-insensitive because
        // Wynncraft may capitalise differently from the GameProfile).
        int usernameStart = indexOfIgnoreCase(fullText, username);
        if (usernameStart < 0) return null;

        return buildAnimated(nameTag, usernameStart, username.length());
    }

    // ── Animated component builder ──────────────────────────────────────

    private static Component buildAnimated(Component original, int usernameStart, int usernameLen) {
        List<StyledSegment> segments = collectSegments(original);
        if (segments.isEmpty()) return original;

        int usernameEnd = usernameStart + usernameLen;

        // Resolve the original colour of the first username character.
        int origColor = resolveColorAt(segments, usernameStart);
        int lightenedColor = lighten(origColor);

        // Time-based phase — identical maths to AnimatedGradientSequence.
        float time = (System.currentTimeMillis() % CYCLE_TIME_MS) / (float) CYCLE_TIME_MS;

        // Rebuild the component, animating only the username characters.
        MutableComponent result = Component.empty();
        int cursor = 0;

        for (StyledSegment seg : segments) {
            int segStart = cursor;
            int segEnd   = cursor + seg.text.length();

            if (segEnd <= usernameStart || segStart >= usernameEnd) {
                // Entirely outside username — keep unchanged.
                result.append(Component.literal(seg.text).setStyle(seg.style));
            } else if (segStart >= usernameStart && segEnd <= usernameEnd) {
                // Entirely within username — animate each character.
                appendAnimatedChars(result, seg, segStart - usernameStart,
                        usernameLen, origColor, lightenedColor, time);
            } else {
                // Segment spans a boundary — split into up to 3 parts.
                int animStart = Math.max(segStart, usernameStart);
                int animEnd   = Math.min(segEnd, usernameEnd);

                // Part before animated region
                if (animStart > segStart) {
                    result.append(Component.literal(
                            seg.text.substring(0, animStart - segStart))
                            .setStyle(seg.style));
                }

                // Animated part
                StyledSegment animSeg = new StyledSegment(
                        seg.text.substring(animStart - segStart, animEnd - segStart),
                        seg.style);
                appendAnimatedChars(result, animSeg,
                        animStart - usernameStart, usernameLen,
                        origColor, lightenedColor, time);

                // Part after animated region
                if (animEnd < segEnd) {
                    result.append(Component.literal(
                            seg.text.substring(animEnd - segStart))
                            .setStyle(seg.style));
                }
            }

            cursor = segEnd;
        }

        return result;
    }

    /**
     * Appends per-character animated text to {@code target}, using the same
     * ping-pong wave as the chat contributor glint.
     */
    private static void appendAnimatedChars(
            MutableComponent target,
            StyledSegment seg,
            int startIdx,
            int usernameLen,
            int startColor,
            int endColor,
            float time) {

        for (int i = 0; i < seg.text.length(); i++) {
            int idx = startIdx + i;
            float charPhase = usernameLen <= 1
                    ? 0f
                    : idx / (float) (usernameLen - 1);

            // Sliding wave — same formula as AnimatedGradientSequence.
            float phase = (charPhase + time) % 1.0f;
            float t = phase < 0.5f ? phase * 2.0f : 2.0f - phase * 2.0f;

            int color = interpolateColor(startColor, endColor, t);
            target.append(
                    Component.literal(String.valueOf(seg.text.charAt(i)))
                            .setStyle(seg.style.withColor(TextColor.fromRgb(color))));
        }
    }

    // ── Component traversal ─────────────────────────────────────────────

    /**
     * Walks the component tree and collects every (resolved-style, text) pair
     * in visual order.
     */
    private static List<StyledSegment> collectSegments(Component component) {
        List<StyledSegment> segments = new ArrayList<>();
        component.visit((Style style, String text) -> {
            if (!text.isEmpty()) {
                segments.add(new StyledSegment(text, style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return segments;
    }

    // ── String utilities ────────────────────────────────────────────────

    /**
     * Case-insensitive indexOf, searching from the END so that the last
     * occurrence is returned (the username is always at the tail of the
     * nametag format).
     */
    private static int indexOfIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) return -1;
        String lowerHay = haystack.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        return lowerHay.lastIndexOf(lowerNeedle);
    }

    // ── Colour utilities ────────────────────────────────────────────────

    /**
     * Returns the RGB value of the style at a given flat character index,
     * falling back to {@link #DEFAULT_TEXT_COLOR} if no colour is set.
     */
    private static int resolveColorAt(List<StyledSegment> segments, int charIndex) {
        int cursor = 0;
        for (StyledSegment seg : segments) {
            int segEnd = cursor + seg.text.length();
            if (charIndex < segEnd) {
                TextColor tc = seg.style.getColor();
                return tc != null ? tc.getValue() : DEFAULT_TEXT_COLOR;
            }
            cursor = segEnd;
        }
        return DEFAULT_TEXT_COLOR;
    }

    /**
     * Lightens a colour by blending it {@value #LIGHTEN_FACTOR} of the way
     * toward pure white.  Works across all hues — aqua, blue, green, yellow,
     * etc. — producing a clearly visible but on-hue brightness shift.
     *
     * <p>Examples at 50% blend:</p>
     * <ul>
     *   <li>Aqua   {@code 0x55FFFF} → {@code 0xAAFFFF}</li>
     *   <li>Blue   {@code 0x5555FF} → {@code 0xAAAAFF}</li>
     *   <li>Green  {@code 0x55FF55} → {@code 0xAAFFAA}</li>
     *   <li>Yellow {@code 0xFFFF55} → {@code 0xFFFFAA}</li>
     *   <li>White  {@code 0xFFFFFF} → {@code 0xFFFFFF} (no change)</li>
     * </ul>
     */
    private static int lighten(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >>  8) & 0xFF;
        int b =  rgb        & 0xFF;

        int lr = Math.round(r + LIGHTEN_FACTOR * (255 - r));
        int lg = Math.round(g + LIGHTEN_FACTOR * (255 - g));
        int lb = Math.round(b + LIGHTEN_FACTOR * (255 - b));
        return (lr << 16) | (lg << 8) | lb;
    }

    /**
     * Linearly interpolates between two RGB colours.
     */
    static int interpolateColor(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r  = Math.round(r1 + (r2 - r1) * t);
        int g  = Math.round(g1 + (g2 - g1) * t);
        int bl = Math.round(b1 + (b2 - b1) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ── Inner types ─────────────────────────────────────────────────────

    private record StyledSegment(String text, Style style) {}
}
