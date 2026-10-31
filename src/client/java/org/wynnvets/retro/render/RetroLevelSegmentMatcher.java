package org.wynnvets.retro.render;

import com.wynntils.core.text.StyledText;
import com.wynntils.handlers.actionbar.ActionBarSegment;
import com.wynntils.handlers.actionbar.ActionBarSegmentMatcher;
import com.wynntils.models.characterstats.actionbar.matchers.LevelSegmentMatcher;

/**
 * Finds Wynncraft's level in the action bar where Wynntils' {@link LevelSegmentMatcher} does not,
 * so that {@link RetroHud#hideWynncraftsBars} can take it out there too.
 *
 * <p>Wynntils' matcher reads no fonts, tries only the first run of the level's code points in the
 * text, and gives up when that run lacks the level's spacers. Other fonts use those code points, so
 * a glyph ahead of the level hides it: the mount energy's from 38 of 48 energy, while the player
 * rides. This matcher answers only when Wynntils' does not, so the two never mark the same text,
 * which Wynntils' handler would cut twice, and it stands down if Wynntils' matcher is fixed. It
 * takes only a run in the level's font, where those code points are the level's alone.</p>
 *
 * <p>Wynntils' handler calls every matcher in one loop with no catch, whether or not retro is on,
 * so a failure here is guarded: it would stop Wynntils reading the action bar at all.</p>
 */
public final class RetroLevelSegmentMatcher implements ActionBarSegmentMatcher {

    private static final RetroGuard GUARD =
            RetroGuard.stopping(
                    "finding the level in the action bar",
                    "Wynncraft's level may show over retro's until the game restarts");

    private final LevelSegmentMatcher wynntils = new LevelSegmentMatcher();

    @Override
    public ActionBarSegment parse(StyledText actionBar) {
        return GUARD.call(() -> find(actionBar), null);
    }

    private ActionBarSegment find(StyledText actionBar) {
        if (wynntils.parse(actionBar) != null) return null;
        return RetroHud.findLevel(
                        actionBar.getStringWithoutFormatting(),
                        RetroHudFont.indicesIn(actionBar)::get)
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
