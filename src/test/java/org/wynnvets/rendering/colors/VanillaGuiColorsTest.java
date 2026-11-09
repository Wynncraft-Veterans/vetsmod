package org.wynnvets.rendering.colors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VanillaGuiColors}: every colour is opaque ARGB, since {@code GuiGraphics} skips
 * a fill or string whose alpha is 0. The constants are found by reflection, so a colour added
 * later is checked too.
 */
class VanillaGuiColorsTest {

    @Test
    void everyColourIsOpaque() throws IllegalAccessException {
        List<String> colours = new ArrayList<>();
        for (Field field : VanillaGuiColors.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class
                    || !Modifier.isPublic(modifiers)
                    || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers)) {
                continue;
            }
            colours.add(field.getName());
            assertEquals(0xFF, field.getInt(null) >>> 24, field.getName());
        }
        assertFalse(colours.isEmpty(), "no colour was found to check");
    }
}
