package org.wynnvets.retro.render;

import com.wynntils.core.text.StyledText;
import com.wynntils.handlers.actionbar.ActionBarSegment;
import com.wynntils.handlers.actionbar.ActionBarSegmentMatcher;

/**
 * Finds a piece of Wynncraft's HUD in the action bar where Wynntils' own matcher does not, so that
 * {@link RetroActionBar#hideWynncraftsBars} can take it out there too. Wynntils' matchers read no
 * fonts, so another font's glyphs on the same code points can hide what they look for.
 *
 * <p>This answers only when Wynntils' matcher does not, so the two never mark the same text, which
 * Wynntils' handler would cut twice, and it stands down if Wynntils' matcher is fixed. That handler
 * calls every matcher in one loop with no catch, whether or not retro is on, so a failure here is
 * guarded: it would stop Wynntils reading the action bar at all.</p>
 */
abstract class RetroSegmentMatcher implements ActionBarSegmentMatcher {

    private final ActionBarSegmentMatcher wynntils;
    private final RetroGuard guard;

    /**
     * Stands in for one of Wynntils' matchers.
     *
     * @param wynntils the matcher to answer after
     * @param guard stops this matcher if it fails
     */
    RetroSegmentMatcher(ActionBarSegmentMatcher wynntils, RetroGuard guard) {
        this.wynntils = wynntils;
        this.guard = guard;
    }

    @Override
    public final ActionBarSegment parse(StyledText actionBar) {
        return guard.call(() -> wynntils.parse(actionBar) != null ? null : find(actionBar), null);
    }

    /**
     * Looks for the segment Wynntils' matcher did not find.
     *
     * @param actionBar the action bar
     * @return the segment, or {@code null} when there is none
     */
    abstract ActionBarSegment find(StyledText actionBar);
}
