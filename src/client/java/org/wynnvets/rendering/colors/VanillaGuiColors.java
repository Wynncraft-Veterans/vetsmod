package org.wynnvets.rendering.colors;

/**
 * The colours of vanilla's container screens that the mod draws in, as opaque ARGB
 * ({@code 0xAARRGGBB}) for {@code GuiGraphics}, read off 1.21.11's
 * {@code gui/container/inventory.png}.
 *
 * <p>Every constant has alpha {@code 0xFF}. {@code GuiGraphics.fill} and {@code drawString} skip
 * a colour whose alpha is 0, so an RGB value passed where they take ARGB draws nothing at all;
 * {@code VanillaGuiColorsTest} pins the alpha. They are not {@link ShaderColorPalette}'s, which
 * are RGB text colours, and they need no {@link ColorMath}.</p>
 *
 * <p>Constants only, so no {@code <clinit>} and no Minecraft class loads with this one.</p>
 */
public final class VanillaGuiColors {

    /** A slot's light bottom and right edge. */
    public static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;

    private VanillaGuiColors() {}
}
