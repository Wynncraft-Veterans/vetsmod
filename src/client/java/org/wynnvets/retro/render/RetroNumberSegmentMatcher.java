package org.wynnvets.retro.render;

import com.wynntils.handlers.actionbar.ActionBarSegment;
import com.wynntils.handlers.actionbar.ActionBarSegmentMatcher;
import com.wynntils.models.characterstats.actionbar.matchers.HealthTextSegmentMatcher;
import com.wynntils.models.characterstats.actionbar.matchers.ManaTextSegmentMatcher;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.MatchResult;

/**
 * Finds Wynncraft's health or mana number where Wynntils' own matcher does not, so that
 * {@link RetroHud} can move it there too, rather than leave it over the armour or bubble row.
 *
 * <p>Wynntils' matchers give up on the first run in the number's spacers whose value they cannot
 * parse: for mana, the mount energy's from 16 to 31 of 48 energy, while the player rides, if it
 * comes first; for either, a value with a decimal point. This takes only a run with a slash in the
 * HUD's font. The value stays unread: Wynntils' health and mana do not change with it, though the
 * number that shows does. Asking Wynntils' matcher again repeats the warning it logs for such a
 * value.</p>
 */
public final class RetroNumberSegmentMatcher extends RetroSegmentMatcher {

    private static final RetroGuard GUARD =
            RetroGuard.stopping(
                    "finding the health and mana numbers in the action bar",
                    "Wynncraft's numbers may show over retro's rows until the game restarts");

    private final boolean health;

    private RetroNumberSegmentMatcher(
            ActionBarSegmentMatcher wynntils, boolean health, SegmentFactory segments) {
        super(wynntils, GUARD, segments);
        this.health = health;
    }

    /**
     * The matcher for the health number.
     *
     * @return it
     */
    public static RetroNumberSegmentMatcher health() {
        return new RetroNumberSegmentMatcher(
                new HealthTextSegmentMatcher(), true, HealthSegment::new);
    }

    /**
     * The matcher for the mana number.
     *
     * @return it
     */
    public static RetroNumberSegmentMatcher mana() {
        return new RetroNumberSegmentMatcher(new ManaTextSegmentMatcher(), false, ManaSegment::new);
    }

    @Override
    Optional<MatchResult> find(String actionBar, IntPredicate inFont) {
        return RetroHudGlyphs.findNumber(actionBar, inFont, health);
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
