package org.wynnvets.retro.render;

import java.util.Comparator;
import java.util.List;

/**
 * What {@link RetroActionBar} last read from Wynncraft's action bar for the rows and numbers
 * {@link RetroHud} draws in place of the level badge: the sprint or breath meter, the ultimate's
 * charge and the health and mana numbers. A bar leaves out what is not showing, and some of its
 * frames show only an animation in place of a value, so these rules say what each bar means for
 * what was there before.
 *
 * <p>A charge with no maximum draws no bubbles, which is how {@link RetroActionBar} says it could
 * not read one. Nothing outlives the action bar: {@link #heard} stamps each bar, and past
 * {@link #BAR_LIFE_MILLIS} without one there is nothing to draw. Render thread only; on plain
 * values, so unit-tested.</p>
 */
final class RetroHudReadings {

    /** What a row answers when it is not drawn. */
    static final int NO_ROW = -1;

    /** How long vanilla keeps an action bar on screen: {@code Gui.setOverlayMessage}'s 60 ticks. */
    // Package-private for unit tests. See RetroHudReadingsTest.
    static final long BAR_LIFE_MILLIS = 3000;

    /** The bubbles in the row, of which only a ready ultimate fills the last. */
    // Package-private for unit tests. See RetroHudReadingsTest.
    static final int BUBBLES = RetroHud.ICONS;

    /**
     * A value out of its maximum.
     *
     * @param current the value
     * @param max its maximum
     */
    record Value(int current, int max) {}

    /**
     * What one action bar shows.
     *
     * @param meter the sprint or breath meter, or {@code null} with none
     * @param meterAnimating whether an animation of the meter shows
     * @param ultimateReady whether the ultimate shows as ready
     * @param charge the ultimate's charge, or {@code null} with none
     * @param level whether the level shows, where the ultimate's charge would
     * @param healthNumber the health number's glyphs, or {@code null} with none
     * @param manaNumber the mana number's glyphs, or {@code null} with none
     */
    record Bar(
            Value meter,
            boolean meterAnimating,
            boolean ultimateReady,
            Value charge,
            boolean level,
            String healthNumber,
            String manaNumber) {}

    /**
     * Which of a bar's rims to show. While the badge changes to or from the ultimate's, one bar can
     * hold both, and the normal rim is then its empty glyph, {@code 0/0}, beside the real value. So
     * a rim with a maximum beats one without, and otherwise the emptiest does.
     *
     * @param rims the bar's rims
     * @return the one to show, or {@code null} with none
     */
    static Value shownMeter(List<Value> rims) {
        return rims.stream()
                .min(
                        Comparator.comparing((Value rim) -> rim.max() <= 0)
                                .thenComparingDouble(
                                        rim -> (double) rim.current() / Math.max(1, rim.max())))
                .orElse(null);
    }

    private Value meter;
    private int bubbles = NO_ROW;
    private String healthNumber;
    private String manaNumber;
    private long heardMillis;
    private boolean heard;

    /**
     * Takes in a changed bar. The meter holds through the frames that animate it, and the ultimate
     * through any frame that shows neither it nor the level. A ready ultimate comes first: the
     * flames show around the full charge.
     *
     * @param bar what it shows
     */
    void update(Bar bar) {
        if (bar.meter() != null) {
            meter = bar.meter();
        } else if (!bar.meterAnimating()) {
            meter = null;
        }
        if (bar.ultimateReady()) {
            bubbles = BUBBLES;
        } else if (bar.charge() != null) {
            bubbles = chargeBubbles(bar.charge());
        } else if (bar.level()) {
            bubbles = NO_ROW;
        }
        healthNumber = bar.healthNumber();
        manaNumber = bar.manaNumber();
    }

    /**
     * Notes that an action bar arrived, changed or not.
     *
     * @param millis the time, in milliseconds
     */
    void heard(long millis) {
        heard = true;
        heardMillis = millis;
    }

    /** Forgets everything, when there is no action bar to read. */
    void clear() {
        meter = null;
        bubbles = NO_ROW;
        healthNumber = null;
        manaNumber = null;
        heard = false;
    }

    /**
     * How full the armour row is. A full meter draws no row, as full air draws no bubbles; {@code
     * 0/0} is Wynncraft's empty meter, which draws an empty row.
     *
     * @param millis the time, in milliseconds
     * @return the row's filled halves ({@link RetroHud#halves}), or {@link #NO_ROW}
     */
    int armourHalves(long millis) {
        if (!fresh(millis) || meter == null) return NO_ROW;
        if (meter.max() > 0 && meter.current() >= meter.max()) return NO_ROW;
        return RetroHud.halves(meter.current(), meter.max());
    }

    /**
     * How full the bubble row is.
     *
     * @param millis the time, in milliseconds
     * @return the filled bubbles, from 0 to {@link #BUBBLES}, or {@link #NO_ROW}
     */
    int bubbles(long millis) {
        return fresh(millis) ? bubbles : NO_ROW;
    }

    /**
     * The health number's glyphs.
     *
     * @param millis the time, in milliseconds
     * @return them, or {@code null} with none to draw
     */
    String healthNumber(long millis) {
        return fresh(millis) ? healthNumber : null;
    }

    /**
     * The mana number's glyphs.
     *
     * @param millis the time, in milliseconds
     * @return them, or {@code null} with none to draw
     */
    String manaNumber(long millis) {
        return fresh(millis) ? manaNumber : null;
    }

    private boolean fresh(long millis) {
        return heard && millis - heardMillis <= BAR_LIFE_MILLIS;
    }

    /** The bubbles a charge fills: rounded down, but at least one while there is any. */
    private static int chargeBubbles(Value charge) {
        if (charge.max() <= 0) return NO_ROW;
        if (charge.current() <= 0) return 0;
        return Math.max(1, Math.min(BUBBLES - 1, BUBBLES * charge.current() / charge.max()));
    }
}
