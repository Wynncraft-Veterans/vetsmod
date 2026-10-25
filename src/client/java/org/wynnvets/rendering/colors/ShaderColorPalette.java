package org.wynnvets.rendering.colors;

/**
 * Colour constants for text styling.
 *
 * <p>{@link #DARK_AQUA} is the chat supporter shimmer's default start colour (via
 * {@link AnimatedGradientSequence#DEFAULT_START_COLOR}); {@link #AQUA} and the Wynncraft
 * resource-pack shader sentinel colours below have no reader today.</p>
 */
public final class ShaderColorPalette {
    private ShaderColorPalette() {}

    public static final int AQUA = 0xC7FFE5;
    public static final int DARK_AQUA = 0x55FFFF;

    // ── Wynncraft resource-pack shader sentinel colours ────────────────
    // The Wynncraft server resource-pack ships modified rendertype_text
    // fragment shaders that detect these specific colour values and replace
    // them with GPU-animated effects using the GameTime uniform.
    //
    // Simply setting a text component's colour to one of these values will
    // trigger the corresponding animation while the pack is active.
    //
    // Reference: Wynntils' WynncraftShaderColor enum (v4.1.17), which lists more sentinels
    // than this file, and the Wynncraft resource-pack shaders.

    /** Animated rainbow colour cycle. */
    public static final int RAINBOW = 0x00F000;

    /** Animated gradient from #f56217 → #0b486b. */
    public static final int GRADIENT = 0x00F004;

    /** Smooth animated fade from #5af082 (Wynntils describes the far end as transparent). */
    public static final int FADE = 0x00F008;

    /** Animated blink of #e63232 (Wynntils describes the other phase as transparent). */
    public static final int BLINK = 0x00F00C;

    /** Animated gradient from #560505 → #8a0303. */
    public static final int GRADIENT_2 = 0x00F010;

    /** Animated shine from #a3cc52 → #ffffd2. */
    public static final int SHINE = 0x00F014;
}
