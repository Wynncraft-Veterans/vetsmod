package org.wynnvets.rendering.colors;

/**
 * Colour constants used for text styling throughout VetsMod.
 *
 * <p>Includes both standard colours ({@link #AQUA}, {@link #DARK_AQUA}) used for
 * gradient pill rendering, and Wynncraft resource-pack shader sentinel colours
 * that trigger GPU-animated text effects when the server resource pack is active.</p>
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
    // Reference: Wynntils CommonColors / Wynncraft resource-pack shaders.

    /** Animated rainbow colour cycle. */
    public static final int RAINBOW = 0x00F000;

    /** Animated gradient from #f56217 → #0b486b. */
    public static final int GRADIENT = 0x00F004;

    /** Smooth animated fade from #5af082 → black. */
    public static final int FADE = 0x00F008;

    /** Animated blink between #e63232 and black. */
    public static final int BLINK = 0x00F00C;

    /** Animated gradient from #560505 → #8a0303. */
    public static final int GRADIENT_2 = 0x00F010;

    /** Animated shine from #a3cc52 → #ffffd2. */
    public static final int SHINE = 0x00F014;
}
