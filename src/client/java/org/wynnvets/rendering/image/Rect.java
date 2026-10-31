package org.wynnvets.rendering.image;

/**
 * A rectangle of pixels.
 *
 * @param x the left column
 * @param y the top row
 * @param width the width, at least 1
 * @param height the height, at least 1
 */
public record Rect(int x, int y, int width, int height) {

    /**
     * Whether {@code other} lies wholly inside this rectangle.
     *
     * @param other the rectangle
     * @return whether every pixel of {@code other} is one of this rectangle's
     */
    public boolean contains(Rect other) {
        return other.x >= x
                && other.y >= y
                && other.x + other.width <= x + width
                && other.y + other.height <= y + height;
    }

    /**
     * The rectangle as a YAML flow list.
     *
     * @return {@code [x, y, width, height]}
     */
    public String toYaml() {
        return "[" + x + ", " + y + ", " + width + ", " + height + "]";
    }
}
