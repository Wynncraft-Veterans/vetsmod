package org.wynnvets.retro.render;

import com.wynntils.core.text.StyledText;
import com.wynntils.core.text.StyledTextPart;
import com.wynntils.core.text.type.StyleType;
import java.util.BitSet;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * The font of Wynncraft's HUD in the action bar. Other fonts use the same code points, so a run of
 * them means what the HUD's glyphs mean only in this font.
 */
final class RetroHudFont {

    static final FontDescription FONT =
            new FontDescription.Resource(
                    Identifier.withDefaultNamespace("hud/gameplay/default/bottom_middle"));

    private RetroHudFont() {}

    /**
     * Where the font is in an action bar.
     *
     * @param actionBar the action bar
     * @return the indices of its text, without formatting, that are in the font
     */
    static BitSet indicesIn(StyledText actionBar) {
        BitSet indices = new BitSet();
        int start = 0;
        for (StyledTextPart part : actionBar) {
            int end = start + part.getString(null, StyleType.NONE).length();
            if (FONT.equals(part.getPartStyle().getFont())) indices.set(start, end);
            start = end;
        }
        return indices;
    }
}
