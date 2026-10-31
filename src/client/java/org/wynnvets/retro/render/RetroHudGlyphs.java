package org.wynnvets.retro.render;

import com.wynntils.core.text.StyledText;
import com.wynntils.core.text.StyledTextPart;
import com.wynntils.core.text.type.StyleType;
import java.util.BitSet;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * Wynncraft's HUD glyphs in the action bar: their font, and where the level and the health and mana
 * numbers are in the bar's text. Other fonts use the same code points, so a run of them means what
 * the HUD's glyphs mean only in this font. The finders are on plain strings, so unit-tested.
 */
final class RetroHudGlyphs {

    static final FontDescription FONT =
            new FontDescription.Resource(
                    Identifier.withDefaultNamespace("hud/gameplay/default/bottom_middle"));

    /**
     * The level: up to six digits and the spacers between them, in the spacers Wynntils'
     * {@code LevelSegmentMatcher} asks of it.
     */
    private static final Pattern LEVEL =
            Pattern.compile(
                    "[\\x{CFC00}-\\x{CFFFF}](?<digits>(?:[\\x{E000}-\\x{E009}]\\x{CFFFE}?+){1,6})"
                            + "[\\x{CFC00}-\\x{CFFFF}]");

    /**
     * A run of the health or mana number with the spacer on each side, as Wynntils'
     * {@code AbstractTextSegmentMatcher} takes it.
     */
    private static final Pattern NUMBER =
            Pattern.compile(".(?<glyphs>[\\x{E010}-\\x{E01F}\\x{CFFFF}\\x{D0002}]+).");

    /** The number's slash, which no other run in its spacers has. */
    private static final int NUMBER_SLASH = 0xE01F;

    private RetroHudGlyphs() {}

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

    /**
     * Where Wynncraft's level is in an action bar's text.
     *
     * @param actionBar the action bar, without formatting
     * @param inFont whether the character at an index of it is in the font
     * @return the first run in that font, spacers included, or empty when there is none
     */
    static Optional<MatchResult> findLevel(String actionBar, IntPredicate inFont) {
        Matcher level = LEVEL.matcher(actionBar);
        boolean found = level.find();
        while (found) {
            if (inFont.test(level.start("digits"))) return Optional.of(level.toMatchResult());
            // From its digits: the spacer closing this run may open the next.
            found = level.find(level.start("digits"));
        }
        return Optional.empty();
    }

    /**
     * Where Wynncraft's health or mana number is in an action bar's text. The two differ by their
     * spacers' signs: health's opens negative and closes positive, and mana's the reverse.
     *
     * @param actionBar the action bar, without formatting
     * @param inFont whether the character at an index of it is in the font
     * @param health whether to find health's number rather than mana's
     * @return the first such run with a slash in that font, spacers included, or empty when there
     *     is none
     */
    static Optional<MatchResult> findNumber(String actionBar, IntPredicate inFont, boolean health) {
        Matcher number = NUMBER.matcher(actionBar);
        while (number.find()) {
            String run = number.group();
            int opening = run.codePointAt(0);
            int closing = run.codePointBefore(run.length());
            boolean spacers =
                    health
                            ? isNegativeSpacer(opening) && isPositiveSpacer(closing)
                            : isPositiveSpacer(opening) && isNegativeSpacer(closing);
            if (spacers
                    && number.group("glyphs").codePoints().anyMatch(glyph -> glyph == NUMBER_SLASH)
                    && inFont.test(number.start("glyphs"))) {
                return Optional.of(number.toMatchResult());
            }
        }
        return Optional.empty();
    }

    private static boolean isNegativeSpacer(int codePoint) {
        return codePoint >= 0xCFC00 && codePoint <= 0xCFFFF;
    }

    private static boolean isPositiveSpacer(int codePoint) {
        return codePoint >= 0xD0000 && codePoint <= 0xD03FF;
    }

    /**
     * A number's glyphs, without the spacer on each side, which place it for Wynncraft's action
     * bar.
     *
     * @param segmentText the number's segment, spacers included
     * @return its glyphs and the spacers between them
     */
    static String numberGlyphs(String segmentText) {
        return segmentText.substring(
                segmentText.offsetByCodePoints(0, 1),
                segmentText.offsetByCodePoints(segmentText.length(), -1));
    }
}
