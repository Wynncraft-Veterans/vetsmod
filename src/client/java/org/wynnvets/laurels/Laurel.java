package org.wynnvets.laurels;

/**
 * One laurel a player holds: the metric it was won in and the place, 1 to {@value #PLACES}.
 *
 * <p>The place picks the row of {@code laurels.png}, so first place is the gold row and the third
 * the bronze one.</p>
 *
 * @param metric the metric the laurel was won in
 * @param place the place held, from 1 to {@value #PLACES}
 */
public record Laurel(LaurelMetric metric, int place) {

    /** How many places a metric awards a laurel for. */
    public static final int PLACES = 3;

    /**
     * The row of {@code laurels.png} that draws this place.
     *
     * @return the zero-based sheet row
     */
    public int row() {
        return place - 1;
    }
}
