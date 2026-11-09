package org.wynnvets.retro.render;

import com.wynntils.core.text.StyledText;
import com.wynntils.handlers.actionbar.ActionBarSegment;
import com.wynntils.handlers.actionbar.ActionBarSegmentMatcher;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.MatchResult;

/**
 * Finds a piece of Wynncraft's HUD in the action bar where Wynntils' own matcher does not, so that
 * {@link RetroActionBar#hideWynncraftsBars} can take it out there too. Wynntils' matchers read no
 * fonts, so another font's glyphs on the same code points can hide what they look for; this looks
 * by the HUD's font ({@link RetroHudGlyphs}).
 *
 * <p>This answers only when Wynntils' matcher does not, so the two never mark the same text, which
 * Wynntils' handler would cut twice, and it stands down if Wynntils' matcher is fixed. A wrong
 * match of Wynntils' wins too: this does not look then. That handler calls every matcher in one
 * loop with no catch, whether or not retro is on, so a failure here is guarded: it would stop
 * Wynntils reading the action bar at all.</p>
 */
abstract class RetroSegmentMatcher implements ActionBarSegmentMatcher {

    /** Makes this matcher's segment from what it found. */
    interface SegmentFactory {
        ActionBarSegment create(String segmentText, int startIndex, int endIndex);
    }

    private final ActionBarSegmentMatcher wynntils;
    private final RetroGuard guard;
    private final SegmentFactory segments;

    /**
     * Stands in for one of Wynntils' matchers.
     *
     * @param wynntils the matcher to answer after
     * @param guard stops this matcher if it fails
     * @param segments makes the segment for a match
     */
    RetroSegmentMatcher(
            ActionBarSegmentMatcher wynntils, RetroGuard guard, SegmentFactory segments) {
        this.wynntils = wynntils;
        this.guard = guard;
        this.segments = segments;
    }

    @Override
    public final ActionBarSegment parse(StyledText actionBar) {
        return guard.call(() -> wynntils.parse(actionBar) != null ? null : find(actionBar), null);
    }

    private ActionBarSegment find(StyledText actionBar) {
        return find(
                        actionBar.getStringWithoutFormatting(),
                        RetroHudGlyphs.indicesIn(actionBar)::get)
                .map(match -> segments.create(match.group(), match.start(), match.end()))
                .orElse(null);
    }

    /**
     * Looks for the text Wynntils' matcher did not find.
     *
     * @param actionBar the action bar's text, without its formatting
     * @param inFont whether the character at an index is in the HUD's font
     * @return the match, or empty when there is none
     */
    abstract Optional<MatchResult> find(String actionBar, IntPredicate inFont);
}
