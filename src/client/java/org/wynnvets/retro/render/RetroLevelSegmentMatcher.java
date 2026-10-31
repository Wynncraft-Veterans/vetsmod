package org.wynnvets.retro.render;

import com.wynntils.core.text.StyledText;
import com.wynntils.handlers.actionbar.ActionBarSegment;
import com.wynntils.models.characterstats.actionbar.matchers.LevelSegmentMatcher;

/**
 * Finds Wynncraft's level where Wynntils' {@link LevelSegmentMatcher} does not. That one tries only
 * the first run of the level's code points in the text, and gives up when the run lacks the level's
 * spacers, so a glyph ahead of the level hides it: the mount energy's from 38 of 48 energy, while
 * the player rides. This takes only a run in the level's font, where those code points are the
 * level's alone.
 */
public final class RetroLevelSegmentMatcher extends RetroSegmentMatcher {

    private static final RetroGuard GUARD =
            RetroGuard.stopping(
                    "finding the level in the action bar",
                    "Wynncraft's level may show over retro's until the game restarts");

    public RetroLevelSegmentMatcher() {
        super(new LevelSegmentMatcher(), GUARD);
    }

    @Override
    ActionBarSegment find(StyledText actionBar) {
        return RetroHudGlyphs.findLevel(
                        actionBar.getStringWithoutFormatting(),
                        RetroHudGlyphs.indicesIn(actionBar)::get)
                .map(level -> new Segment(level.group(), level.start(), level.end()))
                .orElse(null);
    }

    /** The level this matcher found, which Wynntils' models do not read. */
    static final class Segment extends ActionBarSegment {

        private Segment(String segmentText, int startIndex, int endIndex) {
            super(segmentText, startIndex, endIndex);
        }
    }
}
