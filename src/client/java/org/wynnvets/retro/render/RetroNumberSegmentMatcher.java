package org.wynnvets.retro.render;

import com.wynntils.core.text.StyledText;
import com.wynntils.handlers.actionbar.ActionBarSegment;
import com.wynntils.handlers.actionbar.ActionBarSegmentMatcher;
import com.wynntils.models.characterstats.actionbar.matchers.HealthTextSegmentMatcher;
import com.wynntils.models.characterstats.actionbar.matchers.ManaTextSegmentMatcher;
import java.util.regex.MatchResult;

/**
 * Finds Wynncraft's health or mana number in the action bar where Wynntils' own matcher does not,
 * so that {@link RetroHud} can move it there too, rather than leave it over the armour or bubble
 * row.
 *
 * <p>Wynntils' matchers read no fonts, and give up on the first run in the number's spacers whose
 * value they cannot parse: for mana, the mount energy's from 16 to 31 of 48 energy, while the player
 * rides, if it comes first; for either, a value with a decimal point. This matcher answers only when
 * Wynntils' does not, so the two never mark the same text, which Wynntils' handler would cut twice,
 * and it stands down if Wynntils' matcher is fixed. It takes only a run with a slash in the HUD's
 * font ({@link RetroHud#findNumber}). The value stays unread: Wynntils' health and mana do not
 * change with it, though the number that shows does. Asking Wynntils' matcher again repeats the
 * warning it logs for such a value.</p>
 *
 * <p>Wynntils' handler calls every matcher in one loop with no catch, whether or not retro is on,
 * so a failure here is guarded: it would stop Wynntils reading the action bar at all.</p>
 */
public final class RetroNumberSegmentMatcher implements ActionBarSegmentMatcher {

    private static final RetroGuard GUARD =
            RetroGuard.stopping(
                    "finding the health and mana numbers in the action bar",
                    "Wynncraft's numbers may show over retro's rows until the game restarts");

    private final ActionBarSegmentMatcher wynntils;
    private final boolean health;

    private RetroNumberSegmentMatcher(ActionBarSegmentMatcher wynntils, boolean health) {
        this.wynntils = wynntils;
        this.health = health;
    }

    /**
     * The matcher for the health number.
     *
     * @return it
     */
    public static RetroNumberSegmentMatcher health() {
        return new RetroNumberSegmentMatcher(new HealthTextSegmentMatcher(), true);
    }

    /**
     * The matcher for the mana number.
     *
     * @return it
     */
    public static RetroNumberSegmentMatcher mana() {
        return new RetroNumberSegmentMatcher(new ManaTextSegmentMatcher(), false);
    }

    @Override
    public ActionBarSegment parse(StyledText actionBar) {
        return GUARD.call(() -> find(actionBar), null);
    }

    private ActionBarSegment find(StyledText actionBar) {
        if (wynntils.parse(actionBar) != null) return null;
        return RetroHud.findNumber(
                        actionBar.getStringWithoutFormatting(),
                        RetroHudFont.indicesIn(actionBar)::get,
                        health)
                .map(this::segment)
                .orElse(null);
    }

    private ActionBarSegment segment(MatchResult number) {
        return health
                ? new HealthSegment(number.group(), number.start(), number.end())
                : new ManaSegment(number.group(), number.start(), number.end());
    }

    /** The health number this matcher found, which Wynntils' models do not read. */
    static final class HealthSegment extends ActionBarSegment {

        private HealthSegment(String segmentText, int startIndex, int endIndex) {
            super(segmentText, startIndex, endIndex);
        }
    }

    /** The mana number this matcher found, which Wynntils' models do not read. */
    static final class ManaSegment extends ActionBarSegment {

        private ManaSegment(String segmentText, int startIndex, int endIndex) {
            super(segmentText, startIndex, endIndex);
        }
    }
}
