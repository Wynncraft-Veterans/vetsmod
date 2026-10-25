package org.wynnvets.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LegacyTextParser#literalWithUrls}: goldens for every colour and format code, an
 * unknown and an upper-case code, a colour code after a format code, {@code §r} after codes that
 * changed the style, and a section sign with no code after it.
 *
 * <p>Each golden is the result's {@code Component.toString()}, which spells out every segment's
 * text, colour, flags and click event. The string form is Minecraft 1.21.11's, so a Minecraft
 * update that changes {@code Component.toString()} will need the goldens regenerated.</p>
 *
 * <p>Every input is codes and a URL only. Text that is not a URL goes to
 * {@code SpoilerFormatter.appendWithSpoilers}, which reads {@code VetsConfig}, and
 * {@code VetsConfig} cannot initialise in a test; so plain text, and any spoiler in it, is not
 * covered here. The bytecode proof and the in-game pass cover it.</p>
 */
class LegacyTextParserTest {

    private static final String URL = "https://a.b/";
    private static final Style BASE = Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(true);

    private static String parse(String text, Style base) {
        return LegacyTextParser.literalWithUrls(text, base).toString();
    }

    /**
     * The golden for {@link #URL} alone, linked, in a style whose other fields are {@code style}.
     */
    private static String linked(String style) {
        return "empty[siblings=[literal{https://a.b/}[style={"
                + (style.isEmpty() ? "" : style + ",")
                + "clickEvent=OpenUrl[uri=https://a.b/]}]]]";
    }

    @Test
    void literalWithUrls_empty_isAnEmptyComponent() {
        assertEquals("empty", parse("", Style.EMPTY));
    }

    @Test
    void literalWithUrls_null_throws() {
        assertThrows(NullPointerException.class, () -> parse(null, Style.EMPTY));
    }

    @Test
    void literalWithUrls_eachColourCode_setsItsColour() {
        String codes = "0123456789abcdef";
        String[] colours = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
                    "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
        };
        for (int i = 0; i < codes.length(); i++) {
            assertEquals(
                    linked("color=" + colours[i]),
                    parse("\u00A7" + codes.charAt(i) + URL, Style.EMPTY),
                    "code " + codes.charAt(i));
        }
    }

    @Test
    void literalWithUrls_eachFormatCode_setsItsFlag() {
        String codes = "klmno";
        String[] flags = {"obfuscated", "bold", "strikethrough", "underlined", "italic"};
        for (int i = 0; i < codes.length(); i++) {
            assertEquals(
                    linked(flags[i]),
                    parse("\u00A7" + codes.charAt(i) + URL, Style.EMPTY),
                    "code " + codes.charAt(i));
        }
        // a format code adds to the style so far, not to the base style
        assertEquals(linked("color=green,bold"), parse("\u00A7a\u00A7l" + URL, Style.EMPTY));
    }

    @Test
    void literalWithUrls_unknownCode_isDroppedAndKeepsTheStyle() {
        assertEquals(
                "empty[siblings=[literal{https://a.b/}[style={clickEvent=OpenUrl[uri=https://a.b/]}"
                        + "]]]",
                parse("\u00A7z" + URL, Style.EMPTY));
        // the style so far, not the base style
        assertEquals(linked("color=green"), parse("\u00A7a\u00A7z" + URL, Style.EMPTY));
    }

    @Test
    void literalWithUrls_upperCaseCode_actsAsLowerCase() {
        assertEquals(
                "empty[siblings=[literal{https://a.b/}[style={color=green,"
                        + "clickEvent=OpenUrl[uri=https://a.b/]}]]]",
                parse("\u00A7A" + URL, Style.EMPTY));
    }

    @Test
    void literalWithUrls_colourCodeAfterAFormatCode_resetsToTheBaseStylePlusTheColour() {
        assertEquals(
                "empty[siblings=[literal{https://a.b/}[style={color=green,"
                        + "clickEvent=OpenUrl[uri=https://a.b/]}]]]",
                parse("\u00A7l\u00A7a" + URL, Style.EMPTY));
        assertEquals(
                "empty[siblings=[literal{https://a.b/}[style={color=green,italic,"
                        + "clickEvent=OpenUrl[uri=https://a.b/]}]]]",
                parse("\u00A7l\u00A7a" + URL, BASE));
    }

    @Test
    void literalWithUrls_resetAfterCodesThatChangedTheStyle_returnsToTheBaseStyle() {
        assertEquals(
                "empty[siblings=[literal{https://a.b/}[style={clickEvent=OpenUrl[uri=https://a.b/]}"
                        + "]]]",
                parse("\u00A7a\u00A7l\u00A7r" + URL, Style.EMPTY));
        assertEquals(
                "empty[siblings=[literal{https://a.b/}[style={color=gold,italic,"
                        + "clickEvent=OpenUrl[uri=https://a.b/]}]]]",
                parse("\u00A7a\u00A7l\u00A7r" + URL, BASE));
    }

    @Test
    void literalWithUrls_sectionSignWithNoCodeAfterIt_staysInTheText() {
        assertEquals(
                "empty[siblings=[literal{https://a.b/\u00A7}[style={clickEvent=OpenUrl[uri=https://"
                        + "a.b/\u00A7]}]]]",
                parse(URL + "\u00A7", Style.EMPTY));
    }
}
